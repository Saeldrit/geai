package com.github.saeldrit.geai.engine

import com.github.saeldrit.geai.agent.AgentEvent
import com.github.saeldrit.geai.agent.AgentListener
import com.github.saeldrit.geai.agent.AgentSession
import com.github.saeldrit.geai.agent.SystemPrompt
import com.github.saeldrit.geai.llm.ChatMessage
import com.github.saeldrit.geai.llm.TokenUsage
import com.github.saeldrit.geai.mcp.McpToolServer
import com.github.saeldrit.geai.settings.GeaiSettings
import com.github.saeldrit.geai.tools.GeaiToolset
import com.github.saeldrit.geai.tools.ToolResult
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Delegates a turn to the locally installed Claude Code CLI so it runs under the user's Claude
 * subscription login. geai's tools are exposed to it through an in-IDE [McpToolServer], and the
 * CLI's stream-json output is translated back into [AgentEvent]s for the chat UI. Continuity across
 * turns is preserved via Claude Code's own `--resume <session_id>`.
 */
class ClaudeCodeEngine(private val project: Project) {

    private data class RunOutcome(val exitCode: Int, val sawResult: Boolean)

    fun run(session: AgentSession, userText: String, listener: AgentListener, indicator: ProgressIndicator) {
        session.messages.add(ChatMessage.user(userText))
        listener.onEvent(AgentEvent.UserMessage(userText))

        val server = McpToolServer(project, GeaiToolset.registry()) { indicator }
        val tempFiles = mutableListOf<Path>()
        try {
            server.start()
            val mcpConfig = """{"mcpServers":{"geai":{"type":"http","url":"http://127.0.0.1:${server.port}/mcp"}}}"""
            val configFile = tempFile(tempFiles, "geai-mcp", ".json", mcpConfig)
            val promptFile = tempFile(tempFiles, "geai-doctrine", ".txt", SystemPrompt.doctrine())
            val allowedTools = GeaiToolset.all().joinToString(",") { "mcp__geai__${it.name}" }

            listener.onEvent(AgentEvent.Thinking)
            val stderr = StringBuilder()
            val usedResume = session.claudeSessionId != null
            var outcome = streamOnce(buildCommand(configFile, promptFile, allowedTools, session.claudeSessionId), userText, session, listener, indicator, stderr)

            // A stale --resume id makes the CLI exit non-zero; retry once as a fresh session.
            if (outcome.exitCode != 0 && !outcome.sawResult && usedResume) {
                listener.onEvent(AgentEvent.Info("Could not resume the previous Claude Code session; starting a fresh one."))
                session.claudeSessionId = null
                stderr.setLength(0)
                outcome = streamOnce(buildCommand(configFile, promptFile, allowedTools, null), userText, session, listener, indicator, stderr)
            }

            if (!outcome.sawResult) {
                if (outcome.exitCode != 0) {
                    listener.onEvent(AgentEvent.Error(processError(outcome.exitCode, stderr)))
                } else {
                    listener.onEvent(AgentEvent.Done(session.totalUsage))
                }
            }
        } catch (_: ProcessCanceledException) {
            listener.onEvent(AgentEvent.Cancelled())
        } catch (e: Exception) {
            listener.onEvent(AgentEvent.Error("Claude Code engine error: ${e.message ?: e.javaClass.simpleName}"))
        } finally {
            server.stop()
            tempFiles.forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun buildCommand(
        configFile: Path,
        promptFile: Path,
        allowedTools: String,
        resumeSessionId: String?,
    ): GeneralCommandLine {
        val cli = GeaiSettings.getInstance().state.claudeCliPath?.takeIf { it.isNotBlank() } ?: DEFAULT_CLI
        val command = GeneralCommandLine(
            cli, "-p",
            "--output-format", "stream-json",
            "--verbose",
            "--mcp-config", configFile.toString(),
            "--allowedTools", allowedTools,
            "--permission-mode", "acceptEdits",
            "--append-system-prompt-file", promptFile.toString(),
        )
        resumeSessionId?.let { command.addParameters("--resume", it) }
        project.basePath?.let { command.withWorkDirectory(it) }
        return command.withCharset(StandardCharsets.UTF_8)
    }

    private fun streamOnce(
        command: GeneralCommandLine,
        userText: String,
        session: AgentSession,
        listener: AgentListener,
        indicator: ProgressIndicator,
        stderr: StringBuilder,
    ): RunOutcome {
        val process = command.createProcess()

        // Feed the prompt via stdin to avoid command-line quoting of multi-line text.
        runCatching {
            process.outputStream.bufferedWriter(StandardCharsets.UTF_8).use {
                it.write(userText)
                it.newLine()
            }
        }
        daemon("geai-claude-stderr") {
            runCatching { process.errorStream.bufferedReader(StandardCharsets.UTF_8).forEachLine { stderr.appendLine(it) } }
        }
        daemon("geai-claude-killer") {
            while (process.isAlive) {
                if (indicator.isCanceled) {
                    process.destroyForcibly()
                    break
                }
                try {
                    Thread.sleep(150)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }

        val toolNames = HashMap<String, String>()
        val sawResult = booleanArrayOf(false)
        val textEmitted = booleanArrayOf(false)
        process.inputStream.bufferedReader(StandardCharsets.UTF_8).forEachLine { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) parseEvent(trimmed, session, listener, toolNames, sawResult, textEmitted)
        }
        val exit = process.waitFor()
        if (indicator.isCanceled) throw ProcessCanceledException()
        return RunOutcome(exit, sawResult[0])
    }

    private fun parseEvent(
        line: String,
        session: AgentSession,
        listener: AgentListener,
        toolNames: MutableMap<String, String>,
        sawResult: BooleanArray,
        textEmitted: BooleanArray,
    ) {
        val obj = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: return
        when (obj.get("type")?.asString) {
            "system" -> if (obj.get("subtype")?.asString == "init") captureSessionId(obj, session)
            "assistant" -> messageContent(obj)?.forEach { handleAssistantBlock(it.asJsonObject, session, listener, toolNames, textEmitted) }
            "user" -> messageContent(obj)?.forEach { handleToolResult(it.asJsonObject, listener, toolNames) }
            "result" -> handleResult(obj, session, listener, sawResult, textEmitted)
        }
    }

    private fun handleAssistantBlock(
        block: JsonObject,
        session: AgentSession,
        listener: AgentListener,
        toolNames: MutableMap<String, String>,
        textEmitted: BooleanArray,
    ) {
        when (block.get("type")?.asString) {
            "text" -> block.get("text")?.asString?.takeIf { it.isNotBlank() }?.let {
                listener.onEvent(AgentEvent.AssistantText(it))
                session.messages.add(ChatMessage.assistantText(it))
                textEmitted[0] = true
            }

            "tool_use" -> {
                val name = block.get("name")?.asString ?: "tool"
                block.get("id")?.asString?.let { toolNames[it] = name }
                listener.onEvent(AgentEvent.ToolStarted(displayName(name), block.get("input")?.toString() ?: "{}"))
            }
        }
    }

    private fun handleToolResult(block: JsonObject, listener: AgentListener, toolNames: MutableMap<String, String>) {
        if (block.get("type")?.asString != "tool_result") return
        val name = toolNames[block.get("tool_use_id")?.asString] ?: "tool"
        val isError = block.get("is_error")?.asBoolean ?: false
        listener.onEvent(AgentEvent.ToolFinished(displayName(name), ToolResult(toolResultText(block), isError)))
    }

    private fun handleResult(
        obj: JsonObject,
        session: AgentSession,
        listener: AgentListener,
        sawResult: BooleanArray,
        textEmitted: BooleanArray,
    ) {
        sawResult[0] = true
        captureSessionId(obj, session)
        if (obj.get("is_error")?.asBoolean == true) {
            listener.onEvent(AgentEvent.Error(obj.get("result")?.asString ?: "Claude Code reported an error."))
            return
        }
        if (!textEmitted[0]) {
            obj.get("result")?.asString?.takeIf { it.isNotBlank() }?.let {
                listener.onEvent(AgentEvent.AssistantText(it))
                session.messages.add(ChatMessage.assistantText(it))
            }
        }
        obj.getAsJsonObject("usage")?.let {
            session.totalUsage += TokenUsage(
                inputTokens = it.get("input_tokens")?.asInt ?: 0,
                outputTokens = it.get("output_tokens")?.asInt ?: 0,
            )
        }
        listener.onEvent(AgentEvent.Done(session.totalUsage))
    }

    private fun captureSessionId(obj: JsonObject, session: AgentSession) {
        obj.get("session_id")?.asString?.takeIf { it.isNotBlank() }?.let { session.claudeSessionId = it }
    }

    private fun messageContent(obj: JsonObject) =
        obj.getAsJsonObject("message")?.getAsJsonArray("content")

    /** tool_result content is either a string or an array of {type:text,text:...} parts. */
    private fun toolResultText(block: JsonObject): String {
        val content = block.get("content") ?: return ""
        if (content.isJsonPrimitive) return content.asString
        if (content.isJsonArray) {
            return content.asJsonArray.mapNotNull { part ->
                part.takeIf { it.isJsonObject }?.asJsonObject?.get("text")?.asString
            }.joinToString("\n")
        }
        return content.toString()
    }

    private fun displayName(toolName: String): String = toolName.removePrefix("mcp__geai__")

    private fun processError(exitCode: Int, stderr: StringBuilder): String {
        val tail = stderr.toString().trim().takeLast(1500)
        val hint = if (tail.contains("ENOENT", ignoreCase = true) || tail.isBlank()) {
            " Is the Claude Code CLI installed and on PATH? Set its path in Settings | Tools | Geai."
        } else {
            ""
        }
        return "Claude Code exited with code $exitCode.$hint" + if (tail.isNotBlank()) "\n$tail" else ""
    }

    private fun tempFile(into: MutableList<Path>, prefix: String, suffix: String, content: String): Path {
        val path = Files.createTempFile(prefix, suffix)
        Files.writeString(path, content, StandardCharsets.UTF_8)
        into.add(path)
        return path
    }

    private fun daemon(name: String, block: () -> Unit) {
        Thread(block, name).apply { isDaemon = true }.start()
    }

    private companion object {
        const val DEFAULT_CLI = "claude"
    }
}
