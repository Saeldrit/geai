package com.github.saeldrit.geai.cost

import com.github.saeldrit.geai.llm.TokenUsage
import java.util.Locale

object UsageFormat {

    fun line(label: String, model: String, usage: TokenUsage, ratesText: String?): String {
        val cache = if (usage.cacheReadTokens > 0 || usage.cacheWriteTokens > 0) {
            " (cache: read ${usage.cacheReadTokens}, write ${usage.cacheWriteTokens})"
        } else {
            ""
        }
        val cost = Pricing.rateFor(Pricing.parse(ratesText), model)
            ?.let { " · ~$" + String.format(Locale.US, "%.4f", Pricing.costUsd(usage, it)) }
            ?: ""
        return "$label [$model]: ↑${usage.inputTokens} ↓${usage.outputTokens}$cache$cost"
    }

    fun summary(model: String, turn: TokenUsage, session: TokenUsage, ratesText: String?): String =
        "📊 " + line("turn", model, turn, ratesText) + "\n   " + line("session", model, session, ratesText)
}
