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
 *
 * Prompt caching by provider:
 *  - DeepSeek / Qwen: automatic prefix-cache (no opt-in needed; works on stable system prefix).
 *  - OpenRouter: supports an OpenAI-shaped `cache_control: {"type":"ephemeral"}` extension that
 *    is forwarded to Anthropic/Gemini back-ends and gives the same 90% input discount. Detected
 *    by base URL and enabled automatically.
 *  - OpenAI proper, Ollama, vLLM: auto-cache only on the stable prefix.
 */
class OpenAiCompatibleClient(
    private val baseUrl: String,
    private val apiKey: String,
) : LlmClient {

    /** OpenRouter is the only OpenAI-compatible endpoint that honours explicit cache_control blocks. */
    private val supportsExplicitCacheControl: Boolean = baseUrl.contains("openrouter.ai", ignoreCase = true)

    override fun chat(request: ChatRequest, indicator: ProgressIndicator): ChatResult {
        val raw = HttpTransport.postJson(
            url = chatCompletionsUrl(baseUrl),
            headers = mapOf("Authorization" to "Bearer $apiKey"),
            body = JsonSupport.gson.toJson(buildRequestBody(request, streaming = false)),
            indicator = indicator,
        )
        return parseResponse(raw)
    }

    override fun chatStream(
        request: ChatRequest,
        indicator: ProgressIndicator,
        onEvent: (com.github.saeldrit.geai.llm.StreamEvent) -> Unit,
    ): ChatResult {
        val textBuilder = StringBuilder()
        val toolCallBuilders = mutableMapOf<Int, ToolCallAccumulator>()
        var finishReason: String? = null
        var usageResult: TokenUsage? = null

        val result = HttpTransport.postJsonSse(
            url = chatCompletionsUrl(baseUrl),
            headers = mapOf("Authorization" to "Bearer $apiKey"),
            body = JsonSupport.gson.toJson(buildRequestBody(request, streaming = true)),
            indicator = indicator,
        ) { sse ->
            try {
                // OpenAI sends [DONE] as the last event
                if (sse.data == "[DONE]") return@postJsonSse
                val data = JsonSupport.parseObject(sse.data)
                val choice = data.arrayOrEmpty("choices").firstOrNull()?.asJsonObject ?: return@postJsonSse
                val delta = choice.objectOrNull("delta") ?: return@postJsonSse

                delta.stringOrNull("content")?.let { chunk ->
                    if (chunk.isNotEmpty()) {
                        textBuilder.append(chunk)
                        onEvent(com.github.saeldrit.geai.llm.StreamEvent.TextDelta(chunk))
                    }
                }

                delta.get("tool_calls")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { element ->
                    val tc = element.asJsonObject
                    val index = tc.intOr("index", 0)
                    val acc = toolCallBuilders.computeIfAbsent(index) { ToolCallAccumulator() }
                    tc.stringOrNull("id")?.let { if (it.isNotEmpty()) acc.id = it }
                    tc.objectOrNull("function")?.let { fn ->
                        fn.stringOrNull("name")?.let { if (it.isNotEmpty()) acc.name = it }
                        fn.stringOrNull("arguments")?.let { if (it.isNotEmpty()) acc.arguments.append(it) }
                    }
                    if (acc.id != null && acc.name != null && !acc.started) {
                        acc.started = true
                        onEvent(com.github.saeldrit.geai.llm.StreamEvent.ToolUseStarted(acc.id!!, acc.name!!))
                    }
                }

                choice.stringOrNull("finish_reason")?.let { if (it.isNotEmpty()) finishReason = it }
                data.objectOrNull("usage")?.let { usageObj ->
                    usageResult = TokenUsage(
                        inputTokens = usageObj.intOr("prompt_tokens", 0),
                        outputTokens = usageObj.intOr("completion_tokens", 0),
                        cacheReadTokens = maxOf(
                            usageObj.intOr("prompt_cache_hit_tokens", 0),
                            usageObj.objectOrNull("prompt_tokens_details")?.intOr("cached_tokens", 0) ?: 0,
                        ),
                    )
                }
            } catch (_: Exception) {
                // Skip a malformed SSE chunk.
            }
        }

        if (result.isCancelled) throw com.intellij.openapi.progress.ProcessCanceledException()
        if (result.isError) {
            throw LlmException("Provider API error: ${result.errorBody.take(2000)}")
        }

        val blocks = mutableListOf<ContentBlock>()
        if (textBuilder.isNotEmpty()) blocks.add(ContentBlock.Text(textBuilder.toString()))
        toolCallBuilders.entries.sortedBy { it.key }.forEach { (_, acc) ->
            blocks.add(ContentBlock.ToolUse(
                id = acc.id ?: "call_${blocks.size}",
                name = acc.name ?: "tool",
                inputJson = acc.arguments.toString().ifBlank { "{}" },
            ))
        }
        if (blocks.isEmpty()) blocks.add(ContentBlock.Text(""))

        val hasToolUse = blocks.any { it is ContentBlock.ToolUse }
        val stopReason = when (finishReason) {
            "tool_calls" -> StopReason.TOOL_USE
            "stop" -> StopReason.END_TURN
            "length" -> StopReason.MAX_TOKENS
            else -> if (hasToolUse) StopReason.TOOL_USE else StopReason.OTHER
        }
        val usage = usageResult ?: TokenUsage.ZERO

        onEvent(com.github.saeldrit.geai.llm.StreamEvent.Done)
        return ChatResult(ChatMessage.assistant(blocks), stopReason, usage)
    }

    private class ToolCallAccumulator {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
        var started: Boolean = false
    }

    /** Tolerate base URLs given either with or without a trailing `/v1`. */
    private fun chatCompletionsUrl(base: String): String {
        val trimmed = base.trimEnd('/')
        return if (trimmed.endsWith("/v1")) "$trimmed/chat/completions" else "$trimmed/v1/chat/completions"
    }

    private fun buildRequestBody(request: ChatRequest, streaming: Boolean): JsonObject {
        val root = JsonObject().apply {
            addProperty("model", request.model)
            addProperty("max_tokens", request.maxTokens)
            addProperty("temperature", request.temperature)
            if (streaming) addProperty("stream", true)
        }
        val messages = JsonArray()
        appendSystem(messages, request)
        // For OpenRouter we also tag the last tool_result with cache_control so the growing
        // transcript prefix caches between iterations (mirrors the AnthropicClient strategy).
        val lastToolIndex = if (supportsExplicitCacheControl) request.messages.indexOfLast { it.role == Role.TOOL } else -1
        request.messages.forEachIndexed { index, message ->
            appendWireMessages(messages, message, withCacheBreakpoint = index == lastToolIndex)
        }
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

    /**
     * Build the system message. On OpenRouter we emit a multi-part content array so the stable
     * doctrine and per-turn bundle each get their own cache_control breakpoint — the OpenRouter
     * proxy forwards these to Anthropic/Gemini back-ends for a 90% input discount on hits.
     * Elsewhere we fold both into a single string (auto-prefix caching handles it).
     */
    private fun appendSystem(target: JsonArray, request: ChatRequest) {
        if (supportsExplicitCacheControl) {
            val parts = JsonArray()
            if (request.system.isNotBlank()) {
                parts.add(textPart(request.system, withCacheBreakpoint = true))
            }
            if (request.systemVolatileSuffix.isNotBlank()) {
                parts.add(textPart(request.systemVolatileSuffix, withCacheBreakpoint = true))
            }
            if (parts.size() > 0) {
                target.add(JsonObject().apply {
                    addProperty("role", "system")
                    add("content", parts)
                })
            }
        } else {
            val systemText = listOf(request.system, request.systemVolatileSuffix)
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
            if (systemText.isNotBlank()) {
                target.add(JsonObject().apply {
                    addProperty("role", "system")
                    addProperty("content", systemText)
                })
            }
        }
    }

    private fun textPart(text: String, withCacheBreakpoint: Boolean): JsonObject = JsonObject().apply {
        addProperty("type", "text")
        addProperty("text", text)
        if (withCacheBreakpoint) {
            add("cache_control", JsonObject().apply { addProperty("type", "ephemeral") })
        }
    }

    private fun appendWireMessages(target: JsonArray, message: ChatMessage, withCacheBreakpoint: Boolean = false) {
        when (message.role) {
            Role.SYSTEM -> target.add(simpleMessage("system", message.text))
            Role.USER -> target.add(simpleMessage("user", message.text))
            Role.ASSISTANT -> target.add(assistantMessage(message))
            Role.TOOL -> {
                val results = message.content.filterIsInstance<ContentBlock.ToolResult>()
                results.forEachIndexed { index, result ->
                    target.add(JsonObject().apply {
                        addProperty("role", "tool")
                        addProperty("tool_call_id", result.toolUseId)
                        // OpenRouter accepts cache_control on the last tool message in a string
                        // content (Anthropic-style). For other providers we keep plain string content.
                        if (withCacheBreakpoint && index == results.lastIndex && supportsExplicitCacheControl) {
                            add("content", JsonArray().apply { add(textPart(result.content, withCacheBreakpoint = true)) })
                        } else {
                            addProperty("content", result.content)
                        }
                    })
                }
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
