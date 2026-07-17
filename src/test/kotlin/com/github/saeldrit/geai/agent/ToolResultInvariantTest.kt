package com.github.saeldrit.geai.agent

import com.github.saeldrit.geai.llm.ContentBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every tool_use MUST end up with a matching tool_result, or the provider 400s the next request and
 * the reused session is permanently broken. backfillMissingToolResults enforces that even when an
 * interruption skipped some tools.
 */
class ToolResultInvariantTest {

    private fun call(id: String) = ContentBlock.ToolUse(id, "read_file", "{}")
    private fun result(id: String) = ContentBlock.ToolResult(id, "ok", isError = false)

    @Test
    fun `missing results are backfilled with error stubs`() {
        val calls = listOf(call("a"), call("b"), call("c"))
        val results = mutableListOf(result("a")) // b and c were skipped (interrupted)
        backfillMissingToolResults(calls, results)

        val ids = results.map { it.toolUseId }.toSet()
        assertEquals("every tool_use id now has a result", setOf("a", "b", "c"), ids)
        assertTrue("backfilled b is an error", results.first { it.toolUseId == "b" }.isError)
        assertTrue("backfilled c is an error", results.first { it.toolUseId == "c" }.isError)
        assertTrue("pre-existing result a is untouched", !results.first { it.toolUseId == "a" }.isError)
    }

    @Test
    fun `already-complete results are left unchanged`() {
        val calls = listOf(call("a"), call("b"))
        val results = mutableListOf(result("a"), result("b"))
        backfillMissingToolResults(calls, results)
        assertEquals("no phantom results added", 2, results.size)
    }

    @Test
    fun `no calls is a no-op`() {
        val results = mutableListOf<ContentBlock.ToolResult>()
        backfillMissingToolResults(emptyList(), results)
        assertTrue(results.isEmpty())
    }
}
