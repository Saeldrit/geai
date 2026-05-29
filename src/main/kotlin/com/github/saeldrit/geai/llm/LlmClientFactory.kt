package com.github.saeldrit.geai.llm

import com.github.saeldrit.geai.llm.anthropic.AnthropicClient
import com.github.saeldrit.geai.llm.openai.OpenAiCompatibleClient
import com.github.saeldrit.geai.settings.GeaiSecrets
import com.github.saeldrit.geai.settings.GeaiSettings
import com.github.saeldrit.geai.settings.LlmProvider
import com.github.saeldrit.geai.settings.effectiveBaseUrl

/** Resolves the configured provider/key/URL into a concrete [LlmClient]. */
object LlmClientFactory {

    fun create(): LlmClient {
        val state = GeaiSettings.getInstance().state
        val provider = state.provider
        val apiKey = GeaiSecrets.apiKey(provider)
            ?: throw LlmException(
                "No API key configured for ${provider.displayName}. Add one in Settings | Tools | Geai.",
            )
        val baseUrl = state.effectiveBaseUrl()
        return when (provider) {
            LlmProvider.ANTHROPIC -> AnthropicClient(baseUrl, apiKey)
            LlmProvider.OPENAI_COMPATIBLE -> OpenAiCompatibleClient(baseUrl, apiKey)
        }
    }

    fun isConfigured(): Boolean =
        GeaiSecrets.hasApiKey(GeaiSettings.getInstance().state.provider)
}
