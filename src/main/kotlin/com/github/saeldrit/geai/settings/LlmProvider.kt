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
    /** Suggestions for the model dropdown; the field stays editable for custom endpoints. */
    val suggestedModels: List<String>,
    /** Whether ALL models under this provider natively support image/vision input. */
    val supportsVision: Boolean,
) {
    ANTHROPIC(
        displayName = "Anthropic (Claude)",
        defaultBaseUrl = "https://api.anthropic.com",
        defaultModel = "claude-sonnet-4-6",
        supportsVision = true,
        suggestedModels = listOf(
            "claude-sonnet-4-6",
            "claude-opus-4-8",
            "claude-haiku-4-5-20251001",
        ),
    ),
    OPENAI_COMPATIBLE(
        displayName = "OpenAI-compatible (DeepSeek / Qwen / local)",
        defaultBaseUrl = "https://api.deepseek.com",
        defaultModel = "deepseek-chat",
        supportsVision = false, // mixed — gpt-4o yes, deepseek no; conservative default
        suggestedModels = listOf(
            "deepseek-chat",
            "deepseek-reasoner",
            "qwen-plus",
            "qwen-max",
            "qwen3-coder-plus",
            "gpt-4o",
            "gpt-4o-mini",
        ),
    ),
    OPENROUTER(
        displayName = "OpenRouter (one key, many models)",
        defaultBaseUrl = "https://openrouter.ai/api/v1",
        defaultModel = "anthropic/claude-sonnet-4",
        supportsVision = false, // depends on the selected model
        suggestedModels = listOf(
            "anthropic/claude-sonnet-4",
            "anthropic/claude-3.5-sonnet",
            "deepseek/deepseek-chat",
            "qwen/qwen-2.5-coder-32b-instruct",
            "google/gemini-2.0-flash-001",
            "openai/gpt-4o-mini",
        ),
    ),
    XIAOMI(
        displayName = "Xiaomi MiMo",
        defaultBaseUrl = "https://api.xiaomimimo.com",
        defaultModel = "MiMo-V2.5-Pro",
        supportsVision = false,
        suggestedModels = listOf(
            "MiMo-V2.5-Pro",
            "MiMo-V2.5",
            "MiMo-V2-Flash",
            "MiMo-V2.5-DFlash",
            "MiMo-V2.5-Pro-FP4-DFlash",
        ),
    );

    companion object {
        /**
         * Known vision-capable model-id prefixes (case-insensitive).
         * Checked against the raw model string the user typed, covering both direct API
         * names (`claude-sonnet-4-6`) and OpenRouter names (`anthropic/claude-sonnet-4`).
         */
        private val VISION_MODEL_PREFIXES = listOf(
            "claude-",           // all Anthropic Claude models
            "anthropic/claude-",
            "gpt-4o",            // OpenAI GPT-4o / GPT-4o-mini
            "openai/gpt-4o",
            "google/gemini",     // Google Gemini
            "mistralai/pixtral", // Mistral Pixtral (vision)
            "qwen-vl",           // Alibaba Qwen-VL family
        )

        /** Whether [modelId] is a known vision-capable model, regardless of provider. */
        fun modelSupportsVision(modelId: String): Boolean {
            val lower = modelId.lowercase()
            return VISION_MODEL_PREFIXES.any { lower.startsWith(it) }
        }
    }
}
