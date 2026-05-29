package com.github.saeldrit.geai.context

import com.github.saeldrit.geai.llm.ChatMessage
import com.github.saeldrit.geai.llm.ContentBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompressorTest {

    @Test
    fun `under budget returns the transcript unchanged`() {
        val messages = listOf(ChatMessage.user("hello"), ChatMessage.assistantText("hi"))
        val result = ContextCompressor.compress(messages, contextWindowTokens = 200_000, outputReserveTokens = 8192)
        assertSame("small transcript must pass through untouched", messages, result)
    }

    @Test
    fun `over budget truncates old tool output but preserves task and recent turns`() {
        val task = ChatMessage.user("original task")
        val hugeTool = ChatMessage.toolResults(listOf(ContentBlock.ToolResult("t1", "x".repeat(30_000))))
        val recent = (1..6).map { ChatMessage.user("recent $it") }
        val messages = listOf(task, hugeTool) + recent

        // Tiny window forces the floor budget (~20k chars); the 30k tool output must be compacted.
        val result = ContextCompressor.compress(messages, contextWindowTokens = 1_000, outputReserveTokens = 100)

        assertEquals("message count is preserved", messages.size, result.size)
        assertEquals("original task stays verbatim", "original task", result[0].text)
        assertEquals("most recent turn stays verbatim", "recent 6", result.last().text)

        val compactedTool = (result[1].content.first() as ContentBlock.ToolResult).content
        assertTrue("old tool output was truncated", compactedTool.length < 1_000)
        assertTrue("truncation marker present", compactedTool.contains("truncated"))
    }
}
