package com.github.saeldrit.geai.mcp

import com.github.saeldrit.geai.agent.ApprovalPolicy
import com.github.saeldrit.geai.llm.http.JsonSupport
import com.github.saeldrit.geai.settings.GeaiSettings
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolRegistry
import com.github.saeldrit.geai.tools.ToolResult
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Minimal Model Context Protocol server (Streamable HTTP, request/response only) that exposes
 * geai's [ToolRegistry] to an external MCP client such as the Claude Code CLI. Runs in-process so
 * tools keep their access to PSI, VFS, and the debugger. Mutating tools are still gated by
 * [ApprovalPolicy] here, so geai's safety model holds regardless of the client's permission mode.
 */
class McpToolServer(
    private val project: Project,
    private val registry: ToolRegistry,
    private val indicatorSupplier: () -> ProgressIndicator = { EmptyProgressIndicator() },
) {
    private var server: HttpServer? = null
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "geai-mcp").apply { isDaemon = true }
    }

    var port: Int = -1
        private set

    /**
     * Random per-session bearer secret. The loopback bind keeps remote hosts out, but any local
     * process could otherwise reach the tool surface (and read project source); the token closes
     * that gap. Shared with the CLI through the generated MCP config, never logged.
     */
    val authToken: String = newToken()

    fun start() {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/mcp") { exchange -> handle(exchange) }
        http.executor = executor
        http.start()
        server = http
        port = http.address.port
    }

    fun stop() {
        server?.stop(0)
        server = null
        executor.shutdownNow()
    }

    private fun handle(exchange: HttpExchange) {
        try {
            if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return
            }
            if (exchange.requestHeaders.getFirst("Authorization") != "Bearer $authToken") {
                exchange.sendResponseHeaders(401, -1)
                exchange.close()
                return
            }
            val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val request = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            if (request == null) {
                writeJson(exchange, errorEnvelope(JsonNull.INSTANCE, -32700, "Parse error"))
                return
            }
            val id = request.get("id")
            if (id == null || id.isJsonNull) {
                // JSON-RPC notification (e.g. notifications/initialized): acknowledge with no body.
                exchange.sendResponseHeaders(202, -1)
                exchange.close()
                return
            }
            val result = dispatch(request.get("method")?.asString, request)
            val envelope = JsonObject().apply {
                addProperty("jsonrpc", "2.0")
                add("id", id)
                add("result", result)
            }
            writeJson(exchange, envelope)
        } catch (e: Exception) {
            thisLogger().warn("Geai MCP server error", e)
            runCatching {
                exchange.sendResponseHeaders(500, -1)
                exchange.close()
            }
        }
    }

    private fun dispatch(method: String?, request: JsonObject): JsonObject = when (method) {
        "initialize" -> initializeResult(request.getAsJsonObject("params"))
        "tools/list" -> toolsListResult()
        "tools/call" -> toolsCallResult(request.getAsJsonObject("params"))
        else -> JsonObject() // ping and unknown methods get a lenient empty result
    }

    private fun initializeResult(params: JsonObject?): JsonObject = JsonObject().apply {
        addProperty("protocolVersion", params?.get("protocolVersion")?.asString ?: "2025-06-18")
        add("capabilities", JsonObject().apply { add("tools", JsonObject()) })
        add("serverInfo", JsonObject().apply {
            addProperty("name", "geai")
            addProperty("version", pluginVersion())
        })
    }

    private fun pluginVersion(): String =
        PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.version ?: "0.0.0"

    private fun toolsListResult(): JsonObject {
        val tools = JsonArray()
        registry.specs().forEach { spec ->
            tools.add(JsonObject().apply {
                addProperty("name", spec.name)
                addProperty("description", spec.description)
                add("inputSchema", JsonSupport.parseElement(spec.parametersJsonSchema))
            })
        }
        return JsonObject().apply { add("tools", tools) }
    }

    private fun toolsCallResult(params: JsonObject?): JsonObject {
        val name = params?.get("name")?.asString ?: return toolContent("Missing tool name.", isError = true)
        val tool = registry.find(name) ?: return toolContent("Unknown tool '$name'.", isError = true)
        val arguments = params.getAsJsonObject("arguments") ?: JsonObject()

        if (tool.mutating && !GeaiSettings.getInstance().state.autoApproveEditTools) {
            if (!ApprovalPolicy.confirm(project, tool, arguments.toString())) {
                return toolContent("User denied permission to run '${tool.name}'.", isError = true)
            }
        }

        val result: ToolResult = runCatching {
            tool.execute(ToolArgs.parse(arguments.toString()), ToolContext(project, indicatorSupplier()))
        }.getOrElse { ToolResult.error("Tool '$name' threw ${it.javaClass.simpleName}: ${it.message}") }
        return toolContent(result.content, result.isError)
    }

    private fun toolContent(text: String, isError: Boolean): JsonObject = JsonObject().apply {
        add("content", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", text)
            })
        })
        addProperty("isError", isError)
    }

    private fun errorEnvelope(id: JsonElement, code: Int, message: String): JsonObject = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", id)
        add("error", JsonObject().apply {
            addProperty("code", code)
            addProperty("message", message)
        })
    }

    private fun writeJson(exchange: HttpExchange, json: JsonObject) {
        val bytes = JsonSupport.gson.toJson(json).toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private companion object {
        const val PLUGIN_ID = "com.github.saeldrit.geai"
        private val RANDOM = SecureRandom()

        fun newToken(): String {
            val bytes = ByteArray(24)
            RANDOM.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }
    }
}
