package com.github.saeldrit.geai.context

import com.github.saeldrit.geai.llm.ChatMessage
import com.github.saeldrit.geai.llm.ContentBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompressorTest {

    @Test
    fun `under budget returns the transcript unchanged`() {
        val messages = listOf(ChatMessage.user("hello"), ChatMessage.assistantText("hi"))
        val result = ContextCompressor.compress(messages, contextWindowTokens = 200_000, outputReserveTokens = 8192)
        assertSame("small transcript must pass through untouched", messages, result)
        assertEquals("none", ContextCompressor.lastMetrics?.method)
    }

    @Test
    fun `modest transcript under a real model window stays intact`() {
        val messages = (1..5).flatMap { i ->
            listOf(
                ChatMessage.assistant(listOf(ContentBlock.ToolUse("t$i", "read_file", """{"path":"F$i.kt"}"""))),
                ChatMessage.toolResults(listOf(ContentBlock.ToolResult("t$i", "line content ".repeat(50)))),
            )
        }
        val result = ContextCompressor.compress(messages, 128_000, 16_384)
        assertSame("modest transcript under real model window stays intact", messages, result)
        assertEquals("none", ContextCompressor.lastMetrics?.method)
    }

    @Test
    fun `over budget folds old history into ONE summary message plus a verbatim tail`() {
        val task = ChatMessage.user("original task")
        val hugeTool = ChatMessage.toolResults(listOf(ContentBlock.ToolResult("t1", "x".repeat(30_000))))
        val recent = (1..6).map { ChatMessage.user("recent $it") }
        val messages = listOf(task, hugeTool) + recent

        val result = ContextCompressor.compress(messages, 1_000, 100, activeTask = "original task")

        assertEquals("most recent turn stays verbatim", "recent 6", result.last().text)
        assertTrue("summary message present", result.first().text.contains("[SESSION MEMORY"))
        assertTrue("active task survives in the summary", result.first().text.contains("original task"))
        assertTrue("transcript chars shrunk", estimateRough(result) < estimateRough(messages))
        assertFalse("old raw payload is gone, not marker-fied", result.any { it.text.contains("[COMPRESSED:") })
    }

    @Test
    fun `summariser recap is embedded and method is summarize`() {
        val task = ChatMessage.user("original task")
        val middle = (1..10).flatMap { i ->
            listOf(
                ChatMessage.assistant(listOf(ContentBlock.ToolUse("t$i", "read_file", """{"path":"F$i"}"""))),
                ChatMessage.toolResults(listOf(ContentBlock.ToolResult("t$i", "CONTENT ".repeat(700)))),
            )
        }
        val recent = (1..6).map { ChatMessage.assistantText("recent $it") }
        val messages = listOf(task) + middle + recent

        val result = ContextCompressor.compress(
            messages, 1_000, 100, 0,
            summarizer = ContextCompressor.Summarizer { "DENSE-RECAP" },
            activeTask = "original task",
        )

        assertEquals("most recent turn preserved", "recent 6", result.last().text)
        assertTrue("transcript shrank", estimateRough(result) < estimateRough(messages))
        assertTrue("recap embedded in the summary", result.first().text.contains("DENSE-RECAP"))
        assertTrue("deterministic ledger present alongside the recap", result.first().text.contains("[SESSION MEMORY"))
        assertValidToolPairing(result)
    }

    @Test
    fun `re-compressing an already compacted transcript is stable and does not re-summarise`() {
        val task = ChatMessage.user("original task")
        val middle = (1..10).flatMap { i ->
            listOf(
                ChatMessage.assistant(listOf(ContentBlock.ToolUse("t$i", "read_file", """{"path":"F$i"}"""))),
                ChatMessage.toolResults(listOf(ContentBlock.ToolResult("t$i", "CONTENT ".repeat(700)))),
            )
        }
        val recent = (1..6).map { ChatMessage.assistantText("recent $it") }
        val messages = listOf(task) + middle + recent

        var summariserCalls = 0
        val summariser = ContextCompressor.Summarizer { summariserCalls++; "DENSE-RECAP" }

        val once = ContextCompressor.compress(messages, 1_000, 100, 0, summariser)
        val callsAfterFirst = summariserCalls
        val twice = ContextCompressor.compress(once, 1_000, 100, 0, summariser)

        assertTrue("first pass shrank the transcript", estimateRough(once) < estimateRough(messages))
        assertSame("a compacted transcript is returned unchanged", once, twice)
        assertEquals("summariser is not called again on the compact form", callsAfterFirst, summariserCalls)
    }

    @Test
    fun `a declining summariser still produces the deterministic ledger`() {
        val task = ChatMessage.user("task")
        val middle = (1..8).flatMap { i ->
            listOf(
                ChatMessage.assistant(listOf(ContentBlock.ToolUse("t$i", "read_file", """{"path":"F$i.kt"}"""))),
                ChatMessage.toolResults(listOf(ContentBlock.ToolResult("t$i", "Z".repeat(5_000)))),
            )
        }
        val recent = (1..6).map { ChatMessage.user("recent $it") }
        val messages = listOf(task) + middle + recent

        val result = ContextCompressor.compress(
            messages, 1_000, 100, 0,
            summarizer = ContextCompressor.Summarizer { "" },
        )

        assertEquals("most recent turn preserved", "recent 6", result.last().text)
        assertTrue("ledger survives without the LLM", result.first().text.contains("[SESSION MEMORY"))
        assertTrue("files touched are listed", result.first().text.contains("F1.kt"))
        assertTrue("chars shrunk", estimateRough(result) < estimateRough(messages))
        assertValidToolPairing(result)
    }

    @Test
    fun `compression never orphans a tool_result from its tool_use`() {
        val task = ChatMessage.user("task")
        val pairs = (1..12).flatMap { i ->
            listOf(
                ChatMessage.assistant(listOf(ContentBlock.ToolUse("t$i", "search_text", "{}"))),
                ChatMessage.toolResults(listOf(ContentBlock.ToolResult("t$i", "R".repeat(3_000)))),
            )
        }
        val result = ContextCompressor.compress(
            listOf(task) + pairs, 1_000, 100, 0,
            summarizer = ContextCompressor.Summarizer { "RECAP" },
        )

        assertTrue("transcript shrank", estimateRough(result) < estimateRough(listOf(task) + pairs))
        assertValidToolPairing(result)
    }

    @Test
    fun `when the summariser runs it receives a structured digest of the folded segment`() {
        val task = ChatMessage.user("original task")
        val middle = (1..8).flatMap { i ->
            listOf(
                ChatMessage.assistant(listOf(ContentBlock.ToolUse("t$i", "read_file", """{"path":"F$i.kt"}"""))),
                ChatMessage.toolResults(listOf(ContentBlock.ToolResult("t$i", "CONTENT ".repeat(700)))),
            )
        }
        val recent = (1..6).map { ChatMessage.assistantText("recent $it") }
        val messages = listOf(task) + middle + recent

        var captured = ""
        val result = ContextCompressor.compress(
            messages, 1_000, 100, 0,
            summarizer = ContextCompressor.Summarizer { seg -> captured = seg; "RECAP" },
        )

        assertTrue("summariser saw the touched files", captured.contains("F1.kt"))
        assertTrue("recap embedded", result.first().text.contains("RECAP"))
        assertValidToolPairing(result)
        assertTrue("chars shrunk", estimateRough(result) < estimateRough(messages))
    }

    @Test
    fun `giant recent tail degrades by truncation while protecting the summary`() {
        val task = ChatMessage.user("task")
        val pairs = (1..4).flatMap { i ->
            listOf(
                ChatMessage.assistant(listOf(ContentBlock.ToolUse("t$i", "read_file", """{"path":"F$i"}"""))),
                ChatMessage.toolResults(listOf(ContentBlock.ToolResult("t$i", "HUGE ".repeat(4_000)))),
            )
        }
        val messages = listOf(task) + pairs

        val result = ContextCompressor.compress(messages, 1_000, 100)

        assertEquals("truncate", ContextCompressor.lastMetrics?.method)
        assertTrue("fits the budget after degradation", estimateRough(result) < estimateRough(messages))
        assertValidToolPairing(result)
    }

    @Test
    fun `compression metrics reflect the shrink`() {
        val task = ChatMessage.user("Investigate the UI structure of the app")
        val fileReads = (1..15).flatMap { i ->
            listOf(
                ChatMessage.assistant(listOf(ContentBlock.ToolUse("t$i", "read_file", """{"path":"src/F$i.kt"}"""))),
                ChatMessage.toolResults(listOf(ContentBlock.ToolResult("t$i", "package com.example\n" + "x".repeat(5_000)))),
            )
        }
        val messages = listOf(task) + fileReads

        val result = ContextCompressor.compress(
            messages, 1_000, 100, 0,
            summarizer = ContextCompressor.Summarizer { "DENSE-RECAP-OF-FINDINGS" },
        )

        val metrics = ContextCompressor.lastMetrics!!
        assertTrue("compression happened", metrics.method != "none")
        assertTrue("ratio < 1.0 (shrank)", metrics.ratio < 1.0f)
        assertTrue("input > output", metrics.inputChars > metrics.outputChars)
        assertValidToolPairing(result)
    }

    private fun estimateRough(messages: List<ChatMessage>): Int =
        messages.sumOf { m -> m.content.sumOf { b ->
            when (b) {
                is ContentBlock.Text -> b.text.length
                is ContentBlock.ToolUse -> b.name.length + b.inputJson.length
                is ContentBlock.ToolResult -> b.content.length
                is ContentBlock.Image -> b.base64Data.length
            }
        } }

    private fun assertValidToolPairing(messages: List<ChatMessage>) {
        val seen = HashSet<String>()
        for (message in messages) {
            for (block in message.content) {
                when (block) {
                    is ContentBlock.ToolUse -> seen.add(block.id)
                    is ContentBlock.ToolResult ->
                        assertTrue("orphaned tool_result ${block.toolUseId}", seen.contains(block.toolUseId))
                    is ContentBlock.Text -> Unit
                    is ContentBlock.Image -> Unit
                }
            }
        }
    }
}
