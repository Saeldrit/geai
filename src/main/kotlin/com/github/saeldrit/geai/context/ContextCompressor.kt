package com.github.saeldrit.geai.context

import com.github.saeldrit.geai.llm.ChatMessage
import com.github.saeldrit.geai.llm.ContentBlock
import com.github.saeldrit.geai.llm.Role

/**
 * Context compaction for the agent transcript. When the transcript outgrows the budget the preferred
 * strategy is SUMMARISATION: the old middle of the conversation is folded into one dense recap that
 * preserves what matters to keep working (the task, files/symbols examined with locations, findings,
 * decisions, open questions) while dropping raw file dumps and chatter — so a long multi-file task
 * (e.g. a review) keeps its evidence instead of having it chopped to a 400-char head. Truncation is
 * kept only as a dependency-free FALLBACK for when no summariser is supplied or summarisation fails.
 *
 * The character budget is derived from the model's context window (~4 chars/token) minus the output
 * reservation, so the transcript is sized to the model actually in use.
 */
object ContextCompressor {

    private const val CHARS_PER_TOKEN = 4
    private const val SAFETY = 0.5
    private const val KEEP_RECENT = 6
    private const val TRUNCATED_HEAD = 400
    private const val MIN_BUDGET = 20_000

    /**
     * Eager truncation: protect the N most recent TOOL messages verbatim (the model is still working
     * with them), and aggressively shrink every older tool_result to a fixed head — regardless of
     * budget. This stops the transcript from ballooning between compactions (file dumps, search
     * tables) and keeps caching cheap: stable older content stays small and reusable.
     */
    private const val EAGER_KEEP_RECENT_TOOLS = 8
    private const val EAGER_TOOL_HEAD = 2_000

    /** Suffix marking an eagerly-truncated tool result, so re-compaction is idempotent (no re-trim) —
     *  the loop persists compaction back into the transcript, so this must be a fixed point. */
    private const val EAGER_TRUNC_MARKER = "…[truncated to save context]"

    /** Summarises a rendered transcript segment into a dense recap. Returns null/blank to decline. */
    fun interface Summarizer {
        fun summarize(renderedSegment: String): String
    }

    /** Compression metrics from the last compress() call. */
    data class CompressionMetrics(
        val inputChars: Int,
        val outputChars: Int,
        val ratio: Float,
        val method: String, // "none", "eager", "summarize", "truncate"
    )

    @Volatile
    var lastMetrics: CompressionMetrics? = null
        private set

    fun compress(
        messages: List<ChatMessage>,
        contextWindowTokens: Int,
        outputReserveTokens: Int,
        systemPromptChars: Int = 0,
        summarizer: Summarizer? = null,
        activeTask: String = "",
    ): List<ChatMessage> {
        val budget = charBudget(contextWindowTokens, outputReserveTokens, systemPromptChars)
        val originalChars = estimateChars(messages)

        // Under budget: only apply eager truncation when approaching the limit (>70%).
        // When context is small (early turns), this saves an O(n) pass over all messages.
        if (originalChars <= budget) {
            if (originalChars > budget * 0.7) {
                val result = eagerlyTruncateOldToolResults(messages)
                lastMetrics = CompressionMetrics(originalChars, estimateChars(result), 1.0f, "eager")
                return result
            }
            lastMetrics = CompressionMetrics(originalChars, originalChars, 1.0f, "none")
            return messages
        }

        // Over budget: preferred path is summarisation — fold the old middle into a dense recap.
        // Use the ORIGINAL (untrimmed) messages so findings past the eager head survive into the recap;
        // if the full render is too large for the summariser it throws — retry on the eager-trimmed copy.
        if (summarizer != null) {
            val eagerlyTrimmed = eagerlyTruncateOldToolResults(messages)
            val summarised = runCatching { summarizeOldContext(messages, summarizer, activeTask) }.getOrNull()
                ?: runCatching { summarizeOldContext(eagerlyTrimmed, summarizer, activeTask) }.getOrNull()
            if (summarised != null && summarised !== messages) {
                // The recap shrank the middle; the kept recent tail is still verbatim — eager-trim it too.
                val trimmed = eagerlyTruncateOldToolResults(summarised)
                val final = if (estimateChars(trimmed) <= budget) trimmed else truncateToBudget(trimmed, budget)
                val outputChars = estimateChars(final)
                lastMetrics = CompressionMetrics(originalChars, outputChars, outputChars.toFloat() / originalChars, "summarize")
                return final
            }
        }
        // Fallback: deterministic truncation with eager pre-pass.
        val result = truncateToBudget(eagerlyTruncateOldToolResults(messages), budget)
        val outputChars = estimateChars(result)
        lastMetrics = CompressionMetrics(originalChars, outputChars, outputChars.toFloat() / originalChars, "truncate")
        return result
    }

    /**
     * Replace every tool_result older than the last [EAGER_KEEP_RECENT_TOOLS] TOOL messages with its
     * head ([EAGER_TOOL_HEAD] chars). Operates on a shallow copy; returns the original list when no
     * trimming applies, so identity-equality short-circuits work upstream.
     */
    private fun eagerlyTruncateOldToolResults(messages: List<ChatMessage>): List<ChatMessage> {
        val toolIndices = messages.withIndex().filter { it.value.role == Role.TOOL }.map { it.index }
        if (toolIndices.size <= EAGER_KEEP_RECENT_TOOLS) return messages
        val protectedIndices = toolIndices.takeLast(EAGER_KEEP_RECENT_TOOLS).toSet()
        var modified = false
        val result = messages.mapIndexed { index, message ->
            if (message.role != Role.TOOL || index in protectedIndices) return@mapIndexed message
            val shrunk = message.content.map { block ->
                if (block is ContentBlock.ToolResult && block.content.length > EAGER_TOOL_HEAD &&
                    !block.content.endsWith(EAGER_TRUNC_MARKER)
                ) {
                    modified = true
                    block.copy(content = block.content.take(EAGER_TOOL_HEAD) + "\n" + EAGER_TRUNC_MARKER)
                } else {
                    block
                }
            }
            if (shrunk === message.content) message else message.copy(content = shrunk)
        }
        return if (modified) result else messages
    }

    /**
     * Replace messages[0 until tailStart] with a single recap, keeping only the most recent turns
     * verbatim. The first message is NOT sacred — it is included in the summarizable segment so
     * the model does not anchor on a stale initial query. The active task (injected separately by
     * [AgentLoop]) is the sole task anchor. The cut is moved off any TOOL message so no tool_result
     * is left orphaned from its tool_use (which would make the transcript invalid).
     */
    private fun summarizeOldContext(messages: List<ChatMessage>, summarizer: Summarizer, activeTask: String = ""): List<ChatMessage> {
        val n = messages.size
        var tailStart = (n - KEEP_RECENT).coerceAtLeast(1)
        while (tailStart in 1 until n && messages[tailStart].role == Role.TOOL) tailStart--
        if (tailStart <= 1) return messages // nothing summarisable before the recent tail

        val middle = messages.subList(0, tailStart)
        val digest = TranscriptAnalyzer.analyze(middle, activeTask)
        val rendered = digest.renderForSummarizer()
        // Fallback: if the digest is too sparse, use the flat render.
        val inputForSummarizer = if (rendered.length > 50) rendered else renderForSummary(middle)
        val rawRecap = summarizer.summarize(inputForSummarizer).trim()
        if (rawRecap.isBlank()) return messages

        // Post-process: try to parse as structured SemanticSummary; falls back to raw text.
        val recap = SemanticCompressor.parseAndRender(rawRecap)

        val summaryMessage = ChatMessage(
            Role.USER,
            listOf(ContentBlock.Text("[Structured summary of earlier steps — older detail compacted to save context]\n$recap")),
        )
        return buildList {
            add(summaryMessage)
            addAll(messages.subList(tailStart, n))
        }
    }

    /** Flatten a transcript segment to plain text for the summariser. */
    private fun renderForSummary(segment: List<ChatMessage>): String = buildString {
        for (message in segment) {
            when (message.role) {
                Role.USER -> message.text.takeIf { it.isNotBlank() }?.let { appendLine("USER: $it") }
                Role.ASSISTANT -> {
                    message.text.takeIf { it.isNotBlank() }?.let { appendLine("ASSISTANT: $it") }
                    message.toolUses.forEach { appendLine("CALL ${it.name}(${it.inputJson})") }
                }

                Role.TOOL -> message.content.filterIsInstance<ContentBlock.ToolResult>()
                    .forEach { appendLine("RESULT${if (it.isError) " (error)" else ""}: ${it.content}") }

                Role.SYSTEM -> Unit
            }
        }
    }

    // Truncation fallback (deterministic, no LLM)

    private fun truncateToBudget(messages: List<ChatMessage>, budget: Int): List<ChatMessage> {
        if (estimateChars(messages) <= budget) return messages
        val working = messages.toMutableList()
        val protectedFrom = (working.size - KEEP_RECENT).coerceAtLeast(0)
        compactRange(working, protectedFrom, budget) { it.role == Role.TOOL }
        if (estimateChars(working) <= budget) return working
        compactRange(working, protectedFrom, budget) { it.role == Role.ASSISTANT || it.role == Role.USER }
        if (estimateChars(working) <= budget) return working
        compactRange(working, working.size, budget) { true }
        return working
    }

    private inline fun compactRange(
        working: MutableList<ChatMessage>,
        protectedFrom: Int,
        budget: Int,
        eligible: (ChatMessage) -> Boolean,
    ) {
        for (index in 0 until protectedFrom) {
            if (estimateChars(working) <= budget) return
            // No message is sacred — all old messages (including the original task) are eligible
            // for truncation. The active task is injected separately by AgentLoop.
            val message = working[index]
            if (!eligible(message)) continue
            working[index] = message.copy(content = message.content.map(::truncateBlock))
        }
    }

    private fun truncateBlock(block: ContentBlock): ContentBlock = when (block) {
        is ContentBlock.ToolResult ->
            if (block.content.length > TRUNCATED_HEAD) {
                block.copy(content = block.content.take(TRUNCATED_HEAD) + "\n…[older tool output truncated to save context]")
            } else {
                block
            }

        is ContentBlock.Text ->
            if (block.text.length > TRUNCATED_HEAD) {
                ContentBlock.Text(block.text.take(TRUNCATED_HEAD) + "\n…[older message truncated to save context]")
            } else {
                block
            }

        is ContentBlock.Image -> block   // images are never truncated
        is ContentBlock.ToolUse -> block
    }

    private fun charBudget(contextWindowTokens: Int, outputReserveTokens: Int, systemPromptChars: Int = 0): Int {
        val usableTokens = (contextWindowTokens - outputReserveTokens).coerceAtLeast(0)
        val totalBudget = (usableTokens * CHARS_PER_TOKEN * SAFETY).toInt().coerceAtLeast(MIN_BUDGET)
        return (totalBudget - systemPromptChars).coerceAtLeast(MIN_BUDGET)
    }

    /** Rough token estimate of a transcript (~chars/token) — for the usage panel and manual /compact. */
    fun estimatedTokens(messages: List<ChatMessage>): Int = estimateChars(messages) / CHARS_PER_TOKEN

    private fun estimateChars(messages: List<ChatMessage>): Int =
        messages.sumOf { message -> message.content.sumOf(::blockLength) }

    private fun blockLength(block: ContentBlock): Int = when (block) {
        is ContentBlock.Text -> block.text.length
        is ContentBlock.ToolUse -> block.name.length + block.inputJson.length
        is ContentBlock.Image -> block.base64Data.length  // rough size estimate
        is ContentBlock.ToolResult -> block.content.length
    }
}
