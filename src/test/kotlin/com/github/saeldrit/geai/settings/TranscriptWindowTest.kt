package com.github.saeldrit.geai.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The transcript compaction budget is the active working window, applied UNIFORMLY to all providers:
 * the growing transcript is re-sent every iteration, so it is capped to maxTranscriptTokens (never
 * above the model's maxContextTokens window). Prompt caching only cheapens the stable prefix
 * (system + tools), not the transcript — so there is no provider special-case.
 */
class TranscriptWindowTest {

    private fun state(p: LlmProvider, window: Int, cap: Int) = GeaiSettingsState().apply {
        provider = p
        maxContextTokens = window
        maxTranscriptTokens = cap
    }

    @Test
    fun `anthropic is capped to the active window like every provider`() {
        assertEquals(32_000, state(LlmProvider.ANTHROPIC, window = 200_000, cap = 32_000).transcriptWindow())
    }

    @Test
    fun `providers cap the transcript to the active window`() {
        assertEquals(32_000, state(LlmProvider.OPENROUTER, window = 200_000, cap = 32_000).transcriptWindow())
        assertEquals(32_000, state(LlmProvider.OPENAI_COMPATIBLE, window = 200_000, cap = 32_000).transcriptWindow())
    }

    @Test
    fun `cap never exceeds the actual model window`() {
        assertEquals(
            "a small-window model must not be sized above its window",
            16_000,
            state(LlmProvider.OPENAI_COMPATIBLE, window = 16_000, cap = 32_000).transcriptWindow(),
        )
    }
}
