package com.github.saeldrit.geai.llm.anthropic

import com.github.saeldrit.geai.llm.ChatMessage
import com.github.saeldrit.geai.llm.ChatRequest
import com.github.saeldrit.geai.llm.ContentBlock
import com.github.saeldrit.geai.llm.ToolSpec
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the prompt-cache wire contract: the whole point of the caching fix is that the STABLE
 * prefix (system + tools) is one cached block, and the growing transcript caches via a rolling
 * breakpoint on the last tool_result — with the volatile bundle/notes sitting AFTER that breakpoint
 * (in trailing user messages) so they never invalidate the transcript cache.
 */
class AnthropicClientCacheTest {

    private val client = AnthropicClient(baseUrl = "http://localhost", apiKey = "test-key")

    private fun spec(name: String) = ToolSpec(name, "desc $name", """{"type":"object","properties":{}}""")

    /** A realistic turn: task → assistant tool_use → tool_result → trailing volatile bundle + notes. */
    private fun sampleRequest() = ChatRequest(
        model = "claude-opus-4-8",
        system = "STABLE SYSTEM PROMPT",
        messages = listOf(
            ChatMessage.user("do the task"),
            ChatMessage.assistant(listOf(ContentBlock.ToolUse("t1", "read_file", "{}"))),
            ChatMessage.toolResults(listOf(ContentBlock.ToolResult("t1", "file contents here"))),
            ChatMessage.user("<context_bundle>\nvolatile bundle\n</context_bundle>"),
            ChatMessage.user("<your_notes>\n- a finding\n</your_notes>"),
        ),
        tools = listOf(spec("read_file"), spec("edit_file")),
    )

    private fun JsonObject.blocksOf(msgIndex: Int) =
        getAsJsonArray("messages").get(msgIndex).asJsonObject.getAsJsonArray("content")

    @Test
    fun `system is a single stable cached block with no volatile suffix`() {
        val body = client.buildRequestBody(sampleRequest(), streaming = false)
        val system = body.getAsJsonArray("system")
        assertEquals("exactly one system block — no volatile second block", 1, system.size())
        val block = system.get(0).asJsonObject
        assertEquals("STABLE SYSTEM PROMPT", block.get("text").asString)
        assertTrue("stable system must carry the cache breakpoint", block.has("cache_control"))
        assertFalse("the volatile bundle must NOT be in the system prefix", block.get("text").asString.contains("context_bundle"))
    }

    @Test
    fun `last tool spec carries the tools cache breakpoint`() {
        val body = client.buildRequestBody(sampleRequest(), streaming = false)
        val tools = body.getAsJsonArray("tools")
        assertFalse("first tool is not a breakpoint", tools.get(0).asJsonObject.has("cache_control"))
        assertTrue("last tool carries the tools breakpoint", tools.get(tools.size() - 1).asJsonObject.has("cache_control"))
    }

    @Test
    fun `rolling breakpoint lands on the last tool_result, not on the trailing volatile messages`() {
        val body = client.buildRequestBody(sampleRequest(), streaming = false)
        // messages[2] is the tool_result turn; its single tool_result must carry the rolling breakpoint.
        val toolResult = body.blocksOf(2).get(0).asJsonObject
        assertEquals("tool_result", toolResult.get("type").asString)
        assertTrue("last tool_result carries the rolling transcript breakpoint", toolResult.has("cache_control"))

        // messages[3] and [4] are the trailing volatile bundle/notes — they must be plain, uncached.
        for (idx in 3..4) {
            body.blocksOf(idx).forEach { b ->
                assertFalse("trailing volatile content must not carry a cache breakpoint", b.asJsonObject.has("cache_control"))
            }
        }
        assertTrue("bundle is delivered as a trailing user message", body.blocksOf(3).get(0).asJsonObject.get("text").asString.contains("context_bundle"))
    }

    @Test
    fun `at most four cache breakpoints are emitted`() {
        val body = client.buildRequestBody(sampleRequest(), streaming = false)
        var breakpoints = 0
        body.getAsJsonArray("system").forEach { if (it.asJsonObject.has("cache_control")) breakpoints++ }
        body.getAsJsonArray("tools").forEach { if (it.asJsonObject.has("cache_control")) breakpoints++ }
        body.getAsJsonArray("messages").forEach { m ->
            m.asJsonObject.getAsJsonArray("content").forEach { if (it.asJsonObject.has("cache_control")) breakpoints++ }
        }
        assertTrue("Anthropic allows at most 4 cache_control breakpoints; got $breakpoints", breakpoints in 1..4)
    }
}
