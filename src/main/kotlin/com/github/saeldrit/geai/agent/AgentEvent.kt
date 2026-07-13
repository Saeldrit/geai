package com.github.saeldrit.geai.agent

import com.github.saeldrit.geai.llm.TokenUsage
import com.github.saeldrit.geai.tools.ToolResult

data class Attachment(
    val name: String,
    val mediaType: String,
    val base64Data: String,
) {
    val isImage: Boolean get() = mediaType.startsWith("image/")
}

/** Events emitted by [AgentLoop] as a turn progresses. Delivered on the agent's worker thread. */
sealed interface AgentEvent {
    data class UserMessage(val text: String, val attachments: List<Attachment> = emptyList()) : AgentEvent
    data object Thinking : AgentEvent

    /** The model's internal reasoning. Rendered collapsed (hidden behind a toggle), never inline. */
    data class Reasoning(val text: String) : AgentEvent

    /** A streamed CHUNK of the model's thinking — appended live to the collapsible reasoning block. */
    data class ReasoningDelta(val text: String) : AgentEvent

    /** The assistant's complete reply for one step. When streaming, this arrives AFTER the deltas and
     *  re-renders the live bubble as markdown; it is also the single block used on replay / non-stream. */
    data class AssistantText(val text: String) : AgentEvent

    /** A streamed CHUNK of the assistant's reply — appended live to the SAME bubble (not a new one per
     *  token). The trailing [AssistantText] then re-renders that bubble as markdown. */
    data class AssistantTextDelta(val text: String) : AgentEvent
    /** [id] is the tool_use id — a stable key so the UI can match start↔finish even when several calls
     *  share a tool NAME in one turn (parallel reads). Null from the CLI engine, which keys by name. */
    data class ToolStarted(val tool: String, val argsJson: String, val id: String? = null) : AgentEvent
    data class ToolFinished(val tool: String, val result: ToolResult, val id: String? = null) : AgentEvent
    data class Info(val text: String) : AgentEvent
    data class Error(val text: String) : AgentEvent
    data class Cancelled(val text: String = "Stopped.") : AgentEvent
    data class Done(val usage: TokenUsage) : AgentEvent
}

/** Sink for [AgentEvent]s. Implementations must marshal to the EDT before touching Swing. */
fun interface AgentListener {
    fun onEvent(event: AgentEvent)
}
