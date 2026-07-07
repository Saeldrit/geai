package com.github.saeldrit.geai.agent

import com.github.saeldrit.geai.context.NoteEntry
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
    var createdAtEpochMs: Long = System.currentTimeMillis(),
) {
    /**
     * The conversation transcript. A background save (and the UI) can iterate this while the loop
     * appends to it, so it is copy-on-write: iteration sees a stable snapshot and never throws
     * ConcurrentModificationException.
     */
    val messages: MutableList<ChatMessage> = java.util.concurrent.CopyOnWriteArrayList()

    var totalUsage: TokenUsage = TokenUsage.ZERO

    /**
     * The agent's running notes (findings, decisions, next steps) — its working memory that lives
     * OUTSIDE the transcript: always re-injected compactly and never compacted away, so a long
     * multi-file task keeps what it found while the raw file contents that produced it can be dropped.
     * Persists across turns within a live session so a "continue" accumulates rather than re-discovers.
     */
    val scratchpad: MutableList<NoteEntry> = java.util.concurrent.CopyOnWriteArrayList()

    val isEmpty: Boolean get() = messages.isEmpty()

    /**
     * Current active task description — injected as the LAST user message in the outgoing context
     * so the model always sees "this is what I should be working on right now" regardless of how
     * much the middle of the transcript was compressed. Survives compaction. Set on every new
     * user message in [AgentLoop.run].
     */
    var activeTask: String = ""
}
