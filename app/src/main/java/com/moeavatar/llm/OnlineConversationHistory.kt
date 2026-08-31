package com.moeavatar.llm

/** In-memory completed-turn history for stateless online LLM backends. */
class OnlineConversationHistory(private val maxRounds: Int = 15) {
    private data class Round(val user: String, val assistant: String)

    private val rounds = ArrayDeque<Round>()

    init {
        require(maxRounds > 0) { "maxRounds must be positive" }
    }

    @Synchronized
    fun buildRequest(currentUser: String): List<ChatTurn> = buildList {
        for (round in rounds) {
            add(ChatTurn(ChatTurn.Role.USER, round.user))
            add(ChatTurn(ChatTurn.Role.ASSISTANT, round.assistant))
        }
        add(ChatTurn(ChatTurn.Role.USER, currentUser))
    }

    @Synchronized
    fun commit(user: String, assistant: String) {
        val cleanUser = user.trim()
        val cleanAssistant = assistant.trim()
        if (cleanUser.isEmpty() || cleanAssistant.isEmpty()) return
        rounds.addLast(Round(cleanUser, cleanAssistant))
        while (rounds.size > maxRounds) rounds.removeFirst()
    }

    @Synchronized
    fun clear() {
        rounds.clear()
    }

    @Synchronized
    fun roundCount(): Int = rounds.size
}
