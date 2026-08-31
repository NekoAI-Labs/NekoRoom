package com.moeavatar.voiceinteraction

enum class InputMode { VOICE, TEXT }

enum class InputSource { VOICE, TEXT }

enum class InterruptReason { USER_STOP, VOICE_BARGE_IN, NEW_MESSAGE, LIFECYCLE }

sealed class VoiceInteractionState(open val mode: InputMode) {
    data class Idle(override val mode: InputMode) : VoiceInteractionState(mode)
    data class Preparing(val sessionId: Long) : VoiceInteractionState(InputMode.VOICE)
    data class Listening(
        val sessionId: Long,
        val level: Float = 0f,
        val cancelArmed: Boolean = false,
    ) : VoiceInteractionState(InputMode.VOICE)
    data class Finalizing(val sessionId: Long) : VoiceInteractionState(InputMode.VOICE)
    data class Thinking(override val mode: InputMode) : VoiceInteractionState(mode)
    data class Speaking(override val mode: InputMode) : VoiceInteractionState(mode)
    data class Interrupted(
        override val mode: InputMode,
        val reason: InterruptReason,
    ) : VoiceInteractionState(mode)
    data class Error(
        override val mode: InputMode,
        val message: String,
    ) : VoiceInteractionState(mode)
}
