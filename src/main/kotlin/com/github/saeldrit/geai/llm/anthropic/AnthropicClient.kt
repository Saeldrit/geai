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
        // SSE is read on one thread (HttpTransport.postJsonSse) and dispatched inline — plain vars suffice.
        var rawStopReason = ""
        var streamedUsage: TokenUsage? = null
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
                                toolUseBuilders[id] = StringBuilder(block.get("input")?.toString() ?: "")
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
                    "message_delta" -> {
                        data.stringOrNull("stop_reason")?.let { rawStopReason = it }
                        data.objectOrNull("usage")?.let { usageObj ->
                            streamedUsage = TokenUsage(
                                inputTokens = usageObj.intOr("input_tokens", 0),
                                outputTokens = usageObj.intOr("output_tokens", 0),
                                cacheReadTokens = usageObj.intOr("cache_read_input_tokens", 0),
                                cacheWriteTokens = usageObj.intOr("cache_creation_input_tokens", 0),
                            )
                        }
                    }
                }
            } catch (_: Exception) {
                // Malformed SSE data — skip this event
            }
        }

        if (result.isCancelled) throw com.intellij.openapi.progress.ProcessCanceledException()
        if (result.isError) {
            throw com.github.saeldrit.geai.llm.LlmException(
                "Claude API error: ${result.errorBody.take(2000)}",
                statusCode = result.statusCode,
            )
        }

        // Build final content blocks from streamed data. Tool NAMES come from the content_block_start
        // events captured in toolNames (the streamed name is the only place they appear — the request
        // body does not echo them back).
        val blocks = mutableListOf<ContentBlock>()
        if (textBuilder.isNotEmpty()) blocks.add(ContentBlock.Text(textBuilder.toString()))
        toolUseBuilders.forEach { (id, jsonBuilder) ->
            blocks.add(ContentBlock.ToolUse(id, toolNames[id] ?: "tool", jsonBuilder.toString()))
        }
        if (blocks.isEmpty()) blocks.add(ContentBlock.Text(""))

        val stopReason = when (rawStopReason) {
            "tool_use" -> StopReason.TOOL_USE
            "end_turn", "stop_sequence" -> StopReason.END_TURN
            "max_tokens" -> StopReason.MAX_TOKENS
            else -> StopReason.OTHER
        }
        val usage = streamedUsage ?: TokenUsage.ZERO

        onEvent(com.github.saeldrit.geai.llm.StreamEvent.Done)
        return ChatResult(ChatMessage.assistant(blocks), stopReason, usage)
    }

    private fun buildRequestBody(request: ChatRequest, streaming: Boolean): JsonObject {
        val root = JsonObject().apply {
            addProperty("model", request.model)
            addProperty("max_tokens", request.maxTokens)
            addProperty("temperature", request.temperature)
            if (streaming) addProperty("stream", true)
            // System prompt as a cacheable block: it is large and identical across a session, so a
            // cache breakpoint here turns repeated reads into cheap cache hits. The per-turn bundle
            // suffix goes in a SECOND block WITHOUT its own breakpoint — Anthropic auto-extends the
            // cached prefix up to the NEXT breakpoint (tools), so a turn-stable bundle is implicitly
            // cached anyway. Saves one of the 4 available breakpoints for transcript caching.
            if (request.system.isNotBlank() || request.systemVolatileSuffix.isNotBlank()) {
                add("system", JsonArray().apply {
                    if (request.system.isNotBlank()) {
                        add(JsonObject().apply {
                            addProperty("type", "text")
                            addProperty("text", request.system)
                            add("cache_control", ephemeral())
                        })
                    }
                    if (request.systemVolatileSuffix.isNotBlank()) {
                        add(JsonObject().apply {
                            addProperty("type", "text")
                            addProperty("text", request.systemVolatileSuffix)
                        })
                    }
                })
            }
        }
        if (request.tools.isNotEmpty()) {
            val tools = JsonArray()
            request.tools.forEachIndexed { index, spec ->
                tools.add(JsonObject().apply {
                    addProperty("name", spec.name)
                    addProperty("description", spec.description)
                    add("input_schema", JsonSupport.parseElement(spec.parametersJsonSchema))
                    // Breakpoint on the last tool caches the whole (stable) tool catalog prefix.
                    if (index == request.tools.lastIndex) add("cache_control", ephemeral())
                })
            }
            root.add("tools", tools)
        }
        val messages = JsonArray()
        // Incremental transcript caching: cache_control on the LAST tool_result of the MOST RECENT
        // TOOL message caches the entire transcript prefix up to that point, so the next iteration
        // reads it from cache (10% of input price) instead of resending. Anthropic allows ≤4 cache
        // breakpoints — we use 4: doctrine, bundle, tools, last-tool-result.
        val lastToolIndex = request.messages.indexOfLast { it.role == Role.TOOL }
        request.messages.forEachIndexed { index, message ->
            toWireMessage(message, withCacheBreakpoint = index == lastToolIndex)?.let(messages::add)
        }
        root.add("messages", messages)
        return root
    }

    private fun ephemeral(): JsonObject = JsonObject().apply { addProperty("type", "ephemeral") }

    private fun toWireMessage(message: ChatMessage, withCacheBreakpoint: Boolean = false): JsonObject? = when (message.role) {
        Role.SYSTEM -> null // system prompt is a top-level field, never a message
        Role.USER -> wire("user", textBlocks(message.content))
        Role.ASSISTANT -> wire("assistant", assistantBlocks(message.content))
        Role.TOOL -> wire("user", toolResultBlocks(message.content, withCacheBreakpoint))
    }

    private fun wire(role: String, content: JsonArray): JsonObject = JsonObject().apply {
        addProperty("role", role)
        add("content", content)
    }

    private fun textBlocks(blocks: List<ContentBlock>): JsonArray = JsonArray().apply {
        blocks.filterIsInstance<ContentBlock.Text>().forEach { block ->
            add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", block.text)
            })
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

                is ContentBlock.ToolResult -> Unit // never valid inside an assistant turn
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
                // Breakpoint on the LAST tool_result of the message caches the entire transcript prefix
                // up to (and including) this point — the next turn reads it instead of resending.
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
