package com.github.saeldrit.geai.cost

import com.github.saeldrit.geai.llm.TokenUsage

/** Per-million-token USD rates for one model. cacheRead/cacheWrite default to 0 when unspecified. */
data class ModelRate(val input: Double, val output: Double, val cacheRead: Double, val cacheWrite: Double)

/**
 * Token→cost from a user-maintained rate table, keyed by the EXACT model name. Cost is always
 * computed with the rate of the model that actually produced the tokens, so a cheap model is never
 * priced as an expensive one. No matching rate → no cost (tokens only), never a fabricated figure.
 * Hardcoding prices is deliberately avoided: they go stale; the user keeps this table current.
 */
object Pricing {

    /**
     * Parse lines `model=input,output[,cacheRead[,cacheWrite]]` (USD per 1M tokens). Blank lines and
     * `#` comments are ignored. Malformed lines are skipped rather than failing the whole table.
     */
    fun parse(text: String?): Map<String, ModelRate> {
        if (text.isNullOrBlank()) return emptyMap()
        val rates = LinkedHashMap<String, ModelRate>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val eq = line.indexOf('=')
            if (eq <= 0) return@forEach
            val model = line.substring(0, eq).trim()
            val nums = line.substring(eq + 1).split(',').map { it.trim().toDoubleOrNull() }
            if (model.isEmpty() || nums.size < 2 || nums[0] == null || nums[1] == null) return@forEach
            rates[model] = ModelRate(
                input = nums[0]!!,
                output = nums[1]!!,
                cacheRead = nums.getOrNull(2) ?: 0.0,
                cacheWrite = nums.getOrNull(3) ?: 0.0,
            )
        }
        return rates
    }

    /** Exact match first, then a lenient prefix match (handles dated model ids). */
    fun rateFor(rates: Map<String, ModelRate>, model: String): ModelRate? =
        rates[model] ?: rates.entries.firstOrNull { model.startsWith(it.key) || it.key.startsWith(model) }?.value

    fun costUsd(usage: TokenUsage, rate: ModelRate): Double =
        (usage.inputTokens * rate.input +
            usage.outputTokens * rate.output +
            usage.cacheReadTokens * rate.cacheRead +
            usage.cacheWriteTokens * rate.cacheWrite) / 1_000_000.0
}
