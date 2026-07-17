package com.github.saeldrit.geai.llm

data class ChatRequest(
    val model: String,
    /**
     * The STABLE system prefix — must be byte-identical across every turn of a session for the
     * provider's prompt cache to hit. Anything that changes per turn (the GRACE context bundle, the
     * scratchpad notes, a per-command mode directive) must NOT live here: caching is a prefix match
     * over `tools → system → messages`, so a byte change in `system` invalidates the ENTIRE message
     * history cache below it. Volatile context is delivered as trailing user message(s) instead —
     * after the rolling cache breakpoint — where it is reprocessed cheaply without busting the cache.
     */
    val system: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val maxTokens: Int = 65536,
    val temperature: Double = 0.0,
)

enum class StopReason {
    END_TURN,

    TOOL_USE,

    MAX_TOKENS,

    OTHER,
}

/**
 * Token accounting for one response. [inputTokens]/[outputTokens] are the billed non-cached
 * input and the output; [cacheReadTokens]/[cacheWriteTokens] are reported separately by providers
 * that support prompt caching (Anthropic cache read/creation, DeepSeek prompt-cache hit) so cost
 * can reflect the real cache discount — never an invented one.
 */
data class TokenUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheReadTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
) {
    operator fun plus(other: TokenUsage): TokenUsage =
        TokenUsage(
            inputTokens + other.inputTokens,
            outputTokens + other.outputTokens,
            cacheReadTokens + other.cacheReadTokens,
            cacheWriteTokens + other.cacheWriteTokens,
        )

    companion object {
        val ZERO = TokenUsage(0, 0)
    }
}

data class ChatResult(
    val message: ChatMessage,
    val stopReason: StopReason,
    val usage: TokenUsage,
)
