package com.github.saeldrit.geai.llm.anthropic

import com.github.saeldrit.geai.llm.ChatMessage
import com.github.saeldrit.geai.llm.ChatRequest
import com.github.saeldrit.geai.llm.ChatResult
import com.github.saeldrit.geai.llm.ContentBlock
import com.github.saeldrit.geai.llm.LlmClient
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
 * Client for the native Anthropic Claude Messages API (`POST /v1/messages`), with first-class
 * tool use. Translates the provider-agnostic domain model to/from Anthropic content blocks.
 */
class AnthropicClient(
    private val baseUrl: String,
    private val apiKey: String,
) : LlmClient {

    override fun chat(request: ChatRequest, indicator: ProgressIndicator): ChatResult {
        val raw = HttpTransport.postJson(
            url = "$baseUrl/v1/messages",
            headers = mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to ANTHROPIC_VERSION,
            ),
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
        val body = buildRequestBody(request, streaming = true)
        var rawStopReason = ""
        var inputTokens = 0
        var outputTokens = 0
        var cacheReadTokens = 0
        var cacheWriteTokens = 0
        var currentToolId: String? = null
        val textBuilder = StringBuilder()
        val toolUseBuilders = mutableMapOf<String, StringBuilder>()
        val toolNames = mutableMapOf<String, String>()

        val result = HttpTransport.postJsonSse(
            url = "$baseUrl/v1/messages",
            headers = mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to ANTHROPIC_VERSION,
            ),
            body = JsonSupport.gson.toJson(body),
            indicator = indicator,
        ) { sse ->
            try {
                val data = JsonSupport.parseObject(sse.data)
                when (data.stringOrNull("type")) {
                    "content_block_start" -> {
                        val block = data.objectOrNull("content_block") ?: return@postJsonSse
                        when (block.stringOrNull("type")) {
                            "text" -> {
                                val text = block.stringOrNull("text") ?: ""
                                if (text.isNotEmpty()) {
                                    textBuilder.append(text)
                                    onEvent(com.github.saeldrit.geai.llm.StreamEvent.TextDelta(text))
                                }
                            }
                            "tool_use" -> {
                                val id = block.stringOrNull("id").orEmpty()
                                val name = block.stringOrNull("name").orEmpty()
                                currentToolId = id
                                toolNames[id] = name
                                toolUseBuilders[id] = StringBuilder()
                                onEvent(com.github.saeldrit.geai.llm.StreamEvent.ToolUseStarted(id, name))
                            }
                        }
                    }
                    "content_block_delta" -> {
                        val delta = data.objectOrNull("delta") ?: return@postJsonSse
                        when (delta.stringOrNull("type")) {
                            "text_delta" -> {
                                val chunk = delta.stringOrNull("text") ?: ""
                                textBuilder.append(chunk)
                                onEvent(com.github.saeldrit.geai.llm.StreamEvent.TextDelta(chunk))
                            }
                            "input_json_delta" -> {
                                val chunk = delta.stringOrNull("partial_json") ?: ""
                                val id = currentToolId ?: return@postJsonSse
                                toolUseBuilders.computeIfAbsent(id) { StringBuilder() }.append(chunk)
                                onEvent(com.github.saeldrit.geai.llm.StreamEvent.ToolUseInputDelta(id, chunk))
                            }
                        }
                    }
                    "content_block_stop" -> {
                        val id = currentToolId
                        if (id != null) {
                            currentToolId = null
                            onEvent(
                                com.github.saeldrit.geai.llm.StreamEvent.ToolUseCompleted(
                                    id,
                                    toolNames[id] ?: "tool",
                                    toolUseBuilders[id]?.toString()?.ifBlank { "{}" } ?: "{}",
                                ),
                            )
                        }
                    }
                    "message_start" -> {
                        data.objectOrNull("message")?.objectOrNull("usage")?.let { u ->
                            inputTokens = u.intOr("input_tokens", 0)
                            cacheReadTokens = u.intOr("cache_read_input_tokens", 0)
                            cacheWriteTokens = u.intOr("cache_creation_input_tokens", 0)
                            outputTokens = u.intOr("output_tokens", 0)
                        }
                    }
                    "message_delta" -> {
                        data.stringOrNull("stop_reason")?.let { rawStopReason = it }
                        data.objectOrNull("usage")?.let { u ->
                            outputTokens = u.intOr("output_tokens", outputTokens)
                            inputTokens = maxOf(inputTokens, u.intOr("input_tokens", 0))
                            cacheReadTokens = maxOf(cacheReadTokens, u.intOr("cache_read_input_tokens", 0))
                            cacheWriteTokens = maxOf(cacheWriteTokens, u.intOr("cache_creation_input_tokens", 0))
                        }
                    }
                }
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
            throw com.github.saeldrit.geai.llm.LlmException(
                "Claude API error: ${JsonSupport.humanError(result.errorBody)}",
                statusCode = result.statusCode,
            )
        }

        val blocks = mutableListOf<ContentBlock>()
        if (textBuilder.isNotEmpty()) blocks.add(ContentBlock.Text(textBuilder.toString()))
        toolUseBuilders.forEach { (id, jsonBuilder) ->
            blocks.add(ContentBlock.ToolUse(id, toolNames[id] ?: "tool", jsonBuilder.toString().ifBlank { "{}" }))
        }
        if (blocks.isEmpty()) blocks.add(ContentBlock.Text(""))

        val stopReason = when (rawStopReason) {
            "tool_use" -> StopReason.TOOL_USE
            "end_turn", "stop_sequence" -> StopReason.END_TURN
            "max_tokens" -> StopReason.MAX_TOKENS
            else -> StopReason.OTHER
        }
        val usage = TokenUsage(inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens)

        onEvent(com.github.saeldrit.geai.llm.StreamEvent.Done)
        return ChatResult(ChatMessage.assistant(blocks), stopReason, usage)
    }

    // internal (not private) so the wire-format contract — a single stable cached system block, one
    // cache breakpoint on the last tool spec, and a rolling breakpoint on the last tool_result — can
    // be asserted directly in unit tests without a live API.
    internal fun buildRequestBody(request: ChatRequest, streaming: Boolean): JsonObject {
        val root = JsonObject().apply {
            addProperty("model", request.model)
            addProperty("max_tokens", request.maxTokens)
            addProperty("temperature", request.temperature)
            if (streaming) addProperty("stream", true)
            if (request.system.isNotBlank()) {
                // ONE stable, cached system block. Nothing volatile appended here — a per-turn change
                // in `system` sits ahead of `messages` in the cache hierarchy and would invalidate the
                // whole conversation cache. Volatile context rides in trailing user messages instead.
                add("system", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("type", "text")
                        addProperty("text", request.system)
                        add("cache_control", ephemeral())
                    })
                })
            }
        }
        if (request.tools.isNotEmpty()) {
            val tools = JsonArray()
            request.tools.forEachIndexed { index, spec ->
                tools.add(JsonObject().apply {
                    addProperty("name", spec.name)
                    addProperty("description", spec.description)
                    add("input_schema", spec.parsedSchema)
                    if (index == request.tools.lastIndex) add("cache_control", ephemeral())
                })
            }
            root.add("tools", tools)
        }
        val messages = JsonArray()
        val lastToolIndex = request.messages.indexOfLast { it.role == Role.TOOL }
        request.messages.forEachIndexed { index, message ->
            toWireMessage(message, withCacheBreakpoint = index == lastToolIndex)?.let(messages::add)
        }
        root.add("messages", messages)
        return root
    }

    private fun ephemeral(): JsonObject = JsonObject().apply { addProperty("type", "ephemeral") }

    private fun toWireMessage(message: ChatMessage, withCacheBreakpoint: Boolean = false): JsonObject? = when (message.role) {
        Role.SYSTEM -> null
        Role.USER -> wire("user", textBlocks(message.content))
        Role.ASSISTANT -> wire("assistant", assistantBlocks(message.content))
        Role.TOOL -> wire("user", toolResultBlocks(message.content, withCacheBreakpoint))
    }

    private fun wire(role: String, content: JsonArray): JsonObject = JsonObject().apply {
        addProperty("role", role)
        add("content", content)
    }

    private fun textBlocks(blocks: List<ContentBlock>): JsonArray = JsonArray().apply {
        blocks.forEach { block ->
            when (block) {
                is ContentBlock.Text -> add(JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", block.text)
                })
                is ContentBlock.Image -> add(JsonObject().apply {
                    addProperty("type", "image")
                    add("source", JsonObject().apply {
                        addProperty("type", "base64")
                        addProperty("media_type", block.mediaType)
                        addProperty("data", block.base64Data)
                    })
                })
                else -> Unit
            }
        }
    }

    private fun assistantBlocks(blocks: List<ContentBlock>): JsonArray = JsonArray().apply {
        blocks.forEach { block ->
            when (block) {
                is ContentBlock.Text -> add(JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", block.text)
                })

                is ContentBlock.ToolUse -> add(JsonObject().apply {
                    addProperty("type", "tool_use")
                    addProperty("id", block.id)
                    addProperty("name", block.name)
                    add("input", JsonSupport.parseElement(block.inputJson.ifBlank { "{}" }))
                })

                is ContentBlock.Image -> Unit
                is ContentBlock.ToolResult -> Unit
            }
        }
    }

    private fun toolResultBlocks(blocks: List<ContentBlock>, withCacheBreakpoint: Boolean = false): JsonArray = JsonArray().apply {
        val results = blocks.filterIsInstance<ContentBlock.ToolResult>()
        results.forEachIndexed { index, result ->
            add(JsonObject().apply {
                addProperty("type", "tool_result")
                addProperty("tool_use_id", result.toolUseId)
                addProperty("content", result.content)
                if (result.isError) addProperty("is_error", true)
                if (withCacheBreakpoint && index == results.lastIndex) add("cache_control", ephemeral())
            })
        }
    }

    private fun parseResponse(raw: String): ChatResult {
        val root = JsonSupport.parseObject(raw)
        val blocks = mutableListOf<ContentBlock>()
        root.arrayOrEmpty("content").forEach { element ->
            val obj = element.asJsonObject
            when (obj.stringOrNull("type")) {
                "text" -> obj.stringOrNull("text")?.let { blocks.add(ContentBlock.Text(it)) }
                "tool_use" -> blocks.add(
                    ContentBlock.ToolUse(
                        id = obj.stringOrNull("id").orEmpty(),
                        name = obj.stringOrNull("name").orEmpty(),
                        inputJson = obj.get("input")?.toString() ?: "{}",
                    )
                )
            }
        }
        if (blocks.isEmpty()) blocks.add(ContentBlock.Text(""))

        val stopReason = when (root.stringOrNull("stop_reason")) {
            "tool_use" -> StopReason.TOOL_USE
            "end_turn", "stop_sequence" -> StopReason.END_TURN
            "max_tokens" -> StopReason.MAX_TOKENS
            else -> StopReason.OTHER
        }
        val usage = root.objectOrNull("usage")
            ?.let {
                TokenUsage(
                    inputTokens = it.intOr("input_tokens", 0),
                    outputTokens = it.intOr("output_tokens", 0),
                    cacheReadTokens = it.intOr("cache_read_input_tokens", 0),
                    cacheWriteTokens = it.intOr("cache_creation_input_tokens", 0),
                )
            }
            ?: TokenUsage.ZERO

        return ChatResult(ChatMessage.assistant(blocks), stopReason, usage)
    }

    private companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
    }
}
