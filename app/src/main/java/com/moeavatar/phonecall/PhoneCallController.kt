package com.moeavatar.phonecall

import com.moeavatar.chat.ChatAsrController
import com.moeavatar.vad.FireRedVadBridge
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Owns the call lifecycle.  All callbacks carry a monotonically increasing
 * session id so an old recorder/LLM completion cannot resurrect a hung-up call.
 * The recorder feeds FireRed every 300 ms. A completed ASR endpoint is only
 * submitted after FireRed observed speech in that turn, so room noise cannot
 * create an LLM turn merely because the recognizer endpointed. Capture stays
 * active while TTS plays: genuine barge-in remains possible after the platform
 * voice-communication audio path has applied its echo processing.
 */
class PhoneCallController(
    private val asr: ChatAsrController,
    private val scope: CoroutineScope,
    private val onUtterance: (String) -> Unit,
    private val onBargeIn: () -> Unit,
    /** Source of truth outside this controller: LLM may already be streaming/TTS playing. */
    private val isAiOutputActive: () -> Boolean,
    private val vad: FireRedVadBridge? = null,
) {
    sealed class State {
        data object Off : State()
        data object Listening : State()
        data class Hearing(val level: Float = 0f) : State()
        data object Understanding : State()
        data object Speaking : State()
        /** TTS 自然结束后的短暂尾音保护；录音不停，但不接受 ASR endpoint。 */
        data object Settling : State()
        data object Muted : State()
        data class Error(val message: String) : State()
    }

    private val mutableState = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = mutableState.asStateFlow()
    private var session = 0L
    private var silenceJob: Job? = null
    private var tailGuardJob: Job? = null
    private val vadWindow = ShortArray(4800)
    private var vadWindowSize = 0
    private var speechDetectedSinceEndpoint = false
    @Volatile private var bargeInTriggered = false
    private var aiSpeechStartedMs = 0L
    private var residualRmsBaseline = 0f
    private var residualProbabilityBaseline = 0f
    private var bargeCandidateWindows = 0
    /** Non-MNN capture-thread gate: it remains live while TTS owns MNN inference. */
    private val fastGateLock = Any()
    private var fastRmsBaseline = 0f
    private var fastConsecutiveFrames = 0
    // ASR 可能在 VAD 完成插话确认前先 endpoint。旧逻辑直接丢弃它，导致用户要再说一句。
    private var pendingEndpointDuringAi: String? = null

    fun enter(): Boolean {
        if (mutableState.value !is State.Off) return true
        return startListening()
    }

    fun setMuted(muted: Boolean) {
        if (muted) {
            session++
            silenceJob?.cancel()
            tailGuardJob?.cancel()
            asr.stopContinuousRecording()
            mutableState.value = State.Muted
        } else if (mutableState.value is State.Muted) {
            // Reopening the mic must restore the call state as well as AudioRecord.  AI output
            // may have continued while muted, so no new playback callback will arrive to turn
            // Listening back into Speaking; without this reconciliation the interrupt control
            // stays hidden even though the character is still talking.
            if (startListening() && isAiOutputActive()) {
                onAiStarted()
                Log.i(TAG, "microphone reopened while AI output active; restored speaking state")
            }
        }
    }

    fun onAiStarted() {
        if (mutableState.value is State.Off || mutableState.value is State.Muted) return
        silenceJob?.cancel()
        tailGuardJob?.cancel()
        // Do not carry a pre-TTS VAD window into the stricter barge-in gate.
        // Capture itself remains continuously open for full-duplex interaction.
        vadWindowSize = 0
        speechDetectedSinceEndpoint = false
        pendingEndpointDuringAi = null
        resetBargeEvidence()
        synchronized(fastGateLock) {
            fastRmsBaseline = 0f
            fastConsecutiveFrames = 0
        }
        aiSpeechStartedMs = SystemClock.elapsedRealtime()
        mutableState.value = State.Speaking
        Log.i(TAG, "AI playback started; full-duplex VAD gate armed")
    }

    fun onAiFinished() {
        if (mutableState.value is State.Off || mutableState.value is State.Muted) return
        if (mutableState.value !is State.Speaking) return
        // AudioTrack 已耗尽不等于空间中的扬声器回声已经消失。保持采集与 ASR，
        // 但短暂拒绝 endpoint，避免阈值从 Speaking 的动态门控瞬间降回普通监听。
        mutableState.value = State.Settling
        speechDetectedSinceEndpoint = false
        vadWindowSize = 0
        val id = session
        tailGuardJob?.cancel()
        tailGuardJob = scope.launch {
            delay(TTS_TAIL_GUARD_MS)
            if (id == session && mutableState.value is State.Settling) {
                mutableState.value = State.Listening
                Log.d(TAG, "tail guard complete; normal listening resumed")
            }
        }
        Log.d(TAG, "AI playback exhausted; tail guard=${TTS_TAIL_GUARD_MS}ms")
    }

    fun interrupt() {
        if (mutableState.value is State.Speaking) {
            // 连续 ASR 已在跑，不能 stop/start；否则按下打断前说出的起音会丢。
            bargeInTriggered = true
            speechDetectedSinceEndpoint = true
            mutableState.value = State.Hearing()
            Log.i(TAG, "manual barge-in accepted; retaining active ASR stream")
            onBargeIn()
            deliverPendingEndpoint()
            return
        }
        if (mutableState.value is State.Settling) {
            tailGuardJob?.cancel()
            mutableState.value = State.Hearing()
            Log.i(TAG, "manual interrupt during tail guard; retaining active ASR stream")
            return
        }
        if (mutableState.value is State.Listening || mutableState.value is State.Hearing) return
        startListening()
    }

    fun hangup() {
        session++
        silenceJob?.cancel()
        tailGuardJob?.cancel()
        asr.stopContinuousRecording()
        mutableState.value = State.Off
    }

    private fun startListening(): Boolean {
        if (!asr.isReady || !asr.hasAudioPermission()) {
            mutableState.value = State.Error("耳朵还没准备好")
            return false
        }
        val id = ++session
        silenceJob?.cancel()
        tailGuardJob?.cancel()
        vadWindowSize = 0
        speechDetectedSinceEndpoint = false
        pendingEndpointDuringAi = null
        resetBargeEvidence()
        asr.stopContinuousRecording()
        val started = asr.startContinuousRecording(
            onEndpoint = { text ->
                if (id != session || mutableState.value is State.Off || mutableState.value is State.Muted) return@startContinuousRecording
                if (mutableState.value is State.Speaking) {
                    // 先等 VAD 判断是否为真人插话；保留 endpoint，避免 endpoint 比
                    // 300ms VAD 窗口更早时把用户的第一句扔掉。
                    pendingEndpointDuringAi = text.trim().takeIf { it.isNotEmpty() }
                    Log.d(TAG, "ASR endpoint deferred reason=ai_speaking textLen=${text.length}")
                    return@startContinuousRecording
                }
                if (mutableState.value is State.Settling) {
                    Log.d(TAG, "ASR endpoint rejected reason=tts_tail_guard textLen=${text.length}")
                    return@startContinuousRecording
                }
                if (text.isBlank()) return@startContinuousRecording
                val hasSpeech = vad == null || speechDetectedSinceEndpoint
                if (!hasSpeech) {
                    speechDetectedSinceEndpoint = false
                    mutableState.value = State.Listening
                    return@startContinuousRecording
                }
                submitEndpoint(text)
            },
            onPartial = { partial ->
                if (id != session || partial.isBlank()) return@startContinuousRecording
                if (vad == null) mutableState.value = State.Hearing()
            },
            onSamples = { samples, size ->
                if (id != session || vad == null) return@startContinuousRecording
                var offset = 0
                while (offset < size) {
                    val copy = minOf(vadWindow.size - vadWindowSize, size - offset)
                    samples.copyInto(vadWindow, vadWindowSize, offset, offset + copy)
                    vadWindowSize += copy; offset += copy
                    if (vadWindowSize == vadWindow.size) {
                        val probs = vad.inferPcm(vadWindow)
                        val state = mutableState.value
                        if (probs == null || probs.isEmpty()) {
                            Log.w(TAG, "VAD decision state=${state.label()} decision=reject reason=no_probability")
                        } else {
                            val metrics = analyzeVad(probs, vadWindow)
                            when (state) {
                                is State.Speaking -> evaluateSpeakingVad(id, metrics)
                                is State.Listening, is State.Hearing -> evaluateListeningVad(id, metrics)
                                is State.Settling -> logVadDecision(
                                    state, metrics, LISTENING_FRAME_THRESHOLD, "reject", "tts_tail_guard"
                                )
                                else -> logVadDecision(state, metrics, 0f, "ignore", "state_not_listening")
                            }
                        }
                        vadWindowSize = 0
                    }
                }
            },
            onRealtimeSamples = { samples, size ->
                evaluateRealtimeBargeIn(id, samples, size)
            },
            audioSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        )
        mutableState.value = if (started) State.Listening else State.Error("麦克风开启失败")
        return started
    }

    private companion object {
        private const val TAG = "PhoneCall"
        private const val VAD_FRAME_MS = 10
        private const val LISTENING_FRAME_THRESHOLD = 0.28f
        private const val LISTENING_MIN_CONSECUTIVE_FRAMES = 3
        private const val AI_MIN_FRAME_THRESHOLD = 0.74f
        private const val AI_MAX_FRAME_THRESHOLD = 0.95f
        private const val AI_RESIDUAL_MARGIN = 0.15f
        private const val AI_MIN_SPEECH_RATIO = 0.30f
        private const val AI_MIN_CONSECUTIVE_FRAMES = 8
        private const val AI_REQUIRED_CANDIDATE_WINDOWS = 1
        private const val AI_WARMUP_MS = 450L
        private const val AI_RMS_GAIN = 1.18f
        private const val BASELINE_ALPHA = 0.15f
        // Capture producer is 20ms/frame. Field logs on the vivo device showed residual
        // speaker echo at RMS 0.047~0.060 while genuine nearby speech starts around 0.08+
        // (and normally 0.12+).  Keep this stricter policy only during AI playback: normal
        // listening still uses FireRed's lower threshold.
        private const val FAST_WARMUP_MS = 350L
        private const val FAST_REQUIRED_FRAMES = 6
        private const val FAST_ABSOLUTE_RMS = 0.070f
        private const val FAST_RMS_GAIN = 2.2f
        private const val FAST_BASELINE_ALPHA = 0.08f
        private const val TTS_TAIL_GUARD_MS = 350L
    }

    private data class VadMetrics(
        val probabilities: FloatArray,
        val peak: Float,
        val mean: Float,
        val rms: Float,
        val frameCount: Int,
        val highFrames: Int,
        val longestHighRun: Int,
    ) {
        val highRatio: Float get() = highFrames.toFloat() / frameCount.coerceAtLeast(1)
    }

    /** FireRed 是逐约 10ms 帧输出；绝不能用整段 max 作为“连续语音”。 */
    private fun analyzeVad(probs: FloatArray, pcm: ShortArray): VadMetrics {
        var peak = 0f
        var sum = 0f
        var highFrames = 0
        var run = 0
        var longestRun = 0
        for (prob in probs) {
            peak = max(peak, prob)
            sum += prob
            if (prob >= AI_MIN_FRAME_THRESHOLD) {
                highFrames++
                run++
                longestRun = max(longestRun, run)
            } else {
                run = 0
            }
        }
        var squared = 0.0
        for (sample in pcm) {
            val normalized = sample / 32768.0
            squared += normalized * normalized
        }
        return VadMetrics(
            probabilities = probs,
            peak = peak,
            mean = sum / probs.size,
            rms = sqrt(squared / pcm.size).toFloat(),
            frameCount = probs.size,
            highFrames = highFrames,
            longestHighRun = longestRun,
        )
    }

    private fun evaluateListeningVad(id: Long, metrics: VadMetrics) {
        // 普通聆听仍然灵敏，但也避免单个 10ms 毛刺把 endpoint 放行。
        val normal = metrics.withThreshold(LISTENING_FRAME_THRESHOLD)
        val speech = normal.peak >= LISTENING_FRAME_THRESHOLD &&
            normal.longestHighRun >= LISTENING_MIN_CONSECUTIVE_FRAMES
        if (!speech) {
            logVadDecision(mutableState.value, normal, LISTENING_FRAME_THRESHOLD, "reject", "insufficient_normal_speech")
            return
        }
        speechDetectedSinceEndpoint = true
        val score = normal.peak
        mutableState.value = State.Hearing(score)
        logVadDecision(mutableState.value, normal, LISTENING_FRAME_THRESHOLD, "accept", "normal_speech")
        silenceJob?.cancel()
        silenceJob = scope.launch {
            delay(700)
            if (id == session && mutableState.value is State.Hearing) {
                mutableState.value = State.Listening
            }
        }
    }

    private fun evaluateSpeakingVad(id: Long, metrics: VadMetrics) {
        val elapsedMs = SystemClock.elapsedRealtime() - aiSpeechStartedMs
        val adaptiveThreshold = max(
            AI_MIN_FRAME_THRESHOLD,
            (residualProbabilityBaseline + AI_RESIDUAL_MARGIN).coerceAtMost(AI_MAX_FRAME_THRESHOLD),
        )
        val high = metrics.withThreshold(adaptiveThreshold)
        val rmsBaseline = residualRmsBaseline
        val rmsGain = if (rmsBaseline > 0.0001f) metrics.rms / rmsBaseline else 1f
        val temporalSpeech = high.highRatio >= AI_MIN_SPEECH_RATIO &&
            high.longestHighRun >= AI_MIN_CONSECUTIVE_FRAMES
        val warmup = elapsedMs < AI_WARMUP_MS
        val rmsChanged = rmsBaseline <= 0.0001f || rmsGain >= AI_RMS_GAIN
        val candidate = !warmup && temporalSpeech && rmsChanged

        // 只把“未被判为用户插话”的窗口吸收到播放残留基线，避免用户声音抬高基线。
        if (!candidate) updateResidualBaseline(metrics)

        when {
            warmup -> {
                bargeCandidateWindows = 0
                logVadDecision(State.Speaking, high, adaptiveThreshold, "reject", "aec_warmup elapsed=${elapsedMs}ms rmsGain=${format(rmsGain)}")
            }
            !temporalSpeech -> {
                bargeCandidateWindows = 0
                logVadDecision(State.Speaking, high, adaptiveThreshold, "reject", "insufficient_continuous_speech rmsGain=${format(rmsGain)}")
            }
            !rmsChanged -> {
                bargeCandidateWindows = 0
                logVadDecision(State.Speaking, high, adaptiveThreshold, "reject", "stable_residual_echo rmsGain=${format(rmsGain)}")
            }
            else -> {
                bargeCandidateWindows++
                if (bargeCandidateWindows < AI_REQUIRED_CANDIDATE_WINDOWS || bargeInTriggered) {
                    logVadDecision(State.Speaking, high, adaptiveThreshold, "pending", "barge_candidate=$bargeCandidateWindows rmsGain=${format(rmsGain)}")
                    return
                }
                bargeInTriggered = true
                speechDetectedSinceEndpoint = true
                // 不重启 AudioRecord/ASR：当前 stream 已经包含用户的起音，重启会把
                // 真实插话的前半句截掉。stopGeneration 只停 AI，不会停连续录音。
                mutableState.value = State.Hearing(high.peak)
                logVadDecision(State.Speaking, high, adaptiveThreshold, "accept", "barge_in rmsGain=${format(rmsGain)}")
                scope.launch {
                    if (id == session) {
                        onBargeIn()
                        deliverPendingEndpoint()
                    }
                }
            }
        }
    }

    /**
     * Fast interruption runs directly on the AudioRecord producer (20ms frames), not on
     * FireRed/MNN. FireRed remains a slower quality gate in the ASR consumer; it must never
     * delay the first-word stop decision behind a SuperTonic inference lock.
     */
    private fun evaluateRealtimeBargeIn(id: Long, samples: ShortArray, size: Int) {
        if (mutableState.value !is State.Speaking || bargeInTriggered || size <= 0) return
        var squared = 0.0
        for (i in 0 until size) {
            val x = samples[i] / 32768.0
            squared += x * x
        }
        val rms = sqrt(squared / size).toFloat()
        val elapsed = SystemClock.elapsedRealtime() - aiSpeechStartedMs
        var accept = false
        var threshold = FAST_ABSOLUTE_RMS
        synchronized(fastGateLock) {
            if (bargeInTriggered || mutableState.value !is State.Speaking) return
            // Learn the device's AEC residual during the first 120ms. Afterwards a real nearby
            // speaker must exceed both an absolute floor and the dynamic residual baseline.
            if (fastRmsBaseline == 0f) fastRmsBaseline = rms
            else fastRmsBaseline = fastRmsBaseline * (1f - FAST_BASELINE_ALPHA) + rms * FAST_BASELINE_ALPHA
            threshold = max(FAST_ABSOLUTE_RMS, fastRmsBaseline * FAST_RMS_GAIN)
            if (elapsed < FAST_WARMUP_MS) return
            if (rms >= threshold) fastConsecutiveFrames++ else fastConsecutiveFrames = 0
            if (fastConsecutiveFrames >= FAST_REQUIRED_FRAMES) {
                bargeInTriggered = true
                speechDetectedSinceEndpoint = true
                mutableState.value = State.Hearing(0f)
                accept = true
            }
        }
        if (accept) {
            Log.i(TAG, "fast barge-in accept elapsed=${elapsed}ms rms=${format(rms)} threshold=${format(threshold)}")
            scope.launch {
                if (id == session) {
                    onBargeIn()
                    deliverPendingEndpoint()
                }
            }
        }
    }

    private fun VadMetrics.withThreshold(threshold: Float): VadMetrics {
        var highFrames = 0
        var run = 0
        var longestRun = 0
        for (prob in probabilities) {
            if (prob >= threshold) {
                highFrames++
                run++
                longestRun = max(longestRun, run)
            } else {
                run = 0
            }
        }
        return copy(highFrames = highFrames, longestHighRun = longestRun)
    }

    private fun updateResidualBaseline(metrics: VadMetrics) {
        residualRmsBaseline = if (residualRmsBaseline == 0f) metrics.rms
        else residualRmsBaseline * (1f - BASELINE_ALPHA) + metrics.rms * BASELINE_ALPHA
        residualProbabilityBaseline = if (residualProbabilityBaseline == 0f) metrics.mean
        else residualProbabilityBaseline * (1f - BASELINE_ALPHA) + metrics.mean * BASELINE_ALPHA
    }

    private fun resetBargeEvidence() {
        bargeInTriggered = false
        bargeCandidateWindows = 0
        residualRmsBaseline = 0f
        residualProbabilityBaseline = 0f
        synchronized(fastGateLock) {
            fastRmsBaseline = 0f
            fastConsecutiveFrames = 0
        }
    }

    private fun deliverPendingEndpoint() {
        val text = pendingEndpointDuringAi ?: return
        pendingEndpointDuringAi = null
        if (text.isBlank()) return
        Log.i(TAG, "delivering deferred ASR endpoint after accepted barge-in textLen=${text.length}")
        submitEndpoint(text)
    }

    private fun submitEndpoint(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return
        speechDetectedSinceEndpoint = false
        mutableState.value = State.Understanding
        // AudioRecord invokes this on its worker thread; LlmChatActivity's submit
        // path updates views, so always cross back to lifecycleScope.
        scope.launch { onUtterance(clean) }
    }

    private fun logVadDecision(state: State, m: VadMetrics, threshold: Float, decision: String, reason: String) {
        Log.d(
            TAG,
            "VAD decision=$decision state=${state.label()} threshold=${format(threshold)} " +
                "peak=${format(m.peak)} mean=${format(m.mean)} high=${m.highFrames}/${m.frameCount} " +
                "run=${m.longestHighRun * VAD_FRAME_MS}ms ratio=${format(m.highRatio)} " +
                "rms=${format(m.rms)} reason=$reason",
        )
    }

    private fun State.label(): String = when (this) {
        is State.Off -> "off"
        is State.Listening -> "listening"
        is State.Hearing -> "hearing"
        is State.Understanding -> "understanding"
        is State.Speaking -> "speaking"
        is State.Settling -> "settling"
        is State.Muted -> "muted"
        is State.Error -> "error"
    }

    private fun format(value: Float): String = "%.3f".format(java.util.Locale.US, value)
}
