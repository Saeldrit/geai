package com.github.saeldrit.geai.agent

import com.github.saeldrit.geai.context.NoteEntry
import com.github.saeldrit.geai.llm.ChatMessage
import com.github.saeldrit.geai.llm.TokenUsage
import java.util.UUID

class AgentSession(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "New session",
    var createdAtEpochMs: Long = System.currentTimeMillis(),
) {
    val messages: MutableList<ChatMessage> = java.util.concurrent.CopyOnWriteArrayList()

    var totalUsage: TokenUsage = TokenUsage.ZERO

    val scratchpad: MutableList<NoteEntry> = java.util.concurrent.CopyOnWriteArrayList()

    val isEmpty: Boolean get() = messages.isEmpty()

    var activeTask: String = ""
}
