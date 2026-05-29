package com.github.saeldrit.geai.llm

import com.intellij.openapi.progress.ProgressIndicator

/**
 * A single, synchronous (blocking) chat round-trip. Implementations must honor cancellation
 * by polling [indicator] and aborting the in-flight HTTP request.
 *
 * Kept deliberately non-streaming for v1: correct tool-call parsing matters far more than
 * token-by-token rendering for an autonomous debugging loop. Streaming can layer on later
 * behind the same interface.
 */
interface LlmClient {
    fun chat(request: ChatRequest, indicator: ProgressIndicator): ChatResult
}

/** Raised for transport, auth, rate-limit, or protocol failures from a provider. */
class LlmException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
