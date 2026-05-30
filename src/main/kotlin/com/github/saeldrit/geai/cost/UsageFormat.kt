package com.github.saeldrit.geai.cost

import com.github.saeldrit.geai.llm.TokenUsage
import java.util.Locale

/** Renders token/cache/cost lines for the chat, attributing tokens to the model that produced them. */
object UsageFormat {

    /** One line, e.g. `author [claude-opus-4-8]: ↑1200 ↓340 (cache: read 800, write 0) · ~$0.0291`. */
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

    /** Turn delta plus running session total, for the loop (navigator) model. */
    fun summary(model: String, turn: TokenUsage, session: TokenUsage, ratesText: String?): String =
        "📊 " + line("turn", model, turn, ratesText) + "\n   " + line("session", model, session, ratesText)
}
