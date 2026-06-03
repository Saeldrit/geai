package com.github.saeldrit.geai.agent

import com.github.saeldrit.geai.settings.GeaiSettings
import com.github.saeldrit.geai.tools.GeaiToolset
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Exercises the real [AgentLoop] against a scripted [FakeLlmClient] — no network, no IDE dialogs. */
class AgentLoopTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // Keep the loop minimal and deterministic — no graph/bundle machinery.
        GeaiSettings.getInstance().state.graceEnabled = false
    }

    private fun runLoop(fake: FakeLlmClient): List<AgentEvent> {
        val events = mutableListOf<AgentEvent>()
        // Tools emit ToolFinished from a worker pool, so guard the list.
        val listener = AgentListener { event -> synchronized(events) { events.add(event) } }
        AgentLoop(project, GeaiToolset.registry(), clientOverride = fake)
            .run(AgentSession(), "go", listener, EmptyProgressIndicator())
        return synchronized(events) { events.toList() }
    }

    fun testTurnTerminatesAndEmitsDone() {
        val events = runLoop(FakeLlmClient(listOf(FakeLlmClient.endTurn("all done"))))
        assertEquals("exactly one Done per finished turn", 1, events.count { it is AgentEvent.Done })
    }

    fun testStuckGuardNudgesThenAborts() {
        // The model repeats the identical call; an unknown tool returns the same error result each time,
        // so the loop must nudge once and then abort as stuck rather than spin forever.
        val repeat = FakeLlmClient.toolUse("t1", "__no_such_tool__", "{}")
        val events = runLoop(FakeLlmClient(listOf(repeat, repeat, repeat, FakeLlmClient.endTurn("x"))))
        assertTrue(
            "aborts as stuck after a nudge",
            events.any { it is AgentEvent.Error && it.text.contains("stuck", ignoreCase = true) },
        )
    }
}
