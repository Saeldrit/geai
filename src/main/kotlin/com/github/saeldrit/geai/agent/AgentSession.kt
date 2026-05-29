package com.github.saeldrit.geai.agent

import com.github.saeldrit.geai.llm.ChatMessage
import com.github.saeldrit.geai.llm.TokenUsage
import java.util.UUID

/**
 * Mutable conversation state for one debugging session. Persisted/restored by the session store
 * (Phase F) so a disconnect or IDE restart can resume mid-investigation.
 */
class AgentSession(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "New session",
    val messages: MutableList<ChatMessage> = mutableListOf(),
    var createdAtEpochMs: Long = System.currentTimeMillis(),
) {
    var totalUsage: TokenUsage = TokenUsage.ZERO

    /** Claude Code's own session id, used with `--resume` to continue context across turns. */
    var claudeSessionId: String? = null

    val isEmpty: Boolean get() = messages.isEmpty()
}
