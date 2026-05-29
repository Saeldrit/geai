package com.github.saeldrit.geai.agent

import com.github.saeldrit.geai.llm.TokenUsage
import com.github.saeldrit.geai.tools.ToolResult

/** Events emitted by [AgentLoop] as a turn progresses. Delivered on the agent's worker thread. */
sealed interface AgentEvent {
    data class UserMessage(val text: String) : AgentEvent
    data object Thinking : AgentEvent
    data class AssistantText(val text: String) : AgentEvent
    data class ToolStarted(val tool: String, val argsJson: String) : AgentEvent
    data class ToolFinished(val tool: String, val result: ToolResult) : AgentEvent
    data class Info(val text: String) : AgentEvent
    data class Error(val text: String) : AgentEvent
    data class Cancelled(val text: String = "Stopped.") : AgentEvent
    data class Done(val usage: TokenUsage) : AgentEvent
}

/** Sink for [AgentEvent]s. Implementations must marshal to the EDT before touching Swing. */
fun interface AgentListener {
    fun onEvent(event: AgentEvent)
}
