package com.github.saeldrit.geai.agent

import com.github.saeldrit.geai.llm.ChatMessage
import com.github.saeldrit.geai.llm.ChatResult
import com.github.saeldrit.geai.llm.ContentBlock
import com.github.saeldrit.geai.llm.StopReason
import com.github.saeldrit.geai.llm.TokenUsage
import com.github.saeldrit.geai.settings.GeaiSettings
import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.GeaiToolset
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolRegistry
import com.github.saeldrit.geai.tools.ToolResult
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Exercises the real [AgentLoop] against a scripted [FakeLlmClient] — no network, no IDE dialogs. */
class AgentLoopTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // Minimal, deterministic loop: no graph/bundle machinery, and mutating tools auto-approved
        // (so the partition test never blocks on an approval dialog).
        GeaiSettings.getInstance().state.graceEnabled = false
        GeaiSettings.getInstance().state.autoApproveEditTools = true
    }

    private fun runLoop(fake: FakeLlmClient, registry: ToolRegistry = GeaiToolset.registry()): Pair<AgentSession, List<AgentEvent>> {
        val session = AgentSession()
        val events = mutableListOf<AgentEvent>()
        // Tools emit ToolFinished from a worker pool, so guard the list.
        val listener = AgentListener { event -> synchronized(events) { events.add(event) } }
        AgentLoop(project, registry, clientOverride = fake).run(session, "go", listener, EmptyProgressIndicator())
        return session to synchronized(events) { events.toList() }
    }

    fun testTurnTerminatesAndEmitsDone() {
        val (_, events) = runLoop(FakeLlmClient(listOf(FakeLlmClient.endTurn("all done"))))
        assertEquals("exactly one Done per finished turn", 1, events.count { it is AgentEvent.Done })
    }

    fun testStuckGuardNudgesThenAborts() {
        // The model repeats the identical call; an unknown tool returns the same error result each time,
        // so the loop must nudge once and then abort as stuck rather than spin forever.
        val repeat = FakeLlmClient.toolUse("t1", "__no_such_tool__", "{}")
        val (_, events) = runLoop(FakeLlmClient(listOf(repeat, repeat, repeat, FakeLlmClient.endTurn("x"))))
        assertTrue(
            "aborts as stuck after a nudge",
            events.any { it is AgentEvent.Error && it.text.contains("stuck", ignoreCase = true) },
        )
    }

    fun testStuckGuardCatchesAlternatingCycle() {
        // A/B/A/B thrashing: two distinct calls that each return a result already seen, never making
        // progress. The old single-slot guard saw no CONSECUTIVE repeat and missed it; the ring catches it.
        val registry = ToolRegistry(listOf(FAKE_READONLY))
        val a = FakeLlmClient.toolUse("t1", "ro_fake", """{"k":"a"}""")
        val b = FakeLlmClient.toolUse("t2", "ro_fake", """{"k":"b"}""")
        val (_, events) = runLoop(FakeLlmClient(listOf(a, b, a, b, a, b, FakeLlmClient.endTurn("x"))), registry)
        assertTrue(
            "aborts on a 2-cycle even without consecutive repeats",
            events.any { it is AgentEvent.Error && it.text.contains("stuck", ignoreCase = true) },
        )
    }

    fun testReadOnlyAndMutatingToolsBothProduceResults() {
        // P0 dispatch seam: read-only tools run in parallel, mutating tools sequentially — every
        // tool_use must still get exactly one tool_result regardless of which path it took.
        val registry = ToolRegistry(listOf(FAKE_READONLY, FAKE_MUTATING))
        val both = ChatResult(
            ChatMessage.assistant(
                listOf(
                    ContentBlock.ToolUse("t1", "ro_fake", "{}"),
                    ContentBlock.ToolUse("t2", "mut_fake", "{}"),
                ),
            ),
            StopReason.TOOL_USE,
            TokenUsage.ZERO,
        )
        val (session, _) = runLoop(FakeLlmClient(listOf(both, FakeLlmClient.endTurn("done"))), registry)
        val resultIds = session.messages
            .flatMap { it.content }
            .filterIsInstance<ContentBlock.ToolResult>()
            .map { it.toolUseId }
            .toSet()
        assertTrue("both the read-only and the mutating tool produced a result", resultIds.containsAll(setOf("t1", "t2")))
    }

    private companion object {
        private val FAKE_READONLY = object : AgentTool {
            override val name = "ro_fake"
            override val description = "fake read-only"
            override val parametersJsonSchema = """{"type":"object"}"""
            override fun execute(args: ToolArgs, context: ToolContext) = ToolResult.ok("read")
        }
        private val FAKE_MUTATING = object : AgentTool {
            override val name = "mut_fake"
            override val description = "fake mutating"
            override val parametersJsonSchema = """{"type":"object"}"""
            override val mutating = true
            override fun execute(args: ToolArgs, context: ToolContext) = ToolResult.ok("wrote")
        }
    }
}
