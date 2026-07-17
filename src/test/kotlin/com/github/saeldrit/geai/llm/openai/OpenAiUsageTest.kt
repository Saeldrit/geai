package com.github.saeldrit.geai.llm.openai

import com.github.saeldrit.geai.cost.ModelRate
import com.github.saeldrit.geai.cost.Pricing
import com.github.saeldrit.geai.llm.http.JsonSupport
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * OpenAI-shaped `prompt_tokens` is the TOTAL prompt (cached + uncached). The client must report
 * [inputTokens] as the FRESH remainder only — matching the Anthropic client — so cost isn't
 * double-charged and the compaction calibration isn't fooled into thinking every token was billed.
 */
class OpenAiUsageTest {

    private val client = OpenAiCompatibleClient(baseUrl = "https://api.deepseek.com", apiKey = "k")

    private fun usage(json: String) = client.parseUsage(JsonSupport.parseObject(json))

    @Test
    fun `openai cached_tokens are split out of prompt_tokens`() {
        val u = usage("""{"prompt_tokens":1000,"completion_tokens":50,"prompt_tokens_details":{"cached_tokens":800}}""")
        assertEquals("fresh = total - cached", 200, u.inputTokens)
        assertEquals(800, u.cacheReadTokens)
        assertEquals(50, u.outputTokens)
    }

    @Test
    fun `deepseek prompt_cache_hit_tokens are split out of prompt_tokens`() {
        val u = usage("""{"prompt_tokens":1000,"completion_tokens":10,"prompt_cache_hit_tokens":600}""")
        assertEquals(400, u.inputTokens)
        assertEquals(600, u.cacheReadTokens)
    }

    @Test
    fun `no cache reported means all input is fresh`() {
        val u = usage("""{"prompt_tokens":500,"completion_tokens":20}""")
        assertEquals(500, u.inputTokens)
        assertEquals(0, u.cacheReadTokens)
    }

    @Test
    fun `cached is clamped to prompt_tokens against bad provider data`() {
        val u = usage("""{"prompt_tokens":100,"completion_tokens":5,"prompt_tokens_details":{"cached_tokens":999}}""")
        assertEquals("fresh never goes negative", 0, u.inputTokens)
        assertEquals(100, u.cacheReadTokens)
    }

    @Test
    fun `cost is not double-charged for cached tokens`() {
        val u = usage("""{"prompt_tokens":1000000,"completion_tokens":0,"prompt_tokens_details":{"cached_tokens":800000}}""")
        // 200k fresh @ $15 + 800k cacheRead @ $1.5 = $3.00 + $1.20 = $4.20 — NOT 1M @ $15.
        val rate = ModelRate(input = 15.0, output = 75.0, cacheRead = 1.5, cacheWrite = 18.75)
        assertEquals(4.20, Pricing.costUsd(u, rate), 1e-9)
    }
}
