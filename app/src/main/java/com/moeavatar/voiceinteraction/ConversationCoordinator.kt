package com.moeavatar.voiceinteraction

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Single source of truth for the V0.6 input and response state. */
class ConversationCoordinator(initialMode: InputMode = InputMode.VOICE) {
    private val mutableState = MutableStateFlow<VoiceInteractionState>(
        VoiceInteractionState.Idle(initialMode)
    )
    val state: StateFlow<VoiceInteractionState> = mutableState.asStateFlow()

    private var currentMode = initialMode
    private var nextSessionId = 0L

    fun switchMode(mode: InputMode) {
        currentMode = mode
        if (mutableState.value is VoiceInteractionState.Idle ||
            mutableState.value is VoiceInteractionState.Error ||
            mutableState.value is VoiceInteractionState.Interrupted
        ) {
            mutableState.value = VoiceInteractionState.Idle(mode)
        }
    }

    fun beginVoiceSession(): Long {
        currentMode = InputMode.VOICE
        val id = ++nextSessionId
        mutableState.value = VoiceInteractionState.Preparing(id)
        return id
    }

    fun markListening(sessionId: Long): Boolean {
        if (!isCurrentVoiceSession(sessionId)) return false
        mutableState.value = VoiceInteractionState.Listening(sessionId)
        return true
    }

    fun updateLevel(sessionId: Long, level: Float) {
        val current = mutableState.value as? VoiceInteractionState.Listening ?: return
        if (current.sessionId != sessionId) return
        mutableState.value = current.copy(level = level.coerceIn(0f, 1f))
    }

    fun updateCancelArmed(sessionId: Long, armed: Boolean) {
        val current = mutableState.value as? VoiceInteractionState.Listening ?: return
        if (current.sessionId != sessionId || current.cancelArmed == armed) return
        mutableState.value = current.copy(cancelArmed = armed)
    }

    fun markFinalizing(sessionId: Long): Boolean {
        if (!isCurrentVoiceSession(sessionId)) return false
        mutableState.value = VoiceInteractionState.Finalizing(sessionId)
        return true
    }

    fun finishVoiceSession(sessionId: Long): Boolean {
        if (!isCurrentVoiceSession(sessionId)) return false
        // 必须用 currentMode 而不是写死 VOICE：语音会话的收尾回调（如 ASR tail）可能
        // 在用户已切到文字模式后才触发，写死 VOICE 会把正在输入的文字框立刻藏掉。
        mutableState.value = VoiceInteractionState.Idle(currentMode)
        return true
    }

    fun failVoiceSession(sessionId: Long, message: String): Boolean {
        if (!isCurrentVoiceSession(sessionId)) return false
        mutableState.value = VoiceInteractionState.Error(currentMode, message)
        return true
    }

    fun markThinking(source: InputSource) {
        currentMode = if (source == InputSource.VOICE) InputMode.VOICE else currentMode
        mutableState.value = VoiceInteractionState.Thinking(currentMode)
    }

    fun markThinkingCurrent() {
        mutableState.value = VoiceInteractionState.Thinking(currentMode)
    }

    fun markSpeaking() {
        mutableState.value = VoiceInteractionState.Speaking(currentMode)
    }

    fun markResponseComplete() {
        mutableState.value = VoiceInteractionState.Idle(currentMode)
    }

    fun interrupt(reason: InterruptReason) {
        nextSessionId++
        mutableState.value = VoiceInteractionState.Interrupted(currentMode, reason)
    }

    fun settleInterrupted() {
        if (mutableState.value is VoiceInteractionState.Interrupted) {
            mutableState.value = VoiceInteractionState.Idle(currentMode)
        }
    }

    fun isCurrentVoiceSession(sessionId: Long): Boolean {
        val stateId = when (val current = mutableState.value) {
            is VoiceInteractionState.Preparing -> current.sessionId
            is VoiceInteractionState.Listening -> current.sessionId
            is VoiceInteractionState.Finalizing -> current.sessionId
            else -> return false
        }
        return stateId == sessionId
    }
}
