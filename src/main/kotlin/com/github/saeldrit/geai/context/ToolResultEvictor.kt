package com.github.saeldrit.geai.context

import com.github.saeldrit.geai.llm.ChatMessage
import com.github.saeldrit.geai.llm.ContentBlock
import com.github.saeldrit.geai.llm.Role

/**
 * Mechanical, LLM-free eviction of STALE tool outputs — the dominant hidden cost of a long agent
 * turn. Every iteration re-sends the whole transcript, so a 40k-char file read from 20 turns ago
 * is paid for on every subsequent LLM call even though the model has long since extracted what it
 * needed. [ContextCompressor] only fires near the window limit (70% of a 128k window ≈ ~90k tokens)
 * and is lossy + costs a summarizer call; this evictor keeps the transcript lean the whole time.
 *
 * Policy: tool results older than the last [GeaiSettingsState.toolResultKeepTurns] assistant turns
 * are replaced by a short stub (first line(s) preserved + an explicit re-read hint). Knowledge is
 * NOT lost: durable findings live in notes / the SESSION MEMORY ledger, and the system prompt
 * teaches the narrow re-read recovery path. Eviction is applied in hysteresis batches — only when
 * the reclaimable mass crosses [MIN_TOTAL_GAIN_CHARS] — so the transcript prefix stays stable for
 * many turns at a time (friendlier to provider-side prefix caching than per-turn churn).
 */
object ToolResultEvictor {

    /** Results at or below this size are never stubbed — the stub would not pay for itself. */
    private const val MIN_EVICTABLE_CHARS = 600

    /** How much of the original head survives in the stub (cut at a line boundary). */
    private const val STUB_HEAD_CHARS = 200

    /** Hysteresis: do nothing until at least this many chars can be reclaimed in one batch.
     * Each batch rewrites the transcript prefix and therefore invalidates the provider's
     * prompt cache for the whole conversation — so batches must be rare and large. */
    private const val MIN_TOTAL_GAIN_CHARS = 16_000

    /** Activation floor: below this total transcript size eviction cannot pay for itself —
     * the re-read + prompt-cache-miss cost exceeds the token savings. Small/medium sessions
     * keep their tool outputs verbatim. */
    private const val MIN_TRANSCRIPT_CHARS = 60_000

    private const val STUB_MARKER = "[stale tool output evicted"

    data class Stats(val resultsEvicted: Int, val charsReclaimed: Int) {
        val isNoop: Boolean get() = resultsEvicted == 0
    }

    @Volatile
    var lastStats: Stats = Stats(0, 0)
        private set

    /**
     * Stub every eligible tool result that is older than the [keepRecentAssistantTurns] most
     * recent assistant turns. Mutates [messages] in place (block content only — tool_use/tool_result
     * pairing is never broken). Returns what was reclaimed; no-op unless the batch is worth it.
     */
    fun evict(messages: MutableList<ChatMessage>, keepRecentAssistantTurns: Int): Stats {
        if (keepRecentAssistantTurns <= 0) return Stats(0, 0)
        if (transcriptChars(messages) < MIN_TRANSCRIPT_CHARS) return Stats(0, 0)
        val cutoff = cutoffIndex(messages, keepRecentAssistantTurns)
        if (cutoff <= 0) return Stats(0, 0)

        var gain = 0
        var count = 0
        for (i in 0 until cutoff) {
            val msg = messages[i]
            if (msg.role != Role.TOOL) continue
            for (block in msg.content) {
                if (block is ContentBlock.ToolResult && eligible(block)) {
                    gain += (block.content.length - stubFor(block).length).coerceAtLeast(0)
                    count++
                }
            }
        }
        if (count == 0 || gain < MIN_TOTAL_GAIN_CHARS) return Stats(0, 0)

        for (i in 0 until cutoff) {
            val msg = messages[i]
            if (msg.role != Role.TOOL) continue
            var changed = false
            val newContent = msg.content.map { block ->
                if (block is ContentBlock.ToolResult && eligible(block)) {
                    changed = true
                    block.copy(content = stubFor(block))
                } else {
                    block
                }
            }
            if (changed) messages[i] = msg.copy(content = newContent)
        }
        val stats = Stats(count, gain)
        lastStats = stats
        return stats
    }

    /**
     * Index of the K-th most recent ASSISTANT message: that turn and everything after it (its tool
     * results, the follow-ups) is kept verbatim; only messages strictly before it are eligible.
     */
    private fun cutoffIndex(messages: List<ChatMessage>, keepRecentAssistantTurns: Int): Int {
        var assistantSeen = 0
        for (i in messages.indices.reversed()) {
            if (messages[i].role == Role.ASSISTANT) {
                assistantSeen++
                if (assistantSeen >= keepRecentAssistantTurns) return i
            }
        }
        return 0
    }

    /** Total char mass of the transcript (all block payloads) — cheap O(n) scan. */
    private fun transcriptChars(messages: List<ChatMessage>): Int =
        messages.sumOf { msg ->
            msg.content.sumOf { block ->
                when (block) {
                    is ContentBlock.Text -> block.text.length
                    is ContentBlock.ToolResult -> block.content.length
                    is ContentBlock.ToolUse -> block.name.length + block.inputJson.length
                    is ContentBlock.Image -> 0
                }
            }
        }

    /** Big enough to matter and not already a stub (stubs are always far below the size floor). */
    private fun eligible(block: ContentBlock.ToolResult): Boolean =
        block.content.length > MIN_EVICTABLE_CHARS && !block.content.contains(STUB_MARKER)

    /**
     * Keep the informative head — for read_file that is the "// path (lines a-b of N)" header, for
     * run_command the "$ cmd" + "exit code" lines — then an explicit marker with the recovery hint.
     */
    private fun stubFor(block: ContentBlock.ToolResult): String {
        val head = block.content.take(STUB_HEAD_CHARS).let { raw ->
            val lastNewline = raw.lastIndexOf('\n')
            if (lastNewline > 40) raw.take(lastNewline) else raw
        }.trimEnd()
        val kind = if (block.isError) "error output" else "output"
        return head + "\n…$STUB_MARKER: ${block.content.length} chars of stale $kind dropped to keep context lean. " +
            "Durable findings are in SESSION MEMORY / your notes; re-read the specific range or re-run the command if you truly need this data again]"
    }
}
