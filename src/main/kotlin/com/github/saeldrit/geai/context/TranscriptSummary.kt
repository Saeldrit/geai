package com.github.saeldrit.geai.context

import com.github.saeldrit.geai.llm.ChatMessage
import com.github.saeldrit.geai.llm.ChatRequest
import com.github.saeldrit.geai.llm.LlmClient
import com.github.saeldrit.geai.llm.TokenUsage
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator

object TranscriptSummary {

    private const val MAX_TOKENS = 2000

    val DOCTRINE: String = SemanticCompressor.DOCTRINE

    fun summarizer(
        client: LlmClient,
        model: String,
        indicator: ProgressIndicator,
        bill: (TokenUsage) -> Unit,
    ): ContextCompressor.Summarizer = ContextCompressor.Summarizer { segment ->
        runCatching {
            val result = client.chat(
                ChatRequest(
                    model = model,
                    system = DOCTRINE,
                    messages = listOf(ChatMessage.user(segment)),
                    tools = emptyList(),
                    maxTokens = MAX_TOKENS,
                ),
                indicator,
            )
            bill(result.usage)
            result.message.text
        }.getOrElse { thisLogger().warn("Geai /compact summary failed", it); "" }
    }
}
