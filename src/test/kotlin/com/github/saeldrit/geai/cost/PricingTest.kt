package com.github.saeldrit.geai.cost

import com.github.saeldrit.geai.llm.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PricingTest {

    private val table = """
        # comment
        claude-opus-4-8=15,75,1.5,18.75
        deepseek-chat=0.27,1.10
    """.trimIndent()

    @Test
    fun `parses rates and ignores comments and bad lines`() {
        val rates = Pricing.parse(table + "\ngarbage-line\nonlyname=")
        assertEquals(2, rates.size)
        assertEquals(75.0, rates["claude-opus-4-8"]!!.output, 0.0)
        assertEquals(0.0, rates["deepseek-chat"]!!.cacheWrite, 0.0)
    }

    @Test
    fun `each model is costed at its own rate`() {
        val rates = Pricing.parse(table)
        val usage = TokenUsage(inputTokens = 1_000_000, outputTokens = 1_000_000)
        val claude = Pricing.costUsd(usage, Pricing.rateFor(rates, "claude-opus-4-8")!!)
        val deepseek = Pricing.costUsd(usage, Pricing.rateFor(rates, "deepseek-chat")!!)
        assertEquals(90.0, claude, 1e-9)      // 15 + 75
        assertEquals(1.37, deepseek, 1e-9)    // 0.27 + 1.10
        assertTrue("deepseek must be far cheaper than claude", deepseek < claude)
    }

    @Test
    fun `cache read is discounted and unknown model has no rate`() {
        val rates = Pricing.parse(table)
        val cached = TokenUsage(inputTokens = 0, outputTokens = 0, cacheReadTokens = 1_000_000)
        assertEquals(1.5, Pricing.costUsd(cached, rates["claude-opus-4-8"]!!), 1e-9)
        assertNull("unknown model → no rate, no fabricated cost", Pricing.rateFor(rates, "gpt-4o"))
    }
}
