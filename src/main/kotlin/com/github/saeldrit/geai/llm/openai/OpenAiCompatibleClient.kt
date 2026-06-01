package com.github.saeldrit.geai.llm.openai

import com.github.saeldrit.geai.llm.ChatMessage
import com.github.saeldrit.geai.llm.ChatRequest
import com.github.saeldrit.geai.llm.ChatResult
import com.github.saeldrit.geai.llm.ContentBlock
import com.github.saeldrit.geai.llm.LlmClient
import com.github.saeldrit.geai.llm.LlmException
import com.github.saeldrit.geai.llm.Role
import com.github.saeldrit.geai.llm.StopReason
import com.github.saeldrit.geai.llm.TokenUsage
import com.github.saeldrit.geai.llm.http.HttpTransport
import com.github.saeldrit.geai.llm.http.JsonSupport
import com.github.saeldrit.geai.llm.http.arrayOrEmpty
import com.github.saeldrit.geai.llm.http.intOr
import com.github.saeldrit.geai.llm.http.objectOrNull
import com.github.saeldrit.geai.llm.http.stringOrNull
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.progress.ProgressIndicator

/**
 * Client for any OpenAI Chat-Completions-compatible endpoint (`POST /v1/chat/completions`):
 * DeepSeek, Alibaba Qwen / DashScope compatible-mode, OpenRouter, Ollama, LM Studio, vLLM.
 * Tool calls are expressed via the `tools` / `tool_calls` function-calling convention.
 */
class OpenAiCompatibleClient(
    private val baseUrl: String,
    private val apiKey: String,
) : LlmClient {

    override fun chat(request: ChatRequest, indicator: ProgressIndicator): ChatResult {
        val raw = HttpTransport.postJson(
            url = chatCompletionsUrl(baseUrl),
            headers = mapOf("Authorization" to "Bearer $apiKey"),
            body = JsonSupport.gson.toJson(buildRequestBody(request)),
            indicator = indicator,
        )
        return parseResponse(raw)
    }

    /** Tolerate base URLs given either with or without a trailing `/v1`. */
    private fun chatCompletionsUrl(base: String): String {
        val trimmed = base.trimEnd('/')
        return if (trimmed.endsWith("/v1")) "$trimmed/chat/completions" else "$trimmed/v1/chat/completions"
    }

    private fun buildRequestBody(request: ChatRequest): JsonObject {
        val root = JsonObject().apply {
            addProperty("model", request.model)
            addProperty("max_tokens", request.maxTokens)
            addProperty("temperature", request.temperature)
        }
        val messages = JsonArray()
        // No prompt caching here, so the volatile suffix is simply folded into the system message.
        val systemText = listOf(request.system, request.systemVolatileSuffix)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        if (systemText.isNotBlank()) {
            messages.add(JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", systemText)
            })
        }
        request.messages.forEach { message -> appendWireMessages(messages, message) }
        root.add("messages", messages)

        if (request.tools.isNotEmpty()) {
            val tools = JsonArray()
            request.tools.forEach { spec ->
                tools.add(JsonObject().apply {
                    addProperty("type", "function")
                    add("function", JsonObject().apply {
                        addProperty("name", spec.name)
                        addProperty("description", spec.description)
                        add("parameters", JsonSupport.parseElement(spec.parametersJsonSchema))
                    })
                })
            }
            root.add("tools", tools)
            root.addProperty("tool_choice", "auto")
        }
        return root
    }

    private fun appendWireMessages(target: JsonArray, message: ChatMessage) {
        when (message.role) {
            Role.SYSTEM -> target.add(simpleMessage("system", message.text))
            Role.USER -> target.add(simpleMessage("user", message.text))
            Role.ASSISTANT -> target.add(assistantMessage(message))
            Role.TOOL -> message.content.filterIsInstance<ContentBlock.ToolResult>().forEach { result ->
                target.add(JsonObject().apply {
                    addProperty("role", "tool")
                    addProperty("tool_call_id", result.toolUseId)
                    addProperty("content", result.content)
                })
            }
        }
    }

    private fun simpleMessage(role: String, content: String): JsonObject = JsonObject().apply {
        addProperty("role", role)
        addProperty("content", content)
    }

    private fun assistantMessage(message: ChatMessage): JsonObject = JsonObject().apply {
        addProperty("role", "assistant")
        addProperty("content", message.text)
        val toolUses = message.toolUses
        if (toolUses.isNotEmpty()) {
            val calls = JsonArray()
            toolUses.forEach { toolUse ->
                calls.add(JsonObject().apply {
                    addProperty("id", toolUse.id)
                    addProperty("type", "function")
                    add("function", JsonObject().apply {
                        addProperty("name", toolUse.name)
                        addProperty("arguments", toolUse.inputJson.ifBlank { "{}" })
                    })
                })
            }
            add("tool_calls", calls)
        }
    }

    private fun parseResponse(raw: String): ChatResult {
        val root = JsonSupport.parseObject(raw)
        val choices = root.arrayOrEmpty("choices")
        if (choices.size() == 0) throw LlmException("Empty 'choices' in response: ${raw.take(1000)}")
        val choice = choices[0].asJsonObject
        val message = choice.objectOrNull("message")
            ?: throw LlmException("Missing 'message' in response: ${raw.take(1000)}")

        val blocks = mutableListOf<ContentBlock>()
        message.stringOrNull("content")?.takeIf { it.isNotEmpty() }?.let { blocks.add(ContentBlock.Text(it)) }
        message.get("tool_calls")?.takeIf { it.isJsonArray }?.asJsonArray?.forEachIndexed { index, element ->
            val call = element.asJsonObject
            val function = call.objectOrNull("function") ?: return@forEachIndexed
            blocks.add(
                ContentBlock.ToolUse(
                    // Index keeps the id unique when a model omits ids for multiple parallel calls,
                    // so each tool_result maps back to the right call.
                    id = call.stringOrNull("id") ?: "call_${index}_${function.stringOrNull("name").orEmpty()}",
                    name = function.stringOrNull("name").orEmpty(),
                    inputJson = function.stringOrNull("arguments")?.ifBlank { "{}" } ?: "{}",
                )
            )
        }
        if (blocks.isEmpty()) blocks.add(ContentBlock.Text(""))

        val hasToolUse = blocks.any { it is ContentBlock.ToolUse }
        val stopReason = when (choice.stringOrNull("finish_reason")) {
            "tool_calls" -> StopReason.TOOL_USE
            "stop" -> StopReason.END_TURN
            "length" -> StopReason.MAX_TOKENS
            else -> if (hasToolUse) StopReason.TOOL_USE else StopReason.OTHER
        }
        val usage = root.objectOrNull("usage")
            ?.let { usageObj ->
                // Cache hits are reported differently per vendor: DeepSeek uses prompt_cache_hit_tokens;
                // OpenAI uses prompt_tokens_details.cached_tokens. Prefer whichever is present.
                val deepseekHit = usageObj.intOr("prompt_cache_hit_tokens", 0)
                val openAiCached = usageObj.objectOrNull("prompt_tokens_details")?.intOr("cached_tokens", 0) ?: 0
                TokenUsage(
                    inputTokens = usageObj.intOr("prompt_tokens", 0),
                    outputTokens = usageObj.intOr("completion_tokens", 0),
                    cacheReadTokens = maxOf(deepseekHit, openAiCached),
                )
            }
            ?: TokenUsage.ZERO

        return ChatResult(ChatMessage.assistant(blocks), stopReason, usage)
    }
}
