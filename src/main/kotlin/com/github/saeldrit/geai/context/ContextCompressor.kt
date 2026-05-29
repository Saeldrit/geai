package com.github.saeldrit.geai.context

import com.github.saeldrit.geai.llm.ChatMessage
import com.github.saeldrit.geai.llm.ContentBlock
import com.github.saeldrit.geai.llm.Role

/**
 * Deterministic, dependency-free context compaction. When the running transcript exceeds a
 * character budget (~4 chars/token), the oldest *tool outputs* are truncated to a short head
 * while recent turns and the original task are preserved verbatim. This keeps navigation intact
 * and lets a long debugging session survive without an extra summarization round-trip.
 */
object ContextCompressor {

    private const val CHAR_BUDGET = 200_000
    private const val KEEP_RECENT = 6
    private const val TRUNCATED_HEAD = 400

    fun compress(messages: List<ChatMessage>, @Suppress("UNUSED_PARAMETER") maxTokens: Int): List<ChatMessage> {
        if (estimateChars(messages) <= CHAR_BUDGET) return messages

        val working = messages.toMutableList()
        val protectedFrom = (working.size - KEEP_RECENT).coerceAtLeast(0)
        for (index in 0 until protectedFrom) {
            if (estimateChars(working) <= CHAR_BUDGET) break
            val message = working[index]
            if (message.role != Role.TOOL) continue
            val compacted = message.content.map { block ->
                if (block is ContentBlock.ToolResult && block.content.length > TRUNCATED_HEAD) {
                    block.copy(content = block.content.take(TRUNCATED_HEAD) + "\n…[older tool output truncated to save context]")
                } else {
                    block
                }
            }
            working[index] = message.copy(content = compacted)
        }
        return working
    }

    private fun estimateChars(messages: List<ChatMessage>): Int =
        messages.sumOf { message -> message.content.sumOf(::blockLength) }

    private fun blockLength(block: ContentBlock): Int = when (block) {
        is ContentBlock.Text -> block.text.length
        is ContentBlock.ToolUse -> block.name.length + block.inputJson.length
        is ContentBlock.ToolResult -> block.content.length
    }
}
