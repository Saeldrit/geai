package com.github.saeldrit.geai.context

import com.github.saeldrit.geai.llm.ChatMessage
import com.github.saeldrit.geai.llm.ContentBlock
import com.github.saeldrit.geai.llm.Role

/**
 * Context compaction for the agent transcript — aggressive marker-based compression.
 *
 * DESIGN PHILOSOPHY:
 * Unlike traditional summarisation that tries to preserve "important details" through LLM calls
 * (which inevitably lose specifics), this compressor deliberately FORGETS tool result details and
 * only keeps structural metadata: which tool was called, whether it succeeded, and brief args preview.
 * This creates a "false memory protection" — the agent knows WHAT tools it used but not the raw
 * content, so it works from its notes instead of re-reading files.
 *
 * COMPRESSION MARKERS:
 * Compressed messages are tagged with `[COMPRESSED: ...]` markers so the agent can recognize
 * that it has already performed these actions and should work from notes rather than re-read.
 * This is the key insight: aggressive compression + explicit markers prevent re-read loops.
 *
 * FLOW:
 * 1. Transcript exceeds budget → aggressive compression kicks in
 * 2. All old TOOL messages (except protected recent N) are reduced to:
 *    `[COMPRESSED: tool_name(success: bool, args: preview)]`
 * 3. ASSISTANT messages are reduced to their TEXT portion (reasoning) only
 * 4. Recent messages stay verbatim — the agent is still actively working with them
 * 5. Post-compression: a CRITICAL note is injected telling the agent to work from notes
 *
 * FALLBACK:
 * If no budget calculation is possible or compression fails, falls back to simple truncation.
 *
 * The character budget is derived from the model's context window (~4 chars/token) minus the output
 * reservation, so the transcript is sized to the model actually in use.
 */
object ContextCompressor {

    private const val CHARS_PER_TOKEN = 4
    private const val SAFETY = 0.5
    private const val KEEP_RECENT = 8
    private const val TRUNCATED_HEAD = 400
    private const val MIN_BUDGET = 20_000

    /**
     * MARKER-BASED COMPRESSION:
     * Tool results are compressed to ONLY their metadata: tool name, success status, brief args.
     * This creates explicit "I did this already" markers that prevent re-read loops.
     *
     * Example: a 5000-char read_file output becomes:
     *   [COMPRESSED: read_file(success: true, args: target_file=Foo.kt:1-100)]
     *
     * This is INTENTIONALLY minimal — the agent must work from notes, not from re-reading.
     */
    private const val COMPRESSED_MARKER_PREFIX = "[COMPRESSED: "
    private const val COMPRESSED_MARKER_SUFFIX = "]"
    private const val MAX_ARGS_PREVIEW = 80  // characters for args preview in marker

    /**
     * Legacy eager truncation: still used as intermediate step before aggressive compression,
     * and as fallback when aggressive compression isn't applicable.
     */
    private const val EAGER_KEEP_RECENT_TOOLS = 8
    private const val EAGER_TOOL_HEAD = 2_000
    private const val EAGER_TRUNC_MARKER = "…[truncated to save context]"

    /** Summarises a rendered transcript segment into a dense recap. Returns null/blank to decline. */
    fun interface Summarizer {
        fun summarize(renderedSegment: String): String
    }

    /** Compression metrics from the last compress() call. */
    data class CompressionMetrics(
        val inputChars: Int,
        val outputChars: Int,
        val ratio: Float,
        val method: String, // "none", "eager", "summarize", "truncate"
    )

    @Volatile
    var lastMetrics: CompressionMetrics? = null
        private set

    fun compress(
        messages: List<ChatMessage>,
        contextWindowTokens: Int,
        outputReserveTokens: Int,
        systemPromptChars: Int = 0,
        summarizer: Summarizer? = null,
        activeTask: String = "",
    ): List<ChatMessage> {
        val budget = charBudget(contextWindowTokens, outputReserveTokens, systemPromptChars)
        val originalChars = estimateChars(messages)

        if (originalChars <= budget) {
            lastMetrics = CompressionMetrics(originalChars, originalChars, 1.0f, "none")
            return messages
        }

        val aggressiveResult = aggressiveCompress(messages, budget)
        if (estimateChars(aggressiveResult) <= budget) {
            val outputChars = estimateChars(aggressiveResult)
            lastMetrics = CompressionMetrics(
                inputChars = originalChars,
                outputChars = outputChars,
                ratio = outputChars.toFloat() / originalChars,
                method = "aggressive"
            )
            return aggressiveResult
        }

        if (summarizer != null) {
            val eagerlyTrimmed = eagerlyTruncateOldToolResults(messages)
            val summarised = runCatching { summarizeOldContext(messages, summarizer, activeTask) }.getOrNull()
                ?: runCatching { summarizeOldContext(eagerlyTrimmed, summarizer, activeTask) }.getOrNull()
            if (summarised != null && summarised !== messages) {
                val trimmed = eagerlyTruncateOldToolResults(summarised)
                val final = if (estimateChars(trimmed) <= budget) trimmed else truncateToBudget(trimmed, budget)
                val outputChars = estimateChars(final)
                lastMetrics = CompressionMetrics(originalChars, outputChars, outputChars.toFloat() / originalChars, "summarize")
                return final
            }
        }

        val result = truncateToBudget(eagerlyTruncateOldToolResults(messages), budget)
        val outputChars = estimateChars(result)
        lastMetrics = CompressionMetrics(originalChars, outputChars, outputChars.toFloat() / originalChars, "truncate")
        return result
    }

    /**
     * AGGRESSIVE MARKER-BASED COMPRESSION:
     *
     * Compresses old messages (outside protected recent window) to markers:
     * - TOOL messages → `[COMPRESSED: tool_name(success: bool, args: preview)]`
     * - ASSISTANT messages → keep TEXT blocks only (reasoning), drop tool_use details
     * - USER messages → keep as-is (they're small)
     *
     * The protected window (KEEP_RECENT messages) stays verbatim — the agent is actively
     * working with these and needs full details.
     *
     * This creates explicit "I did this already" markers that prevent re-read loops:
     * the agent sees it called read_file but NOT the file content, so it must work
     * from notes rather than re-reading.
     */
    private fun aggressiveCompress(messages: List<ChatMessage>, budget: Int): List<ChatMessage> {
        if (messages.size <= KEEP_RECENT) {
            return messages  // Nothing to compress
        }

        val toCompress = messages.dropLast(KEEP_RECENT)
        val recent = messages.takeLast(KEEP_RECENT)

        val compressed = toCompress.map { msg ->
            when (msg.role) {
                Role.TOOL -> compressToolMessage(msg)
                Role.ASSISTANT -> compressAssistantMessage(msg)
                Role.USER, Role.SYSTEM -> msg  // Keep as-is
            }
        }

        return compressed + recent
    }

    /**
     * Compress a TOOL message to markers.
     * Each ToolResult block becomes: `[COMPRESSED: tool_name(success: status, args: preview)]`
     *
     * The tool name and success status are extracted from the result text:
     * - Success pattern: look for typical success indicators (file content shown, operation succeeded)
     * - Error indicator: explicit "Tool error:" or "Error:" prefix
     *
     * Args preview: try to extract file path or key parameter from result text.
     */
    private fun compressToolMessage(msg: ChatMessage): ChatMessage {
        val compressed = msg.content.map { block ->
            when (block) {
                is ContentBlock.ToolResult -> {
                    // Already compressed? Don't re-compress.
                    if (block.content.startsWith(COMPRESSED_MARKER_PREFIX)) {
                        block
                    } else {
                        val toolName = extractToolName(block.content)
                        val success = !block.isError && !block.content.startsWith("Tool error:")
                        val argsPreview = extractArgsPreview(block.content)
                        val marker = buildCompressedMarker(toolName, success, argsPreview)
                        block.copy(content = marker)
                    }
                }
                else -> block
            }
        }
        return msg.copy(content = compressed)
    }

    /**
     * Compress an ASSISTANT message: keep TEXT blocks (reasoning), truncate tool_use blocks.
     *
     * Tool_use blocks become markers: `[COMPRESSED: tool_call(name, args_preview)]`
     * Text blocks are kept (but capped at 500 chars each for very long reasoning).
     */
    private fun compressAssistantMessage(msg: ChatMessage): ChatMessage {
        val compressed = msg.content.map { block ->
            when (block) {
                is ContentBlock.Text -> {
                    // Keep reasoning but cap very long text
                    if (block.text.length > 500) {
                        block.copy(text = block.text.take(500) + "\n[…reasoning truncated]")
                    } else {
                        block
                    }
                }
                is ContentBlock.ToolUse -> {
                    // Convert tool_use to marker
                    val argsPreview = extractJsonArgsPreview(block.inputJson)
                    val marker = "[COMPRESSED: tool_call(${block.name}, args: $argsPreview)]"
                    ContentBlock.Text(marker)
                }
                else -> block
            }
        }
        return msg.copy(content = compressed)
    }

    /** Extract tool name from tool result content. */
    private fun extractToolName(content: String): String {
        // Try common patterns: "read_file returned:", "Tool: search_text", etc.
        val patterns = listOf(
            Regex("""^(\w+)(?:\s+returned|:)"""),
            Regex("""Tool:\s*(\w+)"""),
            Regex("""^(\w+)\s*[\(\[]""")
        )
        for (pattern in patterns) {
            pattern.find(content.take(50))?.let { return it.groupValues[1] }
        }
        return "unknown_tool"
    }

    /** Extract args preview from tool result content (file path, line numbers, etc.). */
    private fun extractArgsPreview(content: String): String {
        // Try to extract file path
        val filePattern = Regex("""(?:file|path|target)[:\s]+([^\s,\]]+)""", RegexOption.IGNORE_CASE)
        filePattern.find(content)?.let {
            return "target_file=${it.groupValues[1].take(MAX_ARGS_PREVIEW - 15)}"
        }

        // Try to extract search term
        val searchPattern = Regex("""(?:search|query|snippet)[:\s]+([^\s,\]]+)""", RegexOption.IGNORE_CASE)
        searchPattern.find(content)?.let {
            return "search=${it.groupValues[1].take(MAX_ARGS_PREVIEW - 10)}"
        }

        // Fallback: first few words
        return content.take(MAX_ARGS_PREVIEW).replace("\n", " ").trim()
    }

    /** Extract args preview from JSON input string. */
    private fun extractJsonArgsPreview(json: String): String {
        if (json.isBlank() || json == "{}") return "(no args)"

        // Try to extract key parameters
        val preview = mutableListOf<String>()

        // File path
        Regex(""""(?:target_file|path|file|directory_path)":\s*"([^"]+)"""").find(json)
            ?.let { preview.add("target=${it.groupValues[1]}") }

        // Line range
        val start = Regex(""""start_line":\s*(\d+)""").find(json)?.groupValues?.get(1)
        val end = Regex(""""end_line":\s*(\d+)""").find(json)?.groupValues?.get(1)
        if (start != null && end != null) {
            preview.add("lines=$start-$end")
        }

        // Search term
        Regex(""""(?:text_snippet|query|search)":\s*"([^"]{1,30})""").find(json)
            ?.let { preview.add("search=${it.groupValues[1]}") }

        return if (preview.isNotEmpty()) {
            preview.joinToString(", ").take(MAX_ARGS_PREVIEW)
        } else {
            json.take(MAX_ARGS_PREVIEW).replace("\n", " ")
        }
    }

    /** Build the compressed marker string. */
    private fun buildCompressedMarker(toolName: String, success: Boolean, argsPreview: String): String {
        val status = if (success) "success" else "error"
        return "$COMPRESSED_MARKER_PREFIX$toolName($status: $status, args: $argsPreview)$COMPRESSED_MARKER_SUFFIX"
    }

    /**
     * Replace every tool_result older than the last [EAGER_KEEP_RECENT_TOOLS] TOOL messages with its
     * head ([EAGER_TOOL_HEAD] chars). Operates on a shallow copy; returns the original list when no
     * trimming applies, so identity-equality short-circuits work upstream.
     *
     * This is used as an intermediate step before summarisation fallback, but aggressive compression
     * (above) completely replaces this in the primary path.
     */
    private fun eagerlyTruncateOldToolResults(messages: List<ChatMessage>): List<ChatMessage> {
        val toolIndices = messages.withIndex().filter { it.value.role == Role.TOOL }.map { it.index }
        if (toolIndices.size <= EAGER_KEEP_RECENT_TOOLS) return messages
        val protectedIndices = toolIndices.takeLast(EAGER_KEEP_RECENT_TOOLS).toSet()
        var modified = false
        val result = messages.mapIndexed { index, message ->
            if (message.role != Role.TOOL || index in protectedIndices) return@mapIndexed message
            val shrunk = message.content.map { block ->
                if (block is ContentBlock.ToolResult && block.content.length > EAGER_TOOL_HEAD &&
                    !block.content.endsWith(EAGER_TRUNC_MARKER) &&
                    !block.content.startsWith(COMPRESSED_MARKER_PREFIX)  // Don't re-truncate already compressed
                ) {
                    modified = true
                    block.copy(content = block.content.take(EAGER_TOOL_HEAD) + "\n" + EAGER_TRUNC_MARKER)
                } else {
                    block
                }
            }
            if (shrunk === message.content) message else message.copy(content = shrunk)
        }
        return if (modified) result else messages
    }

    /**
     * Replace messages[0 until tailStart] with a single recap, keeping only the most recent turns
     * verbatim. The first message is NOT sacred — it is included in the summarizable segment so
     * the model does not anchor on a stale initial query. The active task (injected separately by
     * [AgentLoop]) is the sole task anchor. The cut is moved off any TOOL message so no tool_result
     * is left orphaned from its tool_use (which would make the transcript invalid).
     */
    private fun summarizeOldContext(messages: List<ChatMessage>, summarizer: Summarizer, activeTask: String = ""): List<ChatMessage> {
        val n = messages.size
        var tailStart = (n - KEEP_RECENT).coerceAtLeast(1)
        while (tailStart in 1 until n && messages[tailStart].role == Role.TOOL) tailStart--
        if (tailStart <= 1) return messages // nothing summarisable before the recent tail

        val middle = messages.subList(0, tailStart)
        val digest = TranscriptAnalyzer.analyze(middle, activeTask)
        val rendered = digest.renderForSummarizer()
        // Fallback: if the digest is too sparse, use the flat render.
        val inputForSummarizer = if (rendered.length > 50) rendered else renderForSummary(middle)
        val rawRecap = summarizer.summarize(inputForSummarizer).trim()
        if (rawRecap.isBlank()) return messages

        // Post-process: try to parse as structured SemanticSummary; falls back to raw text.
        val recap = SemanticCompressor.parseAndRender(rawRecap)

        // Prepend the active task prominently so the model never loses sight of it.
        val taskPrefix = if (activeTask.isNotBlank()) {
            "[CURRENT ACTIVE TASK — DO NOT LOSE THIS:]\n$activeTask\n\n"
        } else ""

        val summaryMessage = ChatMessage(
            Role.USER,
            listOf(ContentBlock.Text("${taskPrefix}[Structured summary of earlier steps — older detail compacted to save context]\n$recap")),
        )
        return buildList {
            add(summaryMessage)
            addAll(messages.subList(tailStart, n))
        }
    }

    /** Flatten a transcript segment to plain text for the summariser. */
    private fun renderForSummary(segment: List<ChatMessage>): String = buildString {
        for (message in segment) {
            when (message.role) {
                Role.USER -> message.text.takeIf { it.isNotBlank() }?.let { appendLine("USER: $it") }
                Role.ASSISTANT -> {
                    message.text.takeIf { it.isNotBlank() }?.let { appendLine("ASSISTANT: $it") }
                    message.toolUses.forEach { appendLine("CALL ${it.name}(${it.inputJson})") }
                }

                Role.TOOL -> message.content.filterIsInstance<ContentBlock.ToolResult>()
                    .forEach { appendLine("RESULT${if (it.isError) " (error)" else ""}: ${it.content}") }

                Role.SYSTEM -> Unit
            }
        }
    }

    // Truncation fallback (deterministic, no LLM)

    private fun truncateToBudget(messages: List<ChatMessage>, budget: Int): List<ChatMessage> {
        if (estimateChars(messages) <= budget) return messages
        val working = messages.toMutableList()
        val protectedFrom = (working.size - KEEP_RECENT).coerceAtLeast(0)
        compactRange(working, protectedFrom, budget) { it.role == Role.TOOL }
        if (estimateChars(working) <= budget) return working
        compactRange(working, protectedFrom, budget) { it.role == Role.ASSISTANT || it.role == Role.USER }
        if (estimateChars(working) <= budget) return working
        compactRange(working, working.size, budget) { true }
        return working
    }

    private inline fun compactRange(
        working: MutableList<ChatMessage>,
        protectedFrom: Int,
        budget: Int,
        eligible: (ChatMessage) -> Boolean,
    ) {
        for (index in 0 until protectedFrom) {
            if (estimateChars(working) <= budget) return
            // No message is sacred — all old messages (including the original task) are eligible
            // for truncation. The active task is injected separately by AgentLoop.
            val message = working[index]
            if (!eligible(message)) continue
            working[index] = message.copy(content = message.content.map(::truncateBlock))
        }
    }

    private fun truncateBlock(block: ContentBlock): ContentBlock = when (block) {
        is ContentBlock.ToolResult ->
            if (block.content.length > TRUNCATED_HEAD) {
                block.copy(content = block.content.take(TRUNCATED_HEAD) + "\n…[older tool output truncated to save context]")
            } else {
                block
            }

        is ContentBlock.Text ->
            if (block.text.length > TRUNCATED_HEAD) {
                ContentBlock.Text(block.text.take(TRUNCATED_HEAD) + "\n…[older message truncated to save context]")
            } else {
                block
            }

        is ContentBlock.Image -> block   // images are never truncated
        is ContentBlock.ToolUse -> block
    }

    private fun charBudget(contextWindowTokens: Int, outputReserveTokens: Int, systemPromptChars: Int = 0): Int {
        val usableTokens = (contextWindowTokens - outputReserveTokens).coerceAtLeast(0)
        val totalBudget = (usableTokens * CHARS_PER_TOKEN * SAFETY).toInt().coerceAtLeast(MIN_BUDGET)
        return (totalBudget - systemPromptChars).coerceAtLeast(MIN_BUDGET)
    }

    /** Rough token estimate of a transcript (~chars/token) — for the usage panel and manual /compact. */
    fun estimatedTokens(messages: List<ChatMessage>): Int = estimateChars(messages) / CHARS_PER_TOKEN

    private fun estimateChars(messages: List<ChatMessage>): Int =
        messages.sumOf { message -> message.content.sumOf(::blockLength) }

    private fun blockLength(block: ContentBlock): Int = when (block) {
        is ContentBlock.Text -> block.text.length
        is ContentBlock.ToolUse -> block.name.length + block.inputJson.length
        is ContentBlock.Image -> block.base64Data.length  // rough size estimate
        is ContentBlock.ToolResult -> block.content.length
    }
}
