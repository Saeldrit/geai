package com.github.saeldrit.geai.llm

/** A complete request to the model. Immutable. */
data class ChatRequest(
    val model: String,
    val system: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val maxTokens: Int = 8192,
    val temperature: Double = 0.0,
)

enum class StopReason {
    /** Model finished its turn with a final answer. */
    END_TURN,

    /** Model wants one or more tools executed before continuing. */
    TOOL_USE,

    /** Output truncated by the token cap. */
    MAX_TOKENS,

    OTHER,
}

data class TokenUsage(val inputTokens: Int, val outputTokens: Int) {
    operator fun plus(other: TokenUsage): TokenUsage =
        TokenUsage(inputTokens + other.inputTokens, outputTokens + other.outputTokens)

    companion object {
        val ZERO = TokenUsage(0, 0)
    }
}

/** The model's reply for one [ChatRequest]. */
data class ChatResult(
    val message: ChatMessage,
    val stopReason: StopReason,
    val usage: TokenUsage,
)
