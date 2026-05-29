package com.github.saeldrit.geai.context

import com.github.saeldrit.geai.llm.ChatMessage
import com.github.saeldrit.geai.llm.ContentBlock
import com.github.saeldrit.geai.llm.Role

/**
 * Deterministic, dependency-free context compaction. The character budget is derived from the
 * model's context window (~4 chars/token) minus the output reservation, so the transcript is
 * sized to the model actually in use rather than a fixed constant. Compaction happens in two
 * passes: first the oldest *tool outputs* are truncated to a short head, then — only if still over
 * budget — older assistant/user *text* is trimmed too. The original task (first message) and the
 * most recent turns are always preserved verbatim so navigation and intent stay intact.
 */
object ContextCompressor {

    private const val CHARS_PER_TOKEN = 4
    private const val SAFETY = 0.8
    private const val KEEP_RECENT = 6
    private const val TRUNCATED_HEAD = 400
    private const val MIN_BUDGET = 20_000

    fun compress(messages: List<ChatMessage>, contextWindowTokens: Int, outputReserveTokens: Int): List<ChatMessage> {
        val budget = charBudget(contextWindowTokens, outputReserveTokens)
        if (estimateChars(messages) <= budget) return messages

        val working = messages.toMutableList()
        val protectedFrom = (working.size - KEEP_RECENT).coerceAtLeast(0)

        // Pass 1: truncate the bulky stuff first — old tool outputs.
        compactRange(working, protectedFrom, budget) { it.role == Role.TOOL }
        if (estimateChars(working) <= budget) return working

        // Pass 2: still over — trim old assistant/user text, but never the original task at index 0.
        compactRange(working, protectedFrom, budget) { it.role == Role.ASSISTANT || it.role == Role.USER }
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

    private fun charBudget(contextWindowTokens: Int, outputReserveTokens: Int): Int {
        val usableTokens = (contextWindowTokens - outputReserveTokens).coerceAtLeast(0)
        return (usableTokens * CHARS_PER_TOKEN * SAFETY).toInt().coerceAtLeast(MIN_BUDGET)
    }

    private fun estimateChars(messages: List<ChatMessage>): Int =
        messages.sumOf { message -> message.content.sumOf(::blockLength) }

    private fun blockLength(block: ContentBlock): Int = when (block) {
        is ContentBlock.Text -> block.text.length
        is ContentBlock.ToolUse -> block.name.length + block.inputJson.length
        is ContentBlock.ToolResult -> block.content.length
    }
}
