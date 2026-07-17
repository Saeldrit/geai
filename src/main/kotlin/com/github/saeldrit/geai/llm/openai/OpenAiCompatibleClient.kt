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
import com.intellij.openapi.diagnostic.thisLogger
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
    private val provider: com.github.saeldrit.geai.settings.LlmProvider = com.github.saeldrit.geai.settings.LlmProvider.OPENAI_COMPATIBLE,
) : LlmClient {

    private val supportsExplicitCacheControl: Boolean = baseUrl.contains("openrouter.ai", ignoreCase = true)

    private val isXiaomiMiMo: Boolean = provider == com.github.saeldrit.geai.settings.LlmProvider.XIAOMI

    override fun listModels(indicator: ProgressIndicator): List<String>? {
        val modelsUrl = modelsListUrl(baseUrl)
        val raw = try {
            HttpTransport.getJson(
                url = modelsUrl,
                headers = mapOf("Authorization" to "Bearer $apiKey"),
                indicator = indicator,
            )
        } catch (_: Exception) {
            return null
        }
        val root = JsonSupport.parseObject(raw)
        val data = root.getAsJsonArray("data") ?: return emptyList()
        return data.mapNotNull { it.asJsonObject?.stringOrNull("id") }
    }

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
                if (sse.data == "[DONE]") return@postJsonSse
                val data = JsonSupport.parseObject(sse.data)
                data.objectOrNull("usage")?.let { usageObj -> usageResult = parseUsage(usageObj) }
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
                    toolCallBuilders.forEach { (i, prev) ->
                        if (i < index && !prev.completedEmitted && prev.id != null && prev.name != null) {
                            prev.completedEmitted = true
                            onEvent(
                                com.github.saeldrit.geai.llm.StreamEvent.ToolUseCompleted(
                                    prev.id!!, prev.name!!, prev.arguments.toString().ifBlank { "{}" },
                                ),
                            )
                        }
                    }
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
            } catch (e: Exception) {
                if (e is com.google.gson.JsonSyntaxException ||
                    e is ClassCastException ||
                    e is NumberFormatException) {
                    thisLogger().debug("Skipping malformed SSE chunk: ${e.message}")
                } else {
                    throw e
                }
            }
        }

        if (result.isCancelled) throw com.intellij.openapi.progress.ProcessCanceledException()
        if (result.isError) {
            throw LlmException("Provider API error: ${JsonSupport.humanError(result.errorBody)}")
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
        var completedEmitted: Boolean = false
    }

    private fun chatCompletionsUrl(base: String): String {
        val trimmed = base.trimEnd('/')
        return when {
            trimmed.endsWith("/chat/completions") -> trimmed
            trimmed.endsWith("/v1") -> "$trimmed/chat/completions"
            else -> "$trimmed/v1/chat/completions"
        }
    }

    private fun modelsListUrl(base: String): String {
        val trimmed = base.trimEnd('/')
        return when {
            trimmed.endsWith("/chat/completions") -> trimmed.removeSuffix("/chat/completions") + "/models"
            trimmed.endsWith("/v1") -> "$trimmed/models"
            else -> "$trimmed/v1/models"
        }
    }

    private fun buildRequestBody(request: ChatRequest, streaming: Boolean): JsonObject {
        val root = JsonObject().apply {
            addProperty("model", request.model)
            addProperty("max_tokens", request.maxTokens)
            addProperty("temperature", request.temperature)
            if (streaming) {
                addProperty("stream", true)
                if (!isXiaomiMiMo) {
                    add("stream_options", JsonObject().apply { addProperty("include_usage", true) })
                }
            }
        }
        val messages = JsonArray()
        appendSystem(messages, request)
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
                        add("parameters", spec.parsedSchema)
                    })
                })
            }
            root.add("tools", tools)
            if (!isXiaomiMiMo) {
                root.addProperty("tool_choice", "auto")
            }
        }
        return root
    }

    private fun appendSystem(target: JsonArray, request: ChatRequest) {
        if (request.system.isBlank()) return
        if (supportsExplicitCacheControl) {
            // ONE stable, cached system block — volatile context rides in trailing user messages, so
            // the cache breakpoint here stays byte-identical across turns and keeps hitting.
            target.add(JsonObject().apply {
                addProperty("role", "system")
                add("content", JsonArray().apply { add(textPart(request.system, withCacheBreakpoint = true)) })
            })
        } else {
            target.add(JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", request.system)
            })
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
            Role.USER -> {
                val images = message.content.filterIsInstance<ContentBlock.Image>()
                if (images.isEmpty()) {
                    target.add(simpleMessage("user", message.text))
                } else {
                    val parts = JsonArray()
                    parts.add(textPart(message.text, false))
                    images.forEach { img ->
                        parts.add(JsonObject().apply {
                            addProperty("type", "image_url")
                            add("image_url", JsonObject().apply {
                                addProperty("url", "data:${img.mediaType};base64,${img.base64Data}")
                            })
                        })
                    }
                    target.add(JsonObject().apply {
                        addProperty("role", "user")
                        add("content", parts)
                    })
                }
            }
            Role.ASSISTANT -> target.add(assistantMessage(message))
            Role.TOOL -> {
                val results = message.content.filterIsInstance<ContentBlock.ToolResult>()
                results.forEachIndexed { index, result ->
                    target.add(JsonObject().apply {
                        addProperty("role", "tool")
                        addProperty("tool_call_id", result.toolUseId)
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
        val usage = root.objectOrNull("usage")?.let(::parseUsage) ?: TokenUsage.ZERO

        return ChatResult(ChatMessage.assistant(blocks), stopReason, usage)
    }

    /**
     * Normalize OpenAI-shaped usage to the same semantics the Anthropic client uses: [inputTokens] is
     * the FRESH (uncached) prompt, [cacheReadTokens] the cached subset — additive, never overlapping.
     * OpenAI/OpenRouter/DeepSeek report `prompt_tokens` as the TOTAL (cached + uncached), so fresh =
     * total − cached. Reporting the total as `inputTokens` (the old behavior) both double-counted the
     * cache in cost and made the compaction calibration think the whole prompt was billed at full rate.
     */
    internal fun parseUsage(usageObj: JsonObject): TokenUsage {
        val promptTotal = usageObj.intOr("prompt_tokens", 0)
        val cached = maxOf(
            usageObj.intOr("prompt_cache_hit_tokens", 0),
            usageObj.objectOrNull("prompt_tokens_details")?.intOr("cached_tokens", 0) ?: 0,
        ).coerceIn(0, promptTotal)
        return TokenUsage(
            inputTokens = (promptTotal - cached).coerceAtLeast(0),
            outputTokens = usageObj.intOr("completion_tokens", 0),
            cacheReadTokens = cached,
        )
    }
}
