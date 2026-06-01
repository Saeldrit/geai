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
    private const val SAFETY = 0.6
    private const val KEEP_RECENT = 6
    private const val TRUNCATED_HEAD = 400
    private const val MIN_BUDGET = 20_000

    /** Summarises a rendered transcript segment into a dense recap. Returns null/blank to decline. */
    fun interface Summarizer {
        fun summarize(renderedSegment: String): String
    }

    fun compress(
        messages: List<ChatMessage>,
        contextWindowTokens: Int,
        outputReserveTokens: Int,
        systemPromptChars: Int = 0,
        summarizer: Summarizer? = null,
    ): List<ChatMessage> {
        val budget = charBudget(contextWindowTokens, outputReserveTokens, systemPromptChars)
        if (estimateChars(messages) <= budget) return messages

        // Preferred: fold the old middle into a recap (keeps findings, not just the first 400 chars).
        if (summarizer != null) {
            val summarised = runCatching { summarizeOldContext(messages, summarizer) }.getOrNull()
            if (summarised != null && summarised !== messages) {
                return if (estimateChars(summarised) <= budget) summarised else truncateToBudget(summarised, budget)
            }
        }
        // Fallback: deterministic truncation.
        return truncateToBudget(messages, budget)
    }

    /**
     * Replace messages[1 until tailStart] with a single recap, keeping the original task (index 0) and
     * the most recent turns verbatim. The cut is moved off any TOOL message so no tool_result is left
     * orphaned from its tool_use (which would make the transcript invalid).
     */
    private fun summarizeOldContext(messages: List<ChatMessage>, summarizer: Summarizer): List<ChatMessage> {
        val n = messages.size
        var tailStart = (n - KEEP_RECENT).coerceAtLeast(1)
        while (tailStart in 1 until n && messages[tailStart].role == Role.TOOL) tailStart--
        if (tailStart <= 1) return messages // nothing summarisable between the task and the recent tail

        val middle = messages.subList(1, tailStart)
        val recap = summarizer.summarize(renderForSummary(middle)).trim()
        if (recap.isBlank()) return messages

        val summaryMessage = ChatMessage(
            Role.USER,
            listOf(ContentBlock.Text("[Summary of earlier steps — older detail compacted to save context]\n$recap")),
        )
        return buildList {
            add(messages[0])
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

    // --- Truncation fallback (deterministic, no LLM) ------------------------------------------

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
            if (index == 0) continue // preserve the original task verbatim
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
        is ContentBlock.ToolResult -> block.content.length
    }
}
