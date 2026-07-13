package com.github.saeldrit.geai.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptWindowTest {

    private fun state(p: LlmProvider, window: Int, cap: Int, maxOut: Int = 8_192, reserve: Int = 16_384) =
        GeaiSettingsState().apply {
            provider = p
            maxContextTokens = window
            maxTranscriptTokens = cap
            maxTokens = maxOut
            outputReserveTokens = reserve
        }

    @Test
    fun `default soft cap of 0 uses the full model window`() {
        assertEquals(128_000, state(LlmProvider.ANTHROPIC, window = 128_000, cap = 0).transcriptWindow())
    }

    @Test
    fun `positive soft cap shrinks the working window`() {
        assertEquals(32_000, state(LlmProvider.ANTHROPIC, window = 200_000, cap = 32_000).transcriptWindow())
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

    @Test
    fun `output reserve covers a huge maxTokens ceiling`() {
        val s = state(LlmProvider.ANTHROPIC, window = 200_000, cap = 0, maxOut = 65_536, reserve = 16_384)
        assertEquals(
            "reserve must grow with maxTokens or the provider rejects near-full prompts",
            65_536,
            s.effectiveOutputReserve(),
        )
    }

    @Test
    fun `small maxTokens keeps the configured reserve floor`() {
        val s = state(LlmProvider.ANTHROPIC, window = 200_000, cap = 0, maxOut = 4_096, reserve = 16_384)
        assertEquals(16_384, s.effectiveOutputReserve())
    }
}
