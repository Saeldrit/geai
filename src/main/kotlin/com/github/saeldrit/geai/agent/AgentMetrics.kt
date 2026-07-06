package com.github.saeldrit.geai.agent

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Collects per-turn timing and size metrics for the agent loop.
 * Thread-safe: metrics are emitted from the agent worker thread but may be read from the EDT for display.
 */
class AgentMetrics {

    data class TurnMetrics(
        val turnIndex: Int,
        /** Wall-clock time of the LLM streaming call (ms). */
        val llmCallMs: Long,
        /** Wall-clock time of all tool execution combined (ms). */
        val toolExecutionMs: Long,
        /** Wall-clock time of context compression (ms). */
        val compressionMs: Long,
        /** Total turn wall-clock time (ms). */
        val turnTotalMs: Long,
        /** Message count BEFORE compression. */
        val messagesBefore: Int,
        /** Message count AFTER compression (same as before if no compression happened). */
        val messagesAfter: Int,
        /** Number of tool calls in this turn. */
        val toolCallCount: Int,
        /** Number of parallel vs sequential tool calls. */
        val parallelToolCalls: Int,
        val sequentialToolCalls: Int,
        /** Approximate context size in chars sent to LLM. */
        val contextChars: Int,
        /** Input tokens reported by the LLM for this turn. */
        val inputTokens: Int,
        /** Output tokens reported by the LLM for this turn. */
        val outputTokens: Int,
        /** Whether context compression was triggered. */
        val compressed: Boolean,
        /** Whether summarization (LLM-based) was used during compression. */
        val summarized: Boolean,
        val hitIterationLimit: Boolean = false,
    )

    private val _turns = CopyOnWriteArrayList<TurnMetrics>()
    val turns: List<TurnMetrics> get() = _turns.toList()

    fun record(turn: TurnMetrics) {
        _turns.add(turn)
    }

    fun summary(): String {
        if (_turns.isEmpty()) return "No turns recorded."
        val sb = StringBuilder()
        sb.appendLine("=== Agent Metrics (${turns.size} turns) ===")
        sb.appendLine()
        val totalLlm = turns.sumOf { it.llmCallMs }
        val totalTool = turns.sumOf { it.toolExecutionMs }
        val totalComp = turns.sumOf { it.compressionMs }
        val totalAll = turns.sumOf { it.turnTotalMs }
        sb.appendLine("Total time: ${totalAll}ms (${totalAll / 1000}s)")
        sb.appendLine("  LLM calls:     ${totalLlm}ms (${(totalLlm * 100 / totalAll.coerceAtLeast(1))}%)")
        sb.appendLine("  Tool exec:     ${totalTool}ms (${(totalTool * 100 / totalAll.coerceAtLeast(1))}%)")
        sb.appendLine("  Compression:   ${totalComp}ms (${(totalComp * 100 / totalAll.coerceAtLeast(1))}%)")
        sb.appendLine("  Overhead:      ${totalAll - totalLlm - totalTool - totalComp}ms")
        sb.appendLine()
        sb.appendLine("Tokens — input: ${turns.sumOf { it.inputTokens }}, output: ${turns.sumOf { it.outputTokens }}")
        sb.appendLine("Tool calls: ${turns.sumOf { it.toolCallCount }} total (${turns.sumOf { it.parallelToolCalls }} parallel, ${turns.sumOf { it.sequentialToolCalls }} serial)")
        sb.appendLine("Compressions: ${turns.count { it.compressed }} (${turns.count { it.summarized }} with LLM summarization)")
        sb.appendLine()
        sb.appendLine("Per-turn breakdown:")
        sb.appendLine(String.format("  %-5s %8s %8s %8s %8s %5s %8s %5s", "Turn", "LLM(ms)", "Tools(ms)", "Comp(ms)", "Total(ms)", "Tools", "Ctx(chars)", "InTok"))
        for (t in turns) {
            sb.appendLine(String.format("  %-5d %8d %8d %8d %8d %5d %8d %5d",
                t.turnIndex, t.llmCallMs, t.toolExecutionMs, t.compressionMs, t.turnTotalMs,
                t.toolCallCount, t.contextChars, t.inputTokens))
        }
        return sb.toString()
    }
}
