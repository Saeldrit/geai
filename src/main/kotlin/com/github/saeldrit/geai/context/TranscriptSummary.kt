package com.github.saeldrit.geai.context

import com.github.saeldrit.geai.llm.ChatMessage
import com.github.saeldrit.geai.llm.ChatRequest
import com.github.saeldrit.geai.llm.LlmClient
import com.github.saeldrit.geai.llm.TokenUsage
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator

/**
 * Builds a [ContextCompressor.Summarizer] backed by one cheap, tool-less LLM call — used by the manual
 * /compact action to fold the old transcript into a dense recap. (The agent loop has its own inline
 * equivalent; this keeps the manual path independent and reusable.)
 */
object TranscriptSummary {

    private const val MAX_TOKENS = 1500

    const val DOCTRINE =
        "You compress an AI coding agent's transcript. Output a DENSE recap that preserves everything " +
            "needed to keep working: the original task; files, symbols and APIs examined WITH their " +
            "file:line locations; concrete findings and conclusions; decisions and edits already made; and " +
            "open questions / next steps. Drop raw file contents and chatter. Terse bullet points, no preamble."

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
