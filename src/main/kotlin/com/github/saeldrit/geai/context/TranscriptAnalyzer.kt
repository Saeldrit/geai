package com.github.saeldrit.geai.context

import com.github.saeldrit.geai.llm.ChatMessage
import com.github.saeldrit.geai.llm.ContentBlock
import com.github.saeldrit.geai.llm.Role
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import java.io.StringReader

data class TranscriptDigest(
    val userMessage: String?,
    val activeTask: String,
    val filesTouched: Map<String, List<String>>,
    val findings: List<String>,
    val toolCallSummary: List<String>,
    val errors: List<String>,
    val assistantConclusions: List<String>,
) {
    fun renderForSummarizer(): String = buildString {
        if (userMessage != null) appendLine("USER: $userMessage")
        if (activeTask.isNotBlank()) appendLine("ACTIVE_TASK: $activeTask")
        if (filesTouched.isNotEmpty()) {
            appendLine("\nFILES TOUCHED:")
            filesTouched.forEach { (file, actions) ->
                appendLine("  $file: ${actions.joinToString("; ")}")
            }
        }
        if (findings.isNotEmpty()) {
            appendLine("\nFINDINGS (file:line):")
            findings.takeLast(20).forEach { appendLine("  - $it") }
        }
        if (toolCallSummary.isNotEmpty()) {
            appendLine("\nTOOL CALLS:")
            toolCallSummary.takeLast(30).forEach { appendLine("  - $it") }
        }
        if (errors.isNotEmpty()) {
            appendLine("\nERRORS:")
            errors.takeLast(5).forEach { appendLine("  - $it") }
        }
        if (assistantConclusions.isNotEmpty()) {
            appendLine("\nCONCLUSIONS:")
            assistantConclusions.takeLast(3).forEach { appendLine("  - $it") }
        }
    }.trim()

    fun renderForAgent(): String = buildString {
        if (activeTask.isNotBlank()) appendLine("Active task: $activeTask")
        if (filesTouched.isNotEmpty()) {
            appendLine("Already touched (do NOT re-read/re-search unless you need a NEW line range or the file changed):")
            filesTouched.forEach { (file, actions) ->
                appendLine("  • $file — ${actions.joinToString(", ")}")
            }
        }
        if (findings.isNotEmpty()) {
            appendLine("Findings still in play:")
            findings.takeLast(25).forEach { appendLine("  • $it") }
        }
        if (toolCallSummary.isNotEmpty()) {
            appendLine("Recent tool outcomes (compressed payloads; use this ledger instead of re-calling):")
            toolCallSummary.takeLast(25).forEach { appendLine("  • $it") }
        }
        if (errors.isNotEmpty()) {
            appendLine("Errors already hit (do not repeat the same failing call):")
            errors.takeLast(8).forEach { appendLine("  • $it") }
        }
        if (assistantConclusions.isNotEmpty()) {
            appendLine("Your earlier conclusions:")
            assistantConclusions.takeLast(3).forEach { c ->
                appendLine("  • ${c.take(400).replace('\n', ' ')}")
            }
        }
        if (filesTouched.isEmpty() && toolCallSummary.isEmpty() && findings.isEmpty()) {
            appendLine("(no structured actions extracted from the compacted segment)")
        }
    }.trim()
}

private val RETRY_ERROR_PATTERNS = listOf(
    "old_string not found",
    "oldText not found",
    "did not match",
    "String not found",
    "No such file or directory",
    "ENOENT",
)

private val FILE_LINE_REF = Regex("""(\w[\w./\\-]*\.\w+:\d+)""")

private val PATH_KEYS = setOf("path", "target_file", "file", "file_path", "directory_path", "search_directory", "glob_pattern")

private val FILE_OPS_TOOLS = setOf(
    "read_file", "write_file", "edit_file", "find_files",
    "search_for_text", "search_text", "find_symbol_source",
    "file_structure", "list_dir", "similar_search",
)

/**
 * State-changing shell commands MUST survive compaction in the ledger: losing "what did I already
 * run" (git add/commit, build results) mid-task forces the agent to re-derive external state — the
 * exact failure that produced a dirty push after a compaction once dropped the staging state.
 */
private val COMMAND_TOOLS = setOf("run_command")

object TranscriptAnalyzer {

    fun analyze(messages: List<ChatMessage>, activeTask: String = ""): TranscriptDigest {
        var userMessage: String? = null
        val filesTouched = linkedMapOf<String, MutableList<String>>()
        val findings = mutableListOf<String>()
        val toolCallSummaries = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val assistantConclusions = mutableListOf<String>()

        val toolUseIndex = linkedMapOf<String, Pair<String, String>>()

        for (message in messages) {
            when (message.role) {
                Role.USER -> {
                    val text = message.text.takeIf { it.isNotBlank() }
                    if (text != null) {
                        userMessage = text
                    }
                }

                Role.ASSISTANT -> {
                    for (tu in message.toolUses) {
                        toolUseIndex[tu.id] = tu.name to tu.inputJson
                    }
                    message.content.filterIsInstance<ContentBlock.Text>()
                        .map { it.text }
                        .filter { it.isNotBlank() }
                        .takeIf { it.isNotEmpty() }
                        ?.let { texts ->
                            assistantConclusions.addAll(texts)
                        }
                }

                Role.TOOL -> {
                    for (block in message.content.filterIsInstance<ContentBlock.ToolResult>()) {
                        val (toolName, inputJson) = toolUseIndex[block.toolUseId] ?: ("unknown" to "{}")

                        if (block.isError && isRetryError(block.content)) continue

                        if (toolName in FILE_OPS_TOOLS) {
                            val filePath = extractFilePath(inputJson)
                            if (filePath != null) {
                                val action = describeAction(toolName, inputJson)
                                filesTouched.getOrPut(filePath) { mutableListOf() }.add(action)
                            }
                        }

                        if (!block.isError) {
                            FILE_LINE_REF.findAll(block.content).forEach { match ->
                                val ref = match.groupValues[1]
                                if (ref !in findings) {
                                    findings.add(ref)
                                }
                            }
                        }

                        if (toolName in FILE_OPS_TOOLS) {
                            val preview = truncate(toolName, 20) + "(" + argsPreview(inputJson) + ")"
                            val resultPreview = if (block.isError) "ERROR: ${truncate(block.content, 100)}" else truncate(block.content, 100)
                            toolCallSummaries.add("$preview → $resultPreview")
                        } else if (toolName in COMMAND_TOOLS) {
                            val oneLine = block.content.replace('\n', ' ').replace(Regex("\\s+"), " ")
                            val status = if (block.isError) "FAILED" else "ok"
                            toolCallSummaries.add("$toolName [$status]: ${truncate(oneLine, 180)}")
                        }

                        if (block.isError && !isRetryError(block.content)) {
                            errors.add("[$toolName] ${truncate(block.content, 200)}")
                        }
                    }
                }

                Role.SYSTEM -> Unit
            }
        }

        val trimmedConclusions = assistantConclusions
            .filter { it.isNotBlank() && it.length > 10 }
            .distinct()
            .takeLast(3)

        return TranscriptDigest(
            userMessage = userMessage?.take(500),
            activeTask = activeTask,
            filesTouched = filesTouched.mapValues { it.value.distinct() },
            findings = findings.takeLast(30),
            toolCallSummary = toolCallSummaries.takeLast(30),
            errors = errors.takeLast(10),
            assistantConclusions = trimmedConclusions,
        )
    }


    private fun isRetryError(content: String): Boolean =
        RETRY_ERROR_PATTERNS.any { content.contains(it, ignoreCase = true) }

    private fun extractFilePath(inputJson: String): String? {
        return try {
            val trimmed = inputJson.trim()
            if (trimmed.isEmpty() || trimmed == "{}") return null
            val reader = JsonReader(StringReader(trimmed)).apply { isLenient = true }
            val obj = JsonParser.parseReader(reader).asJsonObject
            for (key in PATH_KEYS) {
                val value = obj.get(key)
                if (value != null && value.isJsonPrimitive && value.asString.isNotBlank()) {
                    return value.asString.trim()
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun describeAction(toolName: String, inputJson: String): String = when (toolName) {
        "read_file" -> "read"
        "write_file" -> "write"
        "edit_file" -> "edit"
        "find_files" -> "search"
        "search_for_text" -> "search"
        "search_text" -> "search"
        "find_symbol_source" -> "find_symbol"
        "file_structure" -> "structure"
        "list_dir" -> "list"
        "similar_search" -> "similar_search"
        else -> toolName
    }

    private fun argsPreview(inputJson: String): String {
        return try {
            val trimmed = inputJson.trim()
            if (trimmed.isEmpty() || trimmed == "{}") return ""
            val reader = JsonReader(StringReader(trimmed)).apply { isLenient = true }
            val obj = JsonParser.parseReader(reader).asJsonObject

            for (key in listOf("target_file", "path", "file", "file_path", "text_snippet", "query")) {
                val value = obj.get(key)
                if (value != null && value.isJsonPrimitive && value.asString.isNotBlank()) {
                    val v = value.asString
                    return if (v.length > 60) truncate(v, 60) + "…" else v
                }
            }
            ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun truncate(s: String, maxLen: Int): String =
        if (s.length <= maxLen) s else s.take(maxLen - 1) + "…"
}
