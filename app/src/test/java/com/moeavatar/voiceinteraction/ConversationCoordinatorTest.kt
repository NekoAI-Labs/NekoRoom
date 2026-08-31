package com.moeavatar.voiceinteraction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationCoordinatorTest {
    @Test
    fun voiceSessionMovesThroughListeningAndFinalizing() {
        val coordinator = ConversationCoordinator()
        val session = coordinator.beginVoiceSession()

        assertTrue(coordinator.markListening(session))
        coordinator.updateLevel(session, 0.75f)
        coordinator.updateCancelArmed(session, true)
        assertEquals(
            VoiceInteractionState.Listening(session, 0.75f, true),
            coordinator.state.value,
        )
        assertTrue(coordinator.markFinalizing(session))
        assertTrue(coordinator.finishVoiceSession(session))
        assertEquals(VoiceInteractionState.Idle(InputMode.VOICE), coordinator.state.value)
    }

    @Test
    fun staleSessionCannotOverwriteNewSession() {
        val coordinator = ConversationCoordinator()
        val old = coordinator.beginVoiceSession()
        val current = coordinator.beginVoiceSession()

        assertFalse(coordinator.markListening(old))
        assertTrue(coordinator.markListening(current))
        coordinator.updateLevel(old, 1f)
        assertEquals(VoiceInteractionState.Listening(current), coordinator.state.value)
    }

    @Test
    fun interruptInvalidatesActiveVoiceSession() {
        val coordinator = ConversationCoordinator()
        val session = coordinator.beginVoiceSession()
        coordinator.markListening(session)

        coordinator.interrupt(InterruptReason.USER_STOP)

        assertFalse(coordinator.finishVoiceSession(session))
        assertEquals(
            VoiceInteractionState.Interrupted(InputMode.VOICE, InterruptReason.USER_STOP),
            coordinator.state.value,
        )
    }

    @Test
    fun textModeIsPreservedAcrossResponse() {
        val coordinator = ConversationCoordinator()
        coordinator.switchMode(InputMode.TEXT)
        coordinator.markThinking(InputSource.TEXT)
        coordinator.markSpeaking()
        coordinator.markResponseComplete()

        assertEquals(VoiceInteractionState.Idle(InputMode.TEXT), coordinator.state.value)
    }

    @Test
    fun voiceSessionFinishingAfterTextSwitchKeepsTextMode() {
        val coordinator = ConversationCoordinator()
        val session = coordinator.beginVoiceSession()
        coordinator.markListening(session)
        // 迟到的语音收尾回调撞上用户已切到文字模式：不应把模式拉回 VOICE
        // （否则正在输入的文字框会被 renderVoiceInteraction 立刻藏掉）。
        coordinator.switchMode(InputMode.TEXT)
        assertTrue(coordinator.finishVoiceSession(session))
        assertEquals(VoiceInteractionState.Idle(InputMode.TEXT), coordinator.state.value)
    }
}
