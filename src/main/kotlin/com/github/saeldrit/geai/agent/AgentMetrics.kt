package com.github.saeldrit.geai.agent

import java.util.concurrent.CopyOnWriteArrayList

class AgentMetrics {

    data class TurnMetrics(
        val turnIndex: Int,
        val llmCallMs: Long,
        val toolExecutionMs: Long,
        val compressionMs: Long,
        val turnTotalMs: Long,
        val messagesBefore: Int,
        val messagesAfter: Int,
        val toolCallCount: Int,
        val parallelToolCalls: Int,
        val sequentialToolCalls: Int,
        val contextChars: Int,
        val inputTokens: Int,
        val outputTokens: Int,
        val compressed: Boolean,
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
