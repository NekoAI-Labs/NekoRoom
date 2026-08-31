package com.moeavatar.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 流水线 TTS：合成线程 ‖ 播放线程，中间一个有界 Channel 缓冲合成结果。
 *
 *   句1 → [synth worker] → ─┐    ┌─→ [audio worker] → 句1 播
 *   句2 → [synth worker] → ─┼ Ch ┼─→ [audio worker] → 句2 播
 *
 * 关键：play worker 维护**单个共享 AudioTrack**（MODE_STREAM），所有句子的
 * PCM 顺序 write 进同一个环形缓冲。WRITE_BLOCKING 提供天然背压，缓冲区满了
 * 自动等下一帧空出来，缓冲区有数据就连续放音 —— 句与句之间没有任何
 * stop/start/sleep gap。
 *
 * 现在 backend 抽象成 [TtsBackend]：本地 BertVITS2 一次性吐一个 PcmChunk，
 * MiniMax 流式可能吐 N 个 chunk —— playJob 不区分，按到达顺序往 AudioTrack 写。
 * 这就是首字延迟低的关键：远端 100ms 后第一个 chunk 就能开始放，不用等整句合成完。
 */
class SpeechQueue(
    @Volatile private var backend: TtsBackend,
    private val speakerProvider: () -> String,
    private var mouthListener: ((Float) -> Unit)? = null,
) {
    /** 后续 Activity 创建好 Live2D 控制器再注入 listener，简化构造时序。 */
    fun setMouthListener(l: ((Float) -> Unit)?) { mouthListener = l }

    /**
     * 播放状态变化回调：playJob 开始消费 PCM（true）/ 完全静默（false）。
     * 用来驱动电话模式打断按钮 UI（3 跳点 ↔ 方块按钮）。
     * 回调在 IO 线程触发，调用方需自行切主线程。
     *
     * 注意：false 在多种路径都会触发（自然耗尽 / pauseImmediately /
     * stopAndAwaitSilence / clear），无法区分。要区分"自然播完"请用
     * [onPlaybackExhausted]。
     */
    @Volatile private var onPlaybackStateChange: ((Boolean) -> Unit)? = null
    fun setOnPlaybackStateChange(l: (Boolean) -> Unit) { onPlaybackStateChange = l }
    private fun firePlaybackState(playing: Boolean) {
        runCatching { onPlaybackStateChange?.invoke(playing) }
    }

    /**
     * 电话模式仍用 VOICE_COMMUNICATION 做采集/AEC，播放始终走媒体流。
     * 一些机型把通信播放映射到较低的通话音量，导致 TTS 变小、用户必须大声说话。
     */
    @Volatile private var phoneCallAudioMode = false

    fun setPhoneCallAudioMode(enabled: Boolean) {
        if (phoneCallAudioMode == enabled) return
        phoneCallAudioMode = enabled
        Log.i(TAG, "phoneCall=$enabled capture=voice_communication playback=media/music")
    }

    /**
     * **仅在 playJob 自然耗尽时**触发（line 117 路径）。
     * 不会在 pauseImmediately / stopAndAwaitSilence / clear 时触发。
     *
     * 用来区分"TTS 真正播完了"和"被中断/新一轮清空了"——
     * 电话模式 AI 输出收尾时只有这个回调能告诉 UI"该回聆听了"。
     */
    @Volatile private var onPlaybackExhausted: (() -> Unit)? = null
    fun setOnPlaybackExhausted(l: () -> Unit) { onPlaybackExhausted = l }

    /**
     * v2 字幕同步：每段 PCM 开始播放时调，参数是该段对应的源文本（clause 级）。
     * 在 IO 线程触发，调用方自行切主线程。barge-in 路径不会触发。
     */
    @Volatile private var onClauseStart: (suspend (String) -> Unit)? = null
    fun setOnClauseStart(l: suspend (String) -> Unit) { onClauseStart = l }

    /** 设置里切换 TTS 后端时调。新 backend 必须已经 prepare 好。 */
    fun swapBackend(newBackend: TtsBackend) {
        backend = newBackend
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var textCh: Channel<String> = Channel(Channel.UNLIMITED)
    private var pcmCh: Channel<PcmChunk> = Channel(capacity = 4)

    private var synthJob: Job? = null
    private var playJob: Job? = null
    private var idleCheckJob: Job? = null
    private var lipJob: Job? = null

    // 口型包络：按 LIP_HOP 帧一段，存 RMS。索引 = 绝对帧 / LIP_HOP（与 framesWritten 对齐，
    // 同一条 track 连续追加；track 重建时清空）。ticker 读 playbackHeadPosition 采样对应段，
    // 让嘴型跟真正听到的声音同步。
    private val lipEnvelope = ArrayList<Float>()
    private val lipLock = Any()

    // 共享 AudioTrack：相同采样率的连续 chunk 复用，跨采样率时才重建
    @Volatile private var sharedTrack: AudioTrack? = null
    @Volatile private var sharedSr: Int = 0
    /**
     * AudioTrack 的 release 与 idle coroutine 在不同线程。每次替换或摘除 track 都递增
     * generation；异步任务只能操作自己拿到的 lease，不能操作后来已经被 release 的对象。
     */
    private val trackLock = Any()
    @Volatile private var playbackGeneration: Long = 0L
    private data class TrackLease(val track: AudioTrack, val generation: Long)
    // 已写入当前 track 的总帧数。WRITE_BLOCKING 返回≠播完，idle 判定停 track 前要等
    // playbackHeadPosition 追上它，否则最后一句音频会被 stop() 拦腰切掉。
    @Volatile private var framesWritten: Long = 0
    @Volatile private var synthActive: Boolean = false
    @Volatile private var workStarted: Boolean = false
    @Volatile private var playbackExhaustedFired: Boolean = true

    // mic 按下时 muted=true：堵 enqueue 上游，新句子进不来。
    @Volatile private var muted: Boolean = false

    // **真正的硬闸门**：halted=true 时整条 pipeline 死透：
    //   - synth 一开始检查直接丢，不调 backend.synth
    //   - writeToShared 一开始检查直接丢，**不会新建 AudioTrack**
    // 这是修"mic 按下还有破碎声"的核心 —— pcmCh 残留进 writeToShared 不加这个闸会
    // 自动 buildTrack 接着播，跟 AudioRecord 抢 HAL。
    @Volatile private var halted: Boolean = false

    private fun activeTrackLease(): TrackLease? = synchronized(trackLock) {
        sharedTrack?.let { TrackLease(it, playbackGeneration) }
    }

    private fun isActive(lease: TrackLease): Boolean = synchronized(trackLock) {
        sharedTrack === lease.track && playbackGeneration == lease.generation
    }

    /** Atomically make a track inaccessible to all delayed jobs before releasing it. */
    private fun detachTrack(expected: TrackLease? = null): AudioTrack? = synchronized(trackLock) {
        val current = sharedTrack ?: return@synchronized null
        if (expected != null && (current !== expected.track || playbackGeneration != expected.generation)) {
            return@synchronized null
        }
        sharedTrack = null
        sharedSr = 0
        framesWritten = 0
        playbackGeneration++
        current
    }

    private fun releaseTrack(track: AudioTrack?) {
        if (track == null) return
        runCatching { track.stop() }
        runCatching { track.release() }
    }

    fun start() {
        if (synthJob?.isActive == true && playJob?.isActive == true) return
        // 重启 = 重新开张，闸门必须复位。否则 stopAndAwaitSilence() 后 start()
        // 留下 halted=true / muted=true → synth/enqueue 全被吞，听感是"切换后无声"。
        halted = false
        muted = false
        synthActive = false
        workStarted = false
        playbackExhaustedFired = true
        idleCheckJob?.cancel()
        idleCheckJob = null
        // 口型 ticker：按 LIP_TICK_MS 采样当前播放头对应的包络段，推 [0,1] 给 mouthListener。
        // 独立于 synth/play，随 pipeline 一起重启即可。
        lipJob?.cancel()
        lipJob = scope.launch {
            while (true) {
                delay(LIP_TICK_MS)
                driveMouth()
            }
        }
        // 新建 channel 并把引用存到本地：synthJob/playJob 闭包用本地引用，
        // 不读字段。这样 stopAndAwaitSilence/clear 重新赋值字段后，旧的
        // synthJob（可能还在 NonCancellable 的 infer.infer 里）回来时往
        // 旧的 pcmCh send → 已关闭 → 立刻退出，不会污染新一轮的 channel。
        val localTextCh = Channel<String>(Channel.UNLIMITED)
        val localPcmCh = Channel<PcmChunk>(capacity = 4)
        textCh = localTextCh
        pcmCh = localPcmCh

        synthJob = scope.launch {
            for (text in localTextCh) {
                if (halted) continue
                synthActive = true
                var fallbackTextAttached = false
                runCatching {
                    val be = backend
                    val spk = speakerProvider()
                    // backend.synth 是一个 cold Flow —— 远端流式可以边收边 emit，
                    // 这里逐 chunk 推进 pcmCh，playJob 立刻就能开始放第一个 chunk。
                    be.synth(text, spk) { halted }.collect { rawChunk ->
                        if (halted) return@collect
                        // 在线流式 backend 的 PCM chunk 通常不回传源文本。只给第一个
                        // 有效音频块补上 enqueue 时的句子，避免每个网络 chunk 重复刷新字幕。
                        val chunk = if (
                            rawChunk.text.isBlank() &&
                            !fallbackTextAttached &&
                            rawChunk.samples.isNotEmpty()
                        ) {
                            fallbackTextAttached = true
                            rawChunk.copy(text = text)
                        } else {
                            rawChunk
                        }
                        // send 到已关闭的 localPcmCh 会抛 ClosedSendChannelException；
                        // stopAndAwaitSilence/clear 已 close，这里吞掉避免误报 "synth failed"。
                        // infer.infer() 在 NonCancellable 里已经跑完，这里只是 send 被取消。
                        try { localPcmCh.send(chunk) } catch (e: kotlinx.coroutines.channels.ClosedSendChannelException) {
                            return@collect
                        }
                    }
                }.onFailure { Log.e(TAG, "synth failed: $text", it) }
                synthActive = false
                schedulePlaybackIdleCheck(if (sharedTrack == null) 80L else PLAYBACK_DRAIN_DELAY_MS)
            }
            // synthJob 自然跑完所有 text（没有 cancel）：告诉 playJob 没有更多 PCM 了。
            try { localPcmCh.close() } catch (_: Throwable) {}
        }

        playJob = scope.launch {
            for (pcm in localPcmCh) {
                // 字幕是音频播放的前置屏障：主线程完成上屏之后才写 AudioTrack。
                // 这保证字幕绝不会落在声音后面，不依赖每字时长或固定等待秒数。
                if (pcm.text.isNotEmpty()) {
                    runCatching { onClauseStart?.invoke(pcm.text) }
                }
                if (halted) continue
                runCatching { writeToShared(pcm.samples, pcm.sr) }
                    .onFailure { Log.e(TAG, "play failed", it) }
                schedulePlaybackIdleCheck(PLAYBACK_DRAIN_DELAY_MS)
            }
            // localPcmCh 自然耗尽（synthJob 跑完关闭，playJob 消费完）：
            // 这是 AI 输出真正结束的唯一信号。stopAndAwaitSilence/clear 路径
            // 会先 cancel playJob，不会走到这里——它们不会触发 onPlaybackExhausted。
            //
            // 即使 sharedTrack == null（LLM 输出空、没合成出 PCM）也要触发，
            // 否则 aiBusy 永远不清，UI 卡在打断按钮。
            releaseTrack(detachTrack())
            Log.d(TAG, "playback: playJob exhausted (silence)")
            firePlaybackState(false)
            runCatching { onPlaybackExhausted?.invoke() }
        }
    }

    fun enqueue(sentence: String) {
        if (sentence.isBlank()) return
        if (muted) return
        if (textCh.trySend(sentence).isSuccess) {
            workStarted = true
            playbackExhaustedFired = false
        }
    }

    /**
     * 是否有 PCM 还在播放/等待播放。**主线程安全**。
     *
     * 用来在电话模式里做回声保护：TTS 正在说话时 voiceGate 不该误触发 ASR，
     * 录音中也应该立刻放弃。
     */
    fun isPlaying(): Boolean = sharedTrack != null && !halted

    /**
     * 是否有**待处理**的 TTS 工作（textCh 有未合成句子 / pcmCh 有未播放 PCM / 正在播）。
     *
     * 比 [isPlaying] 更宽：覆盖 LLM 流结束→第一个 PCM 到达之间的 synth 窗口。
     * 用来阻止 ASR 在 TTS synth 期间提前重启（那会和 synth 抢 libMNN → SIGSEGV，
     * 也会让 UI 错误切回聆听态）。
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun hasPendingWork(): Boolean {
        if (halted) return false
        if (sharedTrack != null) return true
        if (synthActive || workStarted) return true
        // Channel 没有公开 isEmpty，用 isClosedForReceive 反向判断不太准；
        // 用 tryReceive 探测：非空就 true，空就 false（不消费数据）。
        // 但 tryReceive 会消费，不行。改用 isEmpty 属性（kotlinx.channels.Channel 有）。
        return !textCh.isEmpty || !pcmCh.isEmpty
    }

    /** mic 按下：闸门关上。后续 LLM 残留 token 调 enqueue 直接丢。 */
    fun mute() { muted = true }
    /** mic 松手：闸门打开，恢复正常合成播放。 */
    fun unmute() { muted = false; halted = false }

    /**
     * **同步、毫秒级**：让正在播的 AudioTrack 立刻闭嘴 + 拉起硬闸门。
     *
     * - halted=true：synth/writeToShared 立刻短路，不再合成、不再建 track
     * - pause+flush：当前 track 解 BLOCKING write，听感立刻闭嘴
     *
     * 必须能在 UI 线程直接调 —— 不释放、不 join、不等 IO。<5ms。
     */
    fun pauseImmediately() {
        halted = true
        muted = true
        sharedTrack?.let {
            try { it.pause() } catch (_: Throwable) {}
            try { it.flush() } catch (_: Throwable) {}
        }
        Log.d(TAG, "playback: pauseImmediately (silence)")
        firePlaybackState(false)
    }

    /**
     * 关键打断接口：**同步**等 TTS 真正静默后再返回。
     *
     * 必须在 IO 线程调用（join 会阻塞最多 ~150ms）。
     */
    suspend fun stopAndAwaitSilence(timeoutMs: Long = 300) {
        muted = true
        halted = true
        backend.cancelSynthesis()
        val oldTrack = detachTrack()
        val oldSynth = synthJob
        val oldPlay = playJob
        idleCheckJob?.cancel()
        idleCheckJob = null
        synthActive = false
        workStarted = false
        playbackExhaustedFired = true
        oldTrack?.let {
            try { it.pause() } catch (_: Throwable) {}
            try { it.flush() } catch (_: Throwable) {}
        }
        try { textCh.close() } catch (_: Throwable) {}
        try { pcmCh.close() } catch (_: Throwable) {}
        oldSynth?.cancel()
        oldPlay?.cancel()
        synthJob = null
        playJob = null
        withTimeoutOrNull(timeoutMs) {
            runCatching { oldPlay?.join() }
            runCatching { oldSynth?.join() }
        }
        releaseTrack(oldTrack)
        Log.d(TAG, "playback: stopAndAwaitSilence done (silence)")
        firePlaybackState(false)
        start()
    }

    /** 切换 backend / 普通打断时使用，不阻塞调用线程。 */
    fun clear() {
        // This must be a terminal operation for the current response.  In the old flow clear()
        // called start() below: start() reopened halted/muted before a cancelled native synth
        // coroutine had actually returned, so its final PCM could create a fresh AudioTrack and
        // steal phone-call UI back from Hearing to Speaking.  The next real submit path calls
        // stopAndAwaitSilence(), which creates a new pipeline explicitly.
        muted = true
        halted = true
        backend.cancelSynthesis()
        val oldTrack = detachTrack()
        val oldSynth = synthJob
        val oldPlay = playJob
        idleCheckJob?.cancel()
        idleCheckJob = null
        synthActive = false
        workStarted = false
        playbackExhaustedFired = true
        oldTrack?.let {
            try { it.pause() } catch (_: Throwable) {}
            try { it.flush() } catch (_: Throwable) {}
        }
        textCh.close()
        pcmCh.close()
        oldSynth?.cancel()
        oldPlay?.cancel()
        synthJob = null
        playJob = null
        scope.launch {
            runCatching { oldPlay?.join() }
            runCatching { oldSynth?.join() }
            releaseTrack(oldTrack)
        }
        Log.d(TAG, "playback: clear (silence)")
        firePlaybackState(false)
    }

    fun release() {
        textCh.close()
        pcmCh.close()
        idleCheckJob?.cancel()
        idleCheckJob = null
        lipJob?.cancel()
        lipJob = null
        synthActive = false
        workStarted = false
        playbackExhaustedFired = true
        synthJob?.cancel()
        playJob?.cancel()
        synthJob = null
        playJob = null
        releaseTrack(detachTrack())
        scope.cancel()
    }

    /**
     * 写到共享 track。同采样率直接 write（WRITE_BLOCKING 自动背压），
     * 不同采样率才重建。**不 sleep、不 stop**，连续两句之间无缝。
     *
     * **halted 检查必须在最前**：mic 按下后 pcmCh 里残留的 chunk 走到这里时，
     * 没有 halted 闸门就会触发 buildTrack 重建一条新 track 接着放 → 跟 AudioRecord
     * 抢 HAL → 破碎声。
     */
    private fun writeToShared(samples: FloatArray, sampleRate: Int) {
        if (halted) return
        workStarted = true
        playbackExhaustedFired = false
        var lease = activeTrackLease()
        if (lease == null || sharedSr != sampleRate) {
            releaseTrack(detachTrack(lease))
            val track = buildTrack(sampleRate)
            synchronized(trackLock) {
                // 仅在新 track 成为 active 后才允许异步 idle task 取得它。
                playbackGeneration++
                sharedTrack = track
                sharedSr = sampleRate
                framesWritten = 0
                lease = TrackLease(track, playbackGeneration)
            }
            synchronized(lipLock) { lipEnvelope.clear() }
            try { track.play() } catch (e: IllegalStateException) {
                detachTrack(lease)
                releaseTrack(track)
                throw e
            }
            Log.i(
                TAG,
                "playback usage=media content=music sr=$sampleRate route=${track.routedDevice?.type ?: "default"}",
            )
            firePlaybackState(true)
        }
        val active = lease ?: return
        if (!isActive(active)) return
        val track = active.track
        recordLipEnvelope(samples)
        // 先记账再写：idle 判定要靠这个总帧数等播放头追上，若等 write 循环结束再记账，
        // 会有一个窗口 framesWritten=0 → idle 误判已放完把刚起播的 track 停掉。
        framesWritten += samples.size
        var written = 0
        while (written < samples.size) {
            if (halted) break
            if (!isActive(active)) return
            val w = try {
                track.write(samples, written, samples.size - written, AudioTrack.WRITE_BLOCKING)
            } catch (_: IllegalStateException) {
                // A concurrent clear detached this lease. It is not an audio error and must not
                // resurrect or access the released track.
                return
            }
            if (w <= 0) break
            written += w
        }
    }

    /** 把一段 PCM 的 RMS 包络按 LIP_HOP 帧追加到 lipEnvelope（与 framesWritten 对齐）。 */
    private fun recordLipEnvelope(samples: FloatArray) {
        synchronized(lipLock) {
            var i = 0
            while (i < samples.size) {
                val end = (i + LIP_HOP).coerceAtMost(samples.size)
                var sum = 0f
                for (j in i until end) sum += samples[j] * samples[j]
                lipEnvelope.add(kotlin.math.sqrt(sum / (end - i)))
                i = end
            }
        }
    }

    /** ticker：按当前 track 播放头取包络段 → [0,1] 张嘴度。无 track 时闭嘴。 */
    private fun driveMouth() {
        val t = sharedTrack
        if (t == null || halted || t.state != AudioTrack.STATE_INITIALIZED) {
            mouthListener?.invoke(0f); return
        }
        // Track 可能在别的线程 release 掉,访问 playbackHeadPosition 会抛 IllegalStateException。
        val head = try { t.playbackHeadPosition.toLong() and 0xFFFFFFFFL }
        catch (_: IllegalStateException) { mouthListener?.invoke(0f); return }
        val idx = (head / LIP_HOP).toInt()
        val rms = synchronized(lipLock) {
            if (lipEnvelope.isEmpty()) 0f
            else lipEnvelope[idx.coerceIn(0, lipEnvelope.size - 1)]
        }
        val mouth = ((rms - LIP_NOISE_FLOOR) * LIP_GAIN).coerceIn(0f, 1f)
        mouthListener?.invoke(mouth)
    }

    private fun buildTrack(sampleRate: Int): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        val bufBytes = maxOf(minBuf, sampleRate * 4 /*float*/ * 1)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    companion object {
        private const val TAG = "MoeAvatar.Speech"
        private const val PLAYBACK_DRAIN_DELAY_MS = 900L

        // 口型：LIP_HOP 帧一段 RMS（1024 帧 ≈ 21ms@48k），ticker 每 LIP_TICK_MS 采样一次。
        private const val LIP_HOP = 1024
        private const val LIP_TICK_MS = 25L
        private const val LIP_NOISE_FLOOR = 0.008f
        private const val LIP_GAIN = 9f
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun schedulePlaybackIdleCheck(delayMs: Long) {
        idleCheckJob?.cancel()
        idleCheckJob = scope.launch {
            delay(delayMs)
            if (halted || muted || playbackExhaustedFired || !workStarted) return@launch
            if (synthActive || !textCh.isEmpty || !pcmCh.isEmpty) return@launch
            val lease = activeTrackLease()
            if (lease != null) {
                // 通道空了不代表放完：WRITE_BLOCKING 只保证写入 track 缓冲，缓冲里还有
                // 没播的帧。等播放头追上已写入帧数再停，否则最后一句被切。等待期间若有
                // 新句进来（synth/channel 非空），让位给正常流程，本次不停。
                while (!halted && !muted && !synthActive && textCh.isEmpty && pcmCh.isEmpty) {
                    if (!isActive(lease)) return@launch
                    val head = try {
                        lease.track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                    } catch (_: IllegalStateException) {
                        // clear/stop 已经摘除并 release 了此 track；lease 已失效，直接退出。
                        return@launch
                    }
                    if (head >= framesWritten) break
                    delay(50)
                }
                if (halted || muted || synthActive || !textCh.isEmpty || !pcmCh.isEmpty) return@launch
                // 不能 release 后来新建的 track；只摘除自己持有的 generation。
                releaseTrack(detachTrack(lease))
            }
            workStarted = false
            playbackExhaustedFired = true
            Log.d(TAG, "playback: idle exhausted (silence)")
            firePlaybackState(false)
            runCatching { onPlaybackExhausted?.invoke() }
        }
    }
}
