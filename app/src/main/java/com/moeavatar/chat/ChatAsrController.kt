package com.moeavatar.chat

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.moeavatar.MnnGlobalLock
import com.moeavatar.model.ModelManager
import com.k2fsa.sherpa.mnn.EndpointConfig
import com.k2fsa.sherpa.mnn.EndpointRule
import com.k2fsa.sherpa.mnn.FeatureConfig
import com.k2fsa.sherpa.mnn.OnlineModelConfig
import com.k2fsa.sherpa.mnn.OnlineRecognizer
import com.k2fsa.sherpa.mnn.OnlineRecognizerConfig
import com.k2fsa.sherpa.mnn.OnlineTransducerModelConfig
import com.k2fsa.sherpa.mnn.OnlineStream
import com.k2fsa.sherpa.mnn.SherpaNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.io.File
import kotlin.math.sqrt

/**
 * Chat 页内嵌的流式 ASR 控制器。
 *
 * 与独立 [com.moeavatar.asr.AsrActivity] 的区别：
 * - 不读 config.json，硬编码模型目录与文件名（与 MnnAsrTest 对齐）。
 * - 模型路径默认 `/sdcard/NekoChatMini/models/asr`（三套模型统一根目录，方便一次 adb push）。
 *   App 有「所有文件访问权限」，可直接读 /sdcard 下的顶级目录。
 * - 录音线程把 partial text 推回 UI；松手后返回 final text，由调用方决定是否发送。
 */
class ChatAsrController(private val ctx: Context) {

    @Volatile private var recognizer: OnlineRecognizer? = null
    private val recording = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null
    /** Continuous mode consumes PCM on a separate worker; capture must never wait for MNN. */
    private var continuousDecodeThread: Thread? = null

    val isReady: Boolean get() = recognizer != null

    fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    /** 在 IO 线程调用。返回 false 表示模型未安装或加载失败（路径/权限/文件名不对）。 */
    fun prepare(modelDir: String = ModelManager.dirOf(ctx, ModelManager.activeAsr(ctx)).absolutePath): Boolean {
        if (recognizer != null) return true
        // 关键：文件不全时不能调 sherpa 构造器——它不抛异常,只会留下一个 native 指针为空的
        // 破对象,后续 createStream 会 SIGSEGV。先按 ModelManager 校验完整性。
        val selectedModel = ModelManager.activeAsr(ctx)
        if (!ModelManager.isInstalled(ctx, selectedModel)) {
            Log.i(TAG, "ASR files not installed under $modelDir; skip prepare")
            return false
        }
        return try {
            // OnlineRecognizer construction also touches MNN Express globals.  It must not
            // overlap SuperTonic/LLM native model creation or release.
            MnnGlobalLock.lock.lock()
            try {
                SherpaNative.ensureLoaded()
                val cfg = buildConfig(modelDir)
                recognizer = OnlineRecognizer(assetManager = null, config = cfg)
            } finally {
                MnnGlobalLock.lock.unlock()
            }
            Log.i(TAG, "ASR ready, model=${selectedModel.id}, modelDir=$modelDir")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "ASR load failed: $modelDir", t)
            runCatching { recognizer?.release() }
            recognizer = null
            false
        }
    }

    /**
     * 长按开始录音。`onPartial` 在录音线程被频繁调用，调用方负责切回主线程。
     * 已经在录或还没 prepare 都直接返回 false。
     */
    @SuppressLint("MissingPermission")
    fun startRecording(
        onPartial: (String) -> Unit,
        onAmplitude: (Float) -> Unit = {},
    ): Boolean {
        val rec = recognizer ?: return false
        if (!hasAudioPermission()) return false
        if (recording.getAndSet(true)) return false

        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC, SR,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
        ).also { it.startRecording() }

        recordThread = Thread {
            val stream = rec.createStream("")
            val buffer = ShortArray((0.1 * SR).toInt())
            val emitted = StringBuilder()
            var lastPartial = ""

            // 性能指标(只打 logcat,不显示在 UI)
            val recStart = System.currentTimeMillis()
            var totalSamples = 0L
            var decodeNs = 0L

            try {
                while (recording.get()) {
                    val n = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (n <= 0) continue
                    totalSamples += n
                    val samples = FloatArray(n) { i -> buffer[i] / 32768.0f }
                    var energy = 0.0
                    for (sample in samples) energy += sample * sample
                    val rms = sqrt(energy / n).toFloat()
                    onAmplitude(((rms - 0.008f) * 12f).coerceIn(0f, 1f))
                    stream.acceptWaveform(samples, SR)

                    val t0 = System.nanoTime()
                    while (rec.isReady(stream)) {
                        // ASR decode 与 TTS infer 共享 libMNN.so，并发跑会 SIGSEGV。
                        // 持全局锁串行化：TTS infer 持锁时这里阻塞，反之亦然。
                        MnnGlobalLock.lock.lock()
                        try { rec.decode(stream) } finally { MnnGlobalLock.lock.unlock() }
                    }
                    decodeNs += System.nanoTime() - t0

                    val partial = rec.getResult(stream).text
                    val combined = emitted.toString() + partial
                    if (combined != lastPartial) {
                        lastPartial = combined
                        onPartial(combined)
                    }

                    if (rec.isEndpoint(stream)) {
                        if (partial.isNotEmpty()) {
                            emitted.append(partial)
                            lastPartial = emitted.toString()
                            onPartial(lastPartial)
                        }
                        rec.reset(stream)
                    }
                }

                // tail flush
                val tail = FloatArray((0.5 * SR).toInt())
                stream.acceptWaveform(tail, SR)
                val t0 = System.nanoTime()
                decodeReady(rec, stream)
                decodeNs += System.nanoTime() - t0
                val tailText = rec.getResult(stream).text
                if (tailText.isNotEmpty()) emitted.append(tailText)

                val finalText = emitted.toString()
                pendingFinal = finalText

                val audioSec = totalSamples / SR.toDouble()
                val decodeMs = decodeNs / 1_000_000.0
                val rtf = if (audioSec > 0) decodeMs / 1000.0 / audioSec else 0.0
                Log.i(
                    TAG,
                    "ASR done: text='$finalText' audio=${"%.2f".format(audioSec)}s " +
                            "decode=${"%.0f".format(decodeMs)}ms RTF=${"%.3f".format(rtf)} " +
                            "wall=${System.currentTimeMillis() - recStart}ms"
                )
            } catch (t: Throwable) {
                Log.e(TAG, "asr loop error", t)
            } finally {
                runCatching { stream.release() }
            }
        }.also { it.start() }
        return true
    }

    /**
     * 停录音并等录音线程跑完 tail flush，返回最终识别文本。
     * 必须在主线程调用（UI 触发的松手事件），最多阻塞 ~1.2s。
     */
    fun stopRecordingAndFinalize(timeoutMs: Long = 1200): String {
        if (!recording.getAndSet(false)) return pendingFinal
        runCatching { audioRecord?.stop(); audioRecord?.release() }
        audioRecord = null
        recordThread?.join(timeoutMs)
        recordThread = null
        val out = pendingFinal
        pendingFinal = ""
        return out
    }

    fun release() {
        recording.set(false)
        runCatching { audioRecord?.stop(); audioRecord?.release() }
        audioRecord = null
        recordThread?.join(500)
        continuousDecodeThread?.join(1500)
        recordThread = null
        continuousDecodeThread = null
        MnnGlobalLock.lock.lock()
        try {
            recognizer?.release()
            recognizer = null
        } finally {
            MnnGlobalLock.lock.unlock()
        }
    }

    @Volatile private var pendingFinal: String = ""

    /**
     * 一站式「开始录音 → 静音/端点自动停 → 返回 final 文本」。
     *
     * 旧版本用 `startRecording + delay(5.2s) + stopRecordingAndFinalize` 三段式，
     * 固定窗口既切句又夹尾。Round 3 改 RMS Hysteresis 收尾；**Round 4 改用 VAD 入口 + 文本稳定兜底**：
     *
     * 起音检测**不在这**——VadGate（Silero VAD）触发时调本函数，已经知道用户在说话。
     * 本函数只关心：
     *  - **preRoll 喂入**（VAD 触发前 300ms pre-roll）→ cover 起音头
     *  - **主信号**：`rec.isEndpoint(stream)` 触发（sherpa 自带端点）
     *  - **兜底 1**：1500ms 还没出 partial → 用户根本没说话，给空串
     *  - **兜底 2**：partial 稳定 [PARTIAL_STALE_MS]ms 没变化 → 认作用户说完了
     *  - **兜底 3**：超过 [maxMs] → 强制停（默认 12s）
     *
     * 收尾后做 0.5s tail flush 让 sherpa 出最后 partial。
     *
     * **支持外部取消**：调用方 lifecycleScope.cancel() / cancelRecording() 都会让协程退出。
     * 协程在 IO 调度器上跑，主线程可 await。
     */
    @SuppressLint("MissingPermission")
    suspend fun recordUntilSilence(
        maxMs: Long = 12000L,
        preRoll: ShortArray? = null,
        @Suppress("UNUSED_PARAMETER") startThreshold: Float? = null,
        onPartial: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val rec = recognizer ?: return@withContext ""
        if (!hasAudioPermission()) return@withContext ""
        if (recording.getAndSet(true)) return@withContext ""

        val minBuf = AudioRecord.getMinBufferSize(
            SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val audioRec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SR,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, SR / 5),
            ).also { it.startRecording() }
        } catch (t: Throwable) {
            Log.e(TAG, "recordUntilSilence: AudioRecord init failed", t)
            recording.set(false)
            return@withContext ""
        }
        audioRecord = audioRec

        var stream: com.k2fsa.sherpa.mnn.OnlineStream? = null
        val finalHolder = StringBuilder()
        try {
            stream = rec.createStream("")

            // 先喂 pre-roll：把 voiceGate 触发前的 300ms 音频补进 stream，
            // 否则从 voiceGate 触到 ASR 真正起读这一拍之间的用户起音头会丢。
            if (preRoll != null && preRoll.isNotEmpty()) {
                val preFloat = FloatArray(preRoll.size) { i -> preRoll[i] / 32768.0f }
                stream.acceptWaveform(preFloat, SR)
                decodeReady(rec, stream)
                val prePartial = rec.getResult(stream).text
                if (prePartial.isNotEmpty()) onPartial(prePartial)
            }

            // 30ms 帧
            val buffer = ShortArray((0.03 * SR).toInt())
            val emitted = StringBuilder()
            var lastPartial = ""
            var firstPartialMs = 0L   // 第一个非空 partial 出现的时间
            var lastChangeMs = 0L     // partial 最后一次变化的时间
            val startMs = System.currentTimeMillis()

            while (recording.get()) {
                val elapsed = System.currentTimeMillis() - startMs
                if (elapsed >= maxMs) {
                    Log.i(TAG, "recordUntilSilence: max ${maxMs}ms reached")
                    break
                }

                val n = audioRec.read(buffer, 0, buffer.size)
                if (n <= 0) continue

                val samples = FloatArray(n) { i -> buffer[i] / 32768.0f }
                stream.acceptWaveform(samples, SR)
                decodeReady(rec, stream)
                val partial = rec.getResult(stream).text
                val combined = emitted.toString() + partial
                if (combined != lastPartial) {
                    lastPartial = combined
                    val now = System.currentTimeMillis()
                    if (firstPartialMs == 0L && partial.isNotEmpty()) firstPartialMs = now
                    lastChangeMs = now
                    onPartial(combined)
                }

                // 兜底 1: 启动 1500ms 还没出 partial,放弃
                if (firstPartialMs == 0L && elapsed > 1500L) {
                    Log.i(TAG, "recordUntilSilence: no partial in ${elapsed}ms, give up")
                    break
                }
                // 兜底 2: partial 已经稳定 (最后一字后再没新增) 超过 PARTIAL_STALE_MS,认作用户说完了
                if (lastChangeMs > 0L &&
                    System.currentTimeMillis() - lastChangeMs > PARTIAL_STALE_MS
                ) {
                    Log.i(TAG, "recordUntilSilence: partial stable for ${PARTIAL_STALE_MS}ms")
                    if (partial.isNotEmpty()) emitted.append(partial)
                    break
                }

                // 收尾主信号: ASR 自带端点检测
                if (rec.isEndpoint(stream)) {
                    if (partial.isNotEmpty()) {
                        emitted.append(partial)
                        lastPartial = emitted.toString()
                        onPartial(lastPartial)
                    }
                    rec.reset(stream)
                    break
                }
            }

            // tail flush: 0.5s 静音喂进去让 sherpa 出最后 partial（Round 3 V2: 0.4→0.5s）
            val tail = FloatArray((0.5 * SR).toInt())
            stream.acceptWaveform(tail, SR)
            decodeReady(rec, stream)
            val tailText = rec.getResult(stream).text
            if (tailText.isNotEmpty()) emitted.append(tailText)

            // UTF-8 校验：之前出现过 GBK 乱码，怀疑 ASR 端 raw bytes 没按 UTF-8 解。
            // 这一步冗余但能定位乱码源头（如果 LogCat 出现 ? 替换，说明 raw 真不是合法 UTF-8）。
            val raw = emitted.toString()
            val finalText = try {
                String(raw.toByteArray(Charsets.UTF_8), Charsets.UTF_8)
            } catch (t: Throwable) {
                Log.w(TAG, "UTF-8 validation failed, fallback to raw", t)
                raw
            }
            pendingFinal = finalText
            finalHolder.append(finalText)
            Log.i(
                TAG,
                "recordUntilSilence done: text='$finalText' hadPartial=${firstPartialMs > 0L} " +
                        "preRoll=${preRoll?.size ?: 0} elapsed=${System.currentTimeMillis() - startMs}ms"
            )
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.e(TAG, "recordUntilSilence loop error", t)
        } finally {
            runCatching { stream?.release() }
            runCatching { audioRec.stop() }
            runCatching { audioRec.release() }
            audioRecord = null
            recording.set(false)
        }

        finalHolder.toString()
    }

    /**
     * 连续录音 + ASR endpoint 驱动（照搬 MnnTaoAvatar RecognizeService.processSamples）。
     *
     * 用于电话模式：录音线程持续跑，sherpa 的 [OnlineRecognizer.isEndpoint] 检测到一句话
     * 结束就回调 [onEndpoint]，调用方拿文本送 LLM。TTS 播放时调用方负责先
     * [stopContinuousRecording] 互斥（避免回声），TTS 结束后再 startContinuousRecording 接着录。
     *
     * 与 [recordUntilSilence] 区别：那个是一次性录到静音返回（chat 模式长按 mic 用），
     * 这个是常驻循环，每次 endpoint 出一句就回调，循环不停 —— sherpa 持续 decode，
     * 起音头不会丢（之前 VAD 触发后才起录导致开头几个字识别不到）。
     */
    @SuppressLint("MissingPermission")
    fun startContinuousRecording(
        onEndpoint: (String) -> Unit,
        onPartial: (String) -> Unit = {},
        onSamples: ((ShortArray, Int) -> Unit)? = null,
        /** Runs on the capture thread before any MNN work. Keep it allocation-free and fast. */
        onRealtimeSamples: ((ShortArray, Int) -> Unit)? = null,
        preRoll: ShortArray? = null,
        audioSource: Int = MediaRecorder.AudioSource.MIC,
    ): Boolean {
        val rec = recognizer ?: return false
        if (!hasAudioPermission()) return false
        if (recording.getAndSet(true)) return false

        val minBuf = AudioRecord.getMinBufferSize(
            SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val audioRec = try {
            AudioRecord(
                audioSource, SR,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, SR / 5),
            ).also { it.startRecording() }
        } catch (t: Throwable) {
            Log.e(TAG, "startContinuousRecording: AudioRecord init failed", t)
            recording.set(false)
            return false
        }
        synchronized(this) {
            audioRecord = audioRec
        }
        // VOICE_COMMUNICATION asks the device HAL for its telephony path. These
        // effects are optional per device, so log their actual availability for
        // field diagnosis instead of assuming every phone has the same AEC.
        // Android's public API does not accept an AudioTrack PCM reference;
        // compatible HALs pair the communication capture/playback internally.
        val echoCanceler = AcousticEchoCanceler.create(audioRec.audioSessionId)?.also { it.enabled = true }
        val noiseSuppressor = NoiseSuppressor.create(audioRec.audioSessionId)?.also { it.enabled = true }
        val automaticGainControl = AutomaticGainControl.create(audioRec.audioSessionId)?.also { it.enabled = true }
        Log.i(
            TAG,
            "continuous capture source=${audioSourceName(audioSource)}($audioSource) effects " +
                "aec=${echoCanceler?.enabled == true} ns=${noiseSuppressor?.enabled == true} " +
                "agc=${automaticGainControl?.enabled == true}",
        )

        // Producer/consumer split is intentional. TTS can hold the MNN inference lock for a
        // whole clause; AudioRecord must still drain the hardware buffer and preserve the user's
        // first word. 20ms frames, 12s queue = enough headroom for a cancelled native TTS call
        // to release MNN and for ASR to catch up without throwing away the beginning of speech.
        val pcmQueue = LinkedBlockingDeque<ShortArray>(MAX_CONTINUOUS_PCM_FRAMES)
        var droppedFrames = 0
        continuousDecodeThread = Thread {
            val stream = rec.createStream("")
            try {
                if (preRoll != null && preRoll.isNotEmpty()) {
                    processContinuousPcm(rec, stream, preRoll, onEndpoint, onPartial, onSamples)
                }
                while (recording.get() || pcmQueue.isNotEmpty()) {
                    val frame = pcmQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    processContinuousPcm(rec, stream, frame, onEndpoint, onPartial, onSamples)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "continuous ASR decode loop error", t)
            } finally {
                runCatching { stream.release() }
                Log.i(TAG, "continuous ASR decode loop exited")
            }
        }.also { it.name = "NekoAsrDecode"; it.start() }

        recordThread = Thread {
            // 20ms lets a non-MNN fast VAD interrupt within 100ms without waiting for ASR/TTS.
            val buffer = ShortArray(SR / 50)
            Log.i(TAG, "continuous capture started frameMs=20 queueFrames=$MAX_CONTINUOUS_PCM_FRAMES")
            try {
                while (recording.get()) {
                    val n = audioRec.read(buffer, 0, buffer.size)
                    if (n <= 0) continue
                    val frame = buffer.copyOf(n)
                    // This callback must never call MNN. It drives phone-mode fast barge-in.
                    onRealtimeSamples?.invoke(frame, n)
                    if (!pcmQueue.offerLast(frame)) {
                        pcmQueue.pollFirst()
                        pcmQueue.offerLast(frame)
                        droppedFrames++
                        // Do not flood logcat at 50 lines/s; one warning per lost second still
                        // makes field diagnosis possible.
                        if (droppedFrames == 1 || droppedFrames % 50 == 0) {
                            Log.w(TAG, "continuous PCM queue overflow; dropped=${droppedFrames} frames (20ms each)")
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "continuous capture loop error", t)
            } finally {
                runCatching { echoCanceler?.release() }
                runCatching { noiseSuppressor?.release() }
                runCatching { automaticGainControl?.release() }
                runCatching { audioRec.stop() }
                runCatching { audioRec.release() }
                synchronized(this) {
                    if (audioRecord === audioRec) audioRecord = null
                }
                Log.i(TAG, "continuous capture loop exited")
            }
        }.also { it.name = "NekoAudioCapture"; it.start() }
        Log.i(TAG, "continuous ASR started capture=20ms decode=queued")
        return true
    }

    /** All sherpa/MNN work is confined to the decode consumer, never the AudioRecord thread. */
    private fun processContinuousPcm(
        rec: OnlineRecognizer,
        stream: OnlineStream,
        pcm: ShortArray,
        onEndpoint: (String) -> Unit,
        onPartial: (String) -> Unit,
        onSamples: ((ShortArray, Int) -> Unit)?,
    ) {
        onSamples?.invoke(pcm, pcm.size)
        val samples = FloatArray(pcm.size) { i -> pcm[i] / 32768.0f }
        stream.acceptWaveform(samples, SR)
        decodeReady(rec, stream)
        val isEndpoint = rec.isEndpoint(stream)
        val partial = rec.getResult(stream).text
        if (partial.isNotEmpty()) onPartial(partial)
        if (isEndpoint) {
            rec.reset(stream)
            if (partial.isNotEmpty()) {
                Log.i(TAG, "continuous ASR endpoint: '$partial'")
                onEndpoint(partial)
            }
        }
    }

    /**
     * 停连续录音（电话模式 TTS 播放前调，互斥回声）。可重入。
     * 设 [recording]=false 后 audioRec.read 立刻返回，循环退出。
     *
     * 关键：capture 本地引用再 stop/join/release。如果在这期间另一个线程调了
     * startContinuousRecording 起了新录音，audioRecord/recordThread 字段会被
     * 指向新对象 —— 用本地引用只释放旧的，且只在字段没被换掉时才清 null，
     * 否则会把新录音的 audioRecord release 掉 / recordThread 清 null，
     * 新 ASR 线程读已释放的 AudioRecord → MNN session 崩溃。
     */
    fun stopContinuousRecording() {
        if (!recording.getAndSet(false)) return
        val oldAudio = audioRecord
        val oldCaptureThread = recordThread
        val oldDecodeThread = continuousDecodeThread
        runCatching { oldAudio?.stop() }
        oldCaptureThread?.join(1500)
        oldDecodeThread?.join(1500)
        if (oldCaptureThread?.isAlive == true || oldDecodeThread?.isAlive == true) {
            Log.w(TAG, "stopContinuousRecording: worker still alive, interrupting")
            oldCaptureThread?.interrupt()
            oldDecodeThread?.interrupt()
        }
        runCatching { oldAudio?.release() }
        synchronized(this) {
            if (audioRecord === oldAudio) audioRecord = null
            if (recordThread === oldCaptureThread) recordThread = null
            if (continuousDecodeThread === oldDecodeThread) continuousDecodeThread = null
        }
        Log.i(TAG, "continuous ASR stopped")
    }

    /** 外部中断正在跑的 recordUntilSilence / startRecording。 */
    fun cancelRecording() {
        recording.set(false)
    }

    /** Serialize every sherpa/MNN decode against LLM/TTS inference. */
    private fun decodeReady(rec: OnlineRecognizer, stream: OnlineStream) {
        while (rec.isReady(stream)) {
            MnnGlobalLock.lock.lock()
            try {
                rec.decode(stream)
            } finally {
                MnnGlobalLock.lock.unlock()
            }
        }
    }

    private fun audioSourceName(source: Int): String = when (source) {
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "voice_communication"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "voice_recognition"
        MediaRecorder.AudioSource.MIC -> "mic"
        else -> "other"
    }

    private fun buildConfig(modelDir: String) = OnlineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = SR, featureDim = 80),
        modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = modelFile(modelDir, "encoder.fp16.mnn", "encoder-epoch-99-avg-1.int8.mnn"),
                decoder = modelFile(modelDir, "decoder.fp16.mnn", "decoder-epoch-99-avg-1.int8.mnn"),
                joiner = modelFile(modelDir, "joiner.fp16.mnn", "joiner-epoch-99-avg-1.int8.mnn"),
            ),
            tokens = "$modelDir/tokens.txt",
            // v0.6.7 P0: 不能写死 modelType。ASR 1.2 (medium-fp16) 是 Zipformer2，
            // metadata 只有 query_head_dims/value_head_dims，没有 attention_dims；
            // 写死 "zipformer" 会让 sherpa 直接 dispatch 到旧 Zipformer 类，缺 key 时
            // SHERPA_ONNX_EXIT(-1) 整个进程退出，Kotlin runCatching 拦不住（启动即闪退）。
            // 留空让 OnlineTransducerModel::Create 读 encoder metadata 的 model_type 自动识别。
            numThreads = 1, // MNN Zipformer cache 多线程会 conceal/错乱，与 neko-asr 验证一致，勿改 4
            modelType = "",
            debug = false,
        ),
        endpointConfig = EndpointConfig(
            // 电话模式：VAD 已经负责起音检测，ASR 只管收尾。
            // 调宽松避免用户说话中停顿就被截断（之前 0.8s trailing 导致"测试电话测试电话"被急停）。
            // rule1: 1.8s 纯静音触发（用户说完一段后停很久）
            // rule2: 1.5s trailing silence（说完最后一句后的尾巴）
            // rule3: 20s max utterance 兜底
            rule1 = EndpointRule(false, 1.8f, 0.0f),
            rule2 = EndpointRule(true, 1.5f, 0.0f),
            rule3 = EndpointRule(false, 0.0f, 20.0f),
        ),
        enableEndpoint = true,
        decodingMethod = "greedy_search",
        maxActivePaths = 4,
    )

    private fun modelFile(dir: String, modernName: String, legacyName: String): String {
        val modern = File(dir, modernName)
        return if (modern.isFile) modern.absolutePath else File(dir, legacyName).absolutePath
    }

    companion object {
        private const val TAG = "MoeAvatar.Asr"
        private const val SR = 16000
        /** 20ms capture frames; retain 12 seconds while cancelled MNN work releases its lock. */
        private const val MAX_CONTINUOUS_PCM_FRAMES = 600

        // recordUntilSilence 用的阈值（Round 3 V2：Hysteresis 双阈值 + 30ms 帧）
        // - DEFAULT_START_THRESHOLD：voiceGate 没传阈值时兜底
        // - STOP_THRESHOLD_RATIO：Hysteresis 比例，stop = start * 0.5
        // - PRE_SPEECH 3 帧（90ms）：用户没起音 90ms 放弃（比之前 0.25s 略短）
        // - POST_SPEECH 12 帧（360ms）：Hysteresis 静默持续 360ms 才停（防抖）
        // 起音由 VadGate 检测,本函数只管收尾,不再用 RMS 启发式
        // - PARTIAL_STALE_MS: partial 稳定超此时长认作用户说完了 (兜底 1)
        // - recordUntilSilence 内 1500ms 兜底 2: 还没出 partial 说明用户没说话
        // - rec.isEndpoint() 是主信号 (sherpa 自带端点检测)
        // - maxMs 是兜底 3: 防止用户长时间不说话占着麦克风
        // 电话模式：VAD 起音后 ASR 接管，PARTIAL_STALE_MS 是"partial 没变化多久认作说完"。
        // 之前 800ms 太短，用户说话中稍有停顿（思考、换气）就被截断。
        // 调到 1500ms 给用户足够停顿空间，配合上面的 endpoint rule 1.5s trailing。
        private const val PARTIAL_STALE_MS = 1500L
    }
}
