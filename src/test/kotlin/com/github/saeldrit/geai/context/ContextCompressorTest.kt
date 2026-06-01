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

    @Test
    fun `summariser folds the old middle, keeping the task and recent turns`() {
        val task = ChatMessage.user("original task")
        val middle = (1..10).flatMap { i ->
            listOf(
                ChatMessage.assistant(listOf(ContentBlock.ToolUse("t$i", "read_file", """{"path":"F$i"}"""))),
                ChatMessage.toolResults(listOf(ContentBlock.ToolResult("t$i", "CONTENT ".repeat(700)))),
            )
        }
        val recent = (1..6).map { ChatMessage.assistantText("recent $it") }
        val messages = listOf(task) + middle + recent

        // Tiny window → over budget → summarisation path (fake summariser returns a fixed recap).
        val result = ContextCompressor.compress(messages, 1_000, 100, 0) { "DENSE-RECAP" }

        assertEquals("task preserved verbatim", "original task", result[0].text)
        assertEquals("most recent turn preserved", "recent 6", result.last().text)
        assertTrue("old middle replaced by a recap", result.any { it.text.contains("DENSE-RECAP") })
        assertTrue("transcript shrank", result.size < messages.size)
        assertValidToolPairing(result)
    }

    @Test
    fun `summariser cut never orphans a tool_result`() {
        // Many tool pairs so the recent window would otherwise begin on a TOOL message; the cut must
        // back up to that result's tool_use so nothing is left dangling.
        val task = ChatMessage.user("task")
        val pairs = (1..12).flatMap { i ->
            listOf(
                ChatMessage.assistant(listOf(ContentBlock.ToolUse("t$i", "search_text", "{}"))),
                ChatMessage.toolResults(listOf(ContentBlock.ToolResult("t$i", "R".repeat(3_000)))),
            )
        }
        val result = ContextCompressor.compress(listOf(task) + pairs, 1_000, 100, 0) { "RECAP" }

        assertEquals("task", result[0].text)
        assertValidToolPairing(result)
    }

    @Test
    fun `a declining summariser falls back to truncation`() {
        val task = ChatMessage.user("task")
        val huge = ChatMessage.toolResults(listOf(ContentBlock.ToolResult("t1", "Z".repeat(40_000))))
        val recent = (1..6).map { ChatMessage.user("recent $it") }
        val messages = listOf(task, huge) + recent

        val result = ContextCompressor.compress(messages, 1_000, 100, 0) { "" } // blank → decline

        assertEquals("structure kept (truncation, not folding)", messages.size, result.size)
        val content = (result[1].content.first() as ContentBlock.ToolResult).content
        assertTrue("fell back to truncation", content.contains("truncated"))
    }

    /** Every tool_result must be preceded by its tool_use, or the provider rejects the transcript. */
    private fun assertValidToolPairing(messages: List<ChatMessage>) {
        val seen = HashSet<String>()
        for (message in messages) {
            for (block in message.content) {
                when (block) {
                    is ContentBlock.ToolUse -> seen.add(block.id)
                    is ContentBlock.ToolResult ->
                        assertTrue("orphaned tool_result ${block.toolUseId}", seen.contains(block.toolUseId))

                    is ContentBlock.Text -> Unit
                }
            }
        }
    }
}
