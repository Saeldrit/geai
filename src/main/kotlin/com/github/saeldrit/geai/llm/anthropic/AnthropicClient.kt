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
            body = JsonSupport.gson.toJson(buildRequestBody(request)),
            indicator = indicator,
        )
        return parseResponse(raw)
    }

    private fun buildRequestBody(request: ChatRequest): JsonObject {
        val root = JsonObject().apply {
            addProperty("model", request.model)
            addProperty("max_tokens", request.maxTokens)
            addProperty("temperature", request.temperature)
            if (request.system.isNotBlank()) addProperty("system", request.system)
        }
        if (request.tools.isNotEmpty()) {
            val tools = JsonArray()
            request.tools.forEach { spec ->
                tools.add(JsonObject().apply {
                    addProperty("name", spec.name)
                    addProperty("description", spec.description)
                    add("input_schema", JsonSupport.parseElement(spec.parametersJsonSchema))
                })
            }
            root.add("tools", tools)
        }
        val messages = JsonArray()
        request.messages.forEach { message -> toWireMessage(message)?.let(messages::add) }
        root.add("messages", messages)
        return root
    }

    private fun toWireMessage(message: ChatMessage): JsonObject? = when (message.role) {
        Role.SYSTEM -> null // system prompt is a top-level field, never a message
        Role.USER -> wire("user", textBlocks(message.content))
        Role.ASSISTANT -> wire("assistant", assistantBlocks(message.content))
        Role.TOOL -> wire("user", toolResultBlocks(message.content))
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

    private fun toolResultBlocks(blocks: List<ContentBlock>): JsonArray = JsonArray().apply {
        blocks.filterIsInstance<ContentBlock.ToolResult>().forEach { result ->
            add(JsonObject().apply {
                addProperty("type", "tool_result")
                addProperty("tool_use_id", result.toolUseId)
                addProperty("content", result.content)
                if (result.isError) addProperty("is_error", true)
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
            ?.let { TokenUsage(it.intOr("input_tokens", 0), it.intOr("output_tokens", 0)) }
            ?: TokenUsage.ZERO

        return ChatResult(ChatMessage.assistant(blocks), stopReason, usage)
    }

    private companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
    }
}
