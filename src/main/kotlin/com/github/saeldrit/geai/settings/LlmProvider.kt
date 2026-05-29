package com.github.saeldrit.geai.settings

/**
 * Supported LLM back-ends.
 *
 * [ANTHROPIC] talks the native Claude Messages API. [OPENAI_COMPATIBLE] targets any
 * server speaking the OpenAI Chat Completions dialect — this covers DeepSeek
 * (`api.deepseek.com`), Alibaba Qwen / DashScope (`dashscope.aliyun.com/compatible-mode/v1`),
 * OpenRouter, and locally hosted models (Ollama, LM Studio, vLLM).
 */
enum class LlmProvider(
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
) {
    ANTHROPIC(
        displayName = "Anthropic (Claude)",
        defaultBaseUrl = "https://api.anthropic.com",
        defaultModel = "claude-sonnet-4-6",
    ),
    OPENAI_COMPATIBLE(
        displayName = "OpenAI-compatible (DeepSeek / Qwen / local)",
        defaultBaseUrl = "https://api.deepseek.com",
        defaultModel = "deepseek-chat",
    ),
}
