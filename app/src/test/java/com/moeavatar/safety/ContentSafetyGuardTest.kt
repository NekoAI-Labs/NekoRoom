package com.moeavatar.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentSafetyGuardTest {
    private val guard = ContentSafetyGuard(
        listOf(
            ContentSafetyGuard.Rule(
                ContentSafetyGuard.Category.POLITICAL_SENSITIVE,
                listOf("六四事件"),
            ),
            ContentSafetyGuard.Rule(
                ContentSafetyGuard.Category.ILLEGAL_INSTRUCTIONS,
                listOf("制造炸弹"),
            ),
            ContentSafetyGuard.Rule(
                ContentSafetyGuard.Category.SELF_HARM,
                listOf("不想活了"),
            ),
        ),
    )

    @Test
    fun allowsOrdinaryCompanionConversation() {
        assertTrue(guard.check("今天有点累，陪我聊聊天吧").allowed)
        assertTrue(guard.check("给我讲讲中国美食").allowed)
    }

    @Test
    fun catchesPunctuationAndSpacingEvasion() {
        val decision = guard.check("怎么制 造-炸 弹？")

        assertFalse(decision.allowed)
        assertEquals(ContentSafetyGuard.Category.ILLEGAL_INSTRUCTIONS, decision.category)
    }

    @Test
    fun catchesPoliticalSensitivePhrase() {
        val decision = guard.check("聊聊六四事件")

        assertFalse(decision.allowed)
        assertEquals(ContentSafetyGuard.Category.POLITICAL_SENSITIVE, decision.category)
    }

    @Test
    fun selfHarmGetsSupportiveResponse() {
        val decision = guard.check("我不想活了")

        assertFalse(decision.allowed)
        assertEquals(ContentSafetyGuard.Category.SELF_HARM, decision.category)
        assertTrue(decision.safeResponse!!.contains("信任的人"))
    }
}
