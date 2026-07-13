package com.github.saeldrit.geai.benchmark

import java.util.Locale

data class RunResult(
    val taskId: String,
    val config: String,
    val model: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheReadTokens: Int,
    val cacheWriteTokens: Int,
    val costUsd: Double?,
    val toolCounts: Map<String, Int>,
    val iterations: Int,
    val wallMs: Long,
    val answerChars: Int,
    val error: String?,
) {
    val billedFootprint: Int get() = inputTokens + outputTokens + cacheWriteTokens

    private val graceTools = setOf("context_bundle", "resolve_ref", "graph_query", "graph_neighbors", "spec_lookup", "spec_list")
    private val fileTools = setOf("read_file", "search_text", "find_files", "list_files")

    val graceToolCalls: Int get() = toolCounts.filterKeys { it in graceTools }.values.sum()
    val fileToolCalls: Int get() = toolCounts.filterKeys { it in fileTools }.values.sum()
}

data class BenchmarkReport(val results: List<RunResult>, val stampMs: Long) {

    fun toMarkdown(): String = buildString {
        appendLine("# geai benchmark — $stampMs")
        appendLine()
        appendLine("Tokens: ↑input ↓output, cR=cache read, cW=cache write. Cost from the model price table (blank if unset).")
        results.groupBy { it.taskId }.forEach { (taskId, runs) ->
            appendLine()
            appendLine("## Task: $taskId")
            appendLine()
            appendLine("| config | model | ↑in | ↓out | cR | cW | billed | \$ | grace/file tools | iters | ms | ans | err |")
            appendLine("|---|---|---:|---:|---:|---:|---:|---:|---|---:|---:|---:|---|")
            runs.forEach { r ->
                val cost = r.costUsd?.let { "$" + String.format(Locale.US, "%.4f", it) } ?: "—"
                val err = r.error?.take(30) ?: ""
                appendLine(
                    "| ${r.config} | ${r.model} | ${r.inputTokens} | ${r.outputTokens} | ${r.cacheReadTokens} | " +
                        "${r.cacheWriteTokens} | ${r.billedFootprint} | $cost | ${r.graceToolCalls}/${r.fileToolCalls} | " +
                        "${r.iterations} | ${r.wallMs} | ${r.answerChars} | $err |",
                )
            }
            deltaLine(runs)?.let { appendLine(); appendLine(it) }
        }
    }

    private fun deltaLine(runs: List<RunResult>): String? {
        val base = runs.firstOrNull { it.config.equals("baseline", true) } ?: return null
        val grace = runs.firstOrNull { it.config.contains("grace", true) } ?: return null
        if (base.billedFootprint == 0) return null
        val ratio = grace.billedFootprint.toDouble() / base.billedFootprint
        val verdict = if (ratio < 1.0) "GRACE cheaper" else "GRACE more expensive"
        return "**Δ billed: ${grace.billedFootprint} vs ${base.billedFootprint} = " +
            String.format(Locale.US, "%.2f", ratio) + "× ($verdict)**"
    }
}
