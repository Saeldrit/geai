package com.github.saeldrit.geai.context

import com.github.saeldrit.geai.llm.ChatMessage
import com.github.saeldrit.geai.llm.ContentBlock
import com.github.saeldrit.geai.llm.Role
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import java.io.StringReader

/**
 * Programmatic pre-processor that runs BEFORE the LLM summarizer.
 *
 * Extracts structured signal from the raw transcript so the summarizer receives organized,
 * condensed input instead of having to parse and organize in one shot. This dramatically
 * improves summarization reliability because the LLM only needs to synthesize, not triage.
 */
data class TranscriptDigest(
    /** The last user message. */
    val userMessage: String?,
    /** Current task description. */
    val activeTask: String,
    /** File -> list of actions performed on it (read, edit, write, search, etc.). */
    val filesTouched: Map<String, List<String>>,
    /** file:line references extracted from search/grep results. */
    val findings: List<String>,
    /** "call_name(args_preview) → result_preview" for each tool call. */
    val toolCallSummary: List<String>,
    /** Non-retried error messages. */
    val errors: List<String>,
    /** Last few assistant text blocks (conclusions / analysis). */
    val assistantConclusions: List<String>,
) {
    /**
     * Render the digest into a condensed, structured text block for the summarizer.
     * This is the input the LLM summarizer actually sees — organized, deduplicated,
     * and stripped of noise.
     */
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
}

/**
 * Known retry / transient error patterns that should be dropped from the digest.
 * These are common tool failures that the agent automatically retries and don't
 * carry useful signal for the summarizer.
 */
private val RETRY_ERROR_PATTERNS = listOf(
    "old_string not found",
    "oldText not found",
    "did not match",
    "String not found",
    "No such file or directory",
    "ENOENT",
)

/** Regex for extracting file:line references from tool results (grep output, search results, etc.). */
private val FILE_LINE_REF = Regex("""(\w[\w./\\-]*\.\w+:\d+)""")

/** JSON keys that commonly hold file paths in tool input. */
private val PATH_KEYS = setOf("path", "target_file", "file", "file_path", "directory_path", "search_directory", "glob_pattern")

/** Tool names whose calls are interesting to track per-file. */
private val FILE_OPS_TOOLS = setOf(
    "read_file", "write_file", "edit_file", "find_files",
    "search_for_text", "search_text", "find_symbol_source",
    "file_structure", "list_dir", "similar_search",
)

object TranscriptAnalyzer {

    /**
     * Analyze a list of chat messages and produce a [TranscriptDigest].
     * Walks the transcript pairing tool_use with tool_result, extracting structured
     * signal and filtering noise.
     */
    fun analyze(messages: List<ChatMessage>, activeTask: String = ""): TranscriptDigest {
        var userMessage: String? = null
        val filesTouched = linkedMapOf<String, MutableList<String>>()
        val findings = mutableListOf<String>()
        val toolCallSummaries = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val assistantConclusions = mutableListOf<String>()

        // Index tool_use blocks by id so we can pair them with tool_results.
        val toolUseIndex = linkedMapOf<String, Pair<String, String>>() // toolUseId → (name, inputJson)

        for (message in messages) {
            when (message.role) {
                Role.USER -> {
                    // Track the last user message (non-tool-result).
                    val text = message.text.takeIf { it.isNotBlank() }
                    if (text != null) {
                        userMessage = text
                    }
                }

                Role.ASSISTANT -> {
                    // Collect tool_use references for pairing.
                    for (tu in message.toolUses) {
                        toolUseIndex[tu.id] = tu.name to tu.inputJson
                    }
                    // Collect the last few assistant text blocks as conclusions.
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

                        // Skip error-only results matching known retry patterns.
                        if (block.isError && isRetryError(block.content)) continue

                        // Extract file path from the tool call input and track it.
                        if (toolName in FILE_OPS_TOOLS) {
                            val filePath = extractFilePath(inputJson)
                            if (filePath != null) {
                                val action = describeAction(toolName, inputJson)
                                filesTouched.getOrPut(filePath) { mutableListOf() }.add(action)
                            }
                        }

                        // Extract file:line references from tool results.
                        if (!block.isError) {
                            FILE_LINE_REF.findAll(block.content).forEach { match ->
                                val ref = match.groupValues[1]
                                if (ref !in findings) {
                                    findings.add(ref)
                                }
                            }
                        }

                        // Build tool call summary.
                        if (toolName in FILE_OPS_TOOLS) {
                            val preview = truncate(toolName, 20) + "(" + argsPreview(inputJson) + ")"
                            val resultPreview = if (block.isError) "ERROR: ${truncate(block.content, 100)}" else truncate(block.content, 100)
                            toolCallSummaries.add("$preview → $resultPreview")
                        }

                        // Collect non-retried errors.
                        if (block.isError && !isRetryError(block.content)) {
                            errors.add("[$toolName] ${truncate(block.content, 200)}")
                        }
                    }
                }

                Role.SYSTEM -> Unit
            }
        }

        // Trim assistant conclusions to the last 3 distinct text blocks.
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

    // ---- internal helpers ----

    /** Check if an error message matches a known retry / transient pattern. */
    private fun isRetryError(content: String): Boolean =
        RETRY_ERROR_PATTERNS.any { content.contains(it, ignoreCase = true) }

    /**
     * Extract a file path from a tool's inputJson.
     * Parses the JSON and looks for known path keys.
     */
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

    /** Describe what kind of action was performed based on the tool name and input. */
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

    /** Extract a brief preview of the most important args from inputJson. */
    private fun argsPreview(inputJson: String): String {
        return try {
            val trimmed = inputJson.trim()
            if (trimmed.isEmpty() || trimmed == "{}") return ""
            val reader = JsonReader(StringReader(trimmed)).apply { isLenient = true }
            val obj = JsonParser.parseReader(reader).asJsonObject

            // Prioritize path-like args.
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
