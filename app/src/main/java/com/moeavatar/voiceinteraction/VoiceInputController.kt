package com.moeavatar.voiceinteraction

import com.moeavatar.chat.ChatAsrController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns one press-to-talk session and rejects callbacks from obsolete sessions. */
class VoiceInputController(
    private val asr: ChatAsrController,
    private val scope: CoroutineScope,
    private val coordinator: ConversationCoordinator,
    private val beforeListening: suspend () -> Unit,
    private val onFinalText: (String) -> Unit,
    private val onEmpty: () -> Unit,
    private val onStartFailed: () -> Unit,
) {
    private var held = false
    private var recording = false
    private var cancelArmed = false
    private var sessionId = 0L
    private var prepareJob: Job? = null
    private var finalizeJob: Job? = null

    fun press(): Boolean {
        if (held || prepareJob?.isActive == true || finalizeJob?.isActive == true) return false
        held = true
        recording = false
        cancelArmed = false
        sessionId = coordinator.beginVoiceSession()
        val id = sessionId
        prepareJob = scope.launch {
            beforeListening()
            prepareJob = null
            if (!held || !coordinator.isCurrentVoiceSession(id)) return@launch
            val started = runCatching {
                asr.startRecording(
                    onPartial = {},
                    onAmplitude = { level -> coordinator.updateLevel(id, level) },
                )
            }.getOrDefault(false)
            if (!started) {
                held = false
                coordinator.failVoiceSession(id, "语音输入启动失败")
                onStartFailed()
                coordinator.switchMode(InputMode.VOICE)
                return@launch
            }
            recording = true
            coordinator.markListening(id)
        }
        return true
    }

    fun updateCancelArmed(armed: Boolean) {
        if (!held || cancelArmed == armed) return
        cancelArmed = armed
        coordinator.updateCancelArmed(sessionId, armed)
    }

    fun release(forceCancel: Boolean = false) {
        if (!held) return
        held = false
        val cancelled = forceCancel || cancelArmed
        val id = sessionId
        if (!recording) {
            // Let beforeListening finish its cleanup and restart SpeechQueue safely.
            coordinator.finishVoiceSession(id)
            return
        }
        recording = false
        prepareJob = null
        coordinator.markFinalizing(id)
        finalizeJob = scope.launch {
            val finalText = withContext(Dispatchers.IO) {
                asr.stopRecordingAndFinalize()
            }.trim()
            if (!coordinator.isCurrentVoiceSession(id)) return@launch
            if (cancelled) {
                coordinator.finishVoiceSession(id)
                return@launch
            }
            if (finalText.isEmpty()) {
                coordinator.finishVoiceSession(id)
                onEmpty()
            } else {
                coordinator.finishVoiceSession(id)
                onFinalText(finalText)
            }
        }
    }

    fun cancel() {
        release(forceCancel = true)
    }
}
