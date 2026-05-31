package com.github.saeldrit.geai.agent

import com.github.saeldrit.geai.bundle.ContextBundler
import com.github.saeldrit.geai.context.ContextCompressor
import com.github.saeldrit.geai.cost.UsageFormat
import com.github.saeldrit.geai.llm.ChatMessage
import com.github.saeldrit.geai.llm.ChatRequest
import com.github.saeldrit.geai.llm.ContentBlock
import com.github.saeldrit.geai.llm.LlmClientFactory
import com.github.saeldrit.geai.llm.LlmException
import com.github.saeldrit.geai.llm.StopReason
import com.github.saeldrit.geai.llm.TokenUsage
import com.github.saeldrit.geai.settings.GeaiSettingsState
import com.github.saeldrit.geai.settings.GeaiSettings
import com.github.saeldrit.geai.settings.loopModel
import com.github.saeldrit.geai.tools.ToolArgException
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolRegistry
import com.github.saeldrit.geai.tools.ToolResult
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project

/**
 * The agentic control loop: send the transcript to the model, run any requested tools, append the
 * results, and repeat until the model answers with no further tool calls (or limits/cancellation).
 * Runs on a background worker; emits [AgentEvent]s and never touches Swing directly.
 */
class AgentLoop(
    private val project: Project,
    private val registry: ToolRegistry,
) {

    fun run(session: AgentSession, userText: String, listener: AgentListener, indicator: ProgressIndicator) {
        session.messages.add(ChatMessage.user(userText))
        listener.onEvent(AgentEvent.UserMessage(userText))

        val client = try {
            LlmClientFactory.create()
        } catch (e: LlmException) {
            listener.onEvent(AgentEvent.Error(e.message ?: "Geai is not configured."))
            return
        }

        val settings = GeaiSettings.getInstance().state
        val baseSystemPrompt = SystemPrompt.build(project)
        val systemPrompt = if (settings.graceEnabled) {
            // Auto-inject context bundle: engine calls context_bundle before first LLM call,
            // prepends atoms to system prompt. Model sees ready context immediately, cannot ignore.
            val bundle = try {
                ContextBundler.build(project, userText, emptyList(), maxNodes = 24, hops = 2)
            } catch (e: Exception) {
                null
            }
            if (bundle != null && bundle.text.isNotBlank()) {
                "$baseSystemPrompt\n\n<context_bundle>\n${bundle.text}\n</context_bundle>"
            } else {
                baseSystemPrompt
            }
        } else {
            baseSystemPrompt
        }
        val maxIterations = settings.maxAgentIterations.coerceAtLeast(1)

        try {
            var iteration = 0
            var turnUsage = TokenUsage.ZERO
            while (true) {
                if (indicator.isCanceled) {
                    listener.onEvent(AgentEvent.Cancelled())
                    return
                }
                if (iteration++ >= maxIterations) {
                    listener.onEvent(AgentEvent.Info("Reached the max iteration budget ($maxIterations). Stopping; ask me to continue if needed."))
                    listener.onEvent(AgentEvent.Info(UsageFormat.summary(settings.loopModel(), turnUsage, session.totalUsage, settings.modelPrices)))
                    listener.onEvent(AgentEvent.Done(session.totalUsage))
                    return
                }

                val outgoing = ContextCompressor.compress(session.messages, settings.maxContextTokens, settings.maxTokens)
                val request = ChatRequest(
                    model = settings.loopModel(),
                    system = systemPrompt,
                    messages = outgoing,
                    tools = registry.specs(),
                    maxTokens = settings.maxTokens,
                )

                listener.onEvent(AgentEvent.Thinking)
                val result = client.chat(request, indicator)
                session.totalUsage += result.usage
                turnUsage += result.usage
                session.messages.add(result.message)

                result.message.text.takeIf { it.isNotBlank() }?.let { listener.onEvent(AgentEvent.AssistantText(it)) }

                val toolUses = result.message.toolUses
                if (toolUses.isEmpty()) {
                    if (result.stopReason == StopReason.MAX_TOKENS) {
                        listener.onEvent(AgentEvent.Info("Response was truncated by the token limit; raise Max tokens in Settings | Tools | Geai."))
                    }
                    listener.onEvent(AgentEvent.Info(UsageFormat.summary(settings.loopModel(), turnUsage, session.totalUsage, settings.modelPrices)))
                    listener.onEvent(AgentEvent.Done(session.totalUsage))
                    return
                }

                // Every tool_use MUST get a matching tool_result, even on cancellation — otherwise the
                // persisted transcript is invalid and a resumed session is rejected by the provider.
                val toolResults = ArrayList<ContentBlock.ToolResult>(toolUses.size)
                var interrupted = false
                for (call in toolUses) {
                    if (interrupted || indicator.isCanceled) {
                        interrupted = true
                        toolResults.add(ContentBlock.ToolResult(call.id, "Skipped: turn was interrupted.", isError = true))
                        continue
                    }
                    listener.onEvent(AgentEvent.ToolStarted(call.name, call.inputJson))
                    val toolResult = try {
                        executeTool(call, settings, indicator)
                    } catch (_: ProcessCanceledException) {
                        interrupted = true
                        ToolResult.error("Interrupted during '${call.name}'.")
                    }
                    listener.onEvent(AgentEvent.ToolFinished(call.name, toolResult))
                    toolResults.add(ContentBlock.ToolResult(call.id, toolResult.content, toolResult.isError))
                }
                session.messages.add(ChatMessage.toolResults(toolResults))
                if (interrupted) {
                    listener.onEvent(AgentEvent.Cancelled())
                    return
                }
            }
        } catch (_: ProcessCanceledException) {
            listener.onEvent(AgentEvent.Cancelled())
        } catch (e: LlmException) {
            listener.onEvent(AgentEvent.Error(e.message ?: "LLM request failed."))
        } catch (e: Exception) {
            listener.onEvent(AgentEvent.Error("Unexpected error: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    private fun executeTool(
        call: ContentBlock.ToolUse,
        settings: GeaiSettingsState,
        indicator: ProgressIndicator,
    ): ToolResult {
        val tool = registry.find(call.name)
            ?: return ToolResult.error(
                "Unknown tool '${call.name}'. Available: ${registry.tools.joinToString(", ") { it.name }}",
            )
        if (tool.mutating) {
            if (!ApprovalPolicy.confirm(project, tool, call.inputJson)) {
                return ToolResult.error("User denied permission to run '${tool.name}'.")
            }
        }
        return try {
            tool.execute(ToolArgs.parse(call.inputJson), ToolContext(project, indicator))
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: ToolArgException) {
            ToolResult.error("Invalid arguments for '${tool.name}': ${e.message}")
        } catch (e: Exception) {
            ToolResult.error("Tool '${tool.name}' threw ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
