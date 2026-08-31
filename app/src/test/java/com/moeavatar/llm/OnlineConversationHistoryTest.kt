package com.moeavatar.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class OnlineConversationHistoryTest {
    @Test
    fun requestContainsCompletedRoundsAndCurrentUser() {
        val history = OnlineConversationHistory(maxRounds = 15)
        history.commit("你好", "你好呀，主人喵~")
        history.commit("还记得我吗", "当然记得呀")

        val request = history.buildRequest("我刚才说了什么")

        assertEquals(5, request.size)
        assertEquals(ChatTurn(ChatTurn.Role.USER, "你好"), request[0])
        assertEquals(ChatTurn(ChatTurn.Role.ASSISTANT, "你好呀，主人喵~"), request[1])
        assertEquals(ChatTurn(ChatTurn.Role.USER, "我刚才说了什么"), request.last())
    }

    @Test
    fun oldestRoundIsEvictedAtLimit() {
        val history = OnlineConversationHistory(maxRounds = 2)
        history.commit("u1", "a1")
        history.commit("u2", "a2")
        history.commit("u3", "a3")

        val request = history.buildRequest("u4")

        assertEquals(listOf("u2", "a2", "u3", "a3", "u4"), request.map { it.content })
        assertEquals(2, history.roundCount())
    }

    @Test
    fun blankOrInterruptedAssistantIsNotCommitted() {
        val history = OnlineConversationHistory()

        history.commit("用户消息", "")

        assertEquals(0, history.roundCount())
        assertEquals(1, history.buildRequest("下一条").size)
    }

    @Test
    fun clearStartsANewOnlineSession() {
        val history = OnlineConversationHistory()
        history.commit("u1", "a1")

        history.clear()

        assertEquals(0, history.roundCount())
        assertEquals(listOf("new"), history.buildRequest("new").map { it.content })
    }
}
