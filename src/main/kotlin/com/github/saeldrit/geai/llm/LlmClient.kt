package com.github.saeldrit.geai.llm

import com.intellij.openapi.progress.ProgressIndicator

/** A single, synchronous (blocking) chat round-trip. Implementations must honor cancellation
 * by polling [indicator] and aborting the in-flight HTTP request.
 *
 * The [chatStream] variant returns a [ChatResult] and emits real-time [StreamEvent]s so the
 * agent loop can show text/thinking as it arrives instead of waiting for the full response.
 *
 * Kept deliberately non-streaming for v1: correct tool-call parsing matters far more than
 * token-by-token rendering for an autonomous debugging loop. Streaming can layer on later
 * behind the same interface.
 */
interface LlmClient {
    fun chat(request: ChatRequest, indicator: ProgressIndicator): ChatResult

    /**
     * Streaming variant: sends the request, calls [onEvent] for each stream event (text deltas,
     * tool_use starts, thinking), and returns the fully assembled [ChatResult].
     * Default implementation delegates to [chat], so clients that don't support streaming still work.
     */
    fun chatStream(
        request: ChatRequest,
        indicator: ProgressIndicator,
        onEvent: (StreamEvent) -> Unit,
    ): ChatResult = chat(request, indicator)

    /**
     * Fetch the list of available models from the provider's `/v1/models` endpoint.
     * Returns null if the provider does not support this (e.g. native Anthropic API).
     */
    fun listModels(indicator: ProgressIndicator): List<String>? = null
}

/** Events emitted during a streaming response. */
sealed interface StreamEvent {
    data class TextDelta(val text: String) : StreamEvent

    data class ThinkingDelta(val text: String) : StreamEvent

    /** The model started a tool_use block (id + name known, input still streaming). */
    data class ToolUseStarted(val id: String, val name: String) : StreamEvent

    data class ToolUseInputDelta(val id: String, val partialJson: String) : StreamEvent

    /**
     * A tool_use block finished streaming — [inputJson] is COMPLETE and executable. Emitted as
     * soon as the provider closes the block, typically while the rest of the response is still
     * streaming: the agent loop uses this to start read-only tools early (streaming execution),
     * overlapping tool I/O with the remainder of the model's output.
     */
    data class ToolUseCompleted(val id: String, val name: String, val inputJson: String) : StreamEvent

    /** Streaming is finished — the full response is available as the returned ChatResult. */
    data object Done : StreamEvent
}

class LlmException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
