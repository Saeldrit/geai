package com.github.saeldrit.geai.context

import com.github.saeldrit.geai.llm.ChatMessage
import com.github.saeldrit.geai.llm.ContentBlock
import com.github.saeldrit.geai.llm.Role

object ContextCompressor {

    /** Fallback density until the first provider response calibrates it. */
    private const val CHARS_PER_TOKEN_DEFAULT = 4.0

    /**
     * Observed chars-per-token, calibrated from real provider usage each turn. A fixed /4 is
     * systematically optimistic for code and Cyrillic (~3–3.5), so estimates undercount, the
     * 70% trigger fires too late, and the provider rejects the request — forcing the emergency
     * half-window compress. Clamped to a sane band against pathological samples.
     */
    @Volatile
    private var charsPerToken: Double = CHARS_PER_TOKEN_DEFAULT

    /** Feed real usage back: [chars] of rendered prompt that the provider billed as [inputTokens]. */
    fun calibrate(chars: Int, inputTokens: Int) {
        if (chars < 10_000 || inputTokens < 2_500) return // too small a sample to trust
        val observed = chars.toDouble() / inputTokens
        // EMA smoothing: one weird turn (huge tool schemas, images) must not swing the estimate.
        charsPerToken = (0.7 * charsPerToken + 0.3 * observed).coerceIn(2.0, 5.0)
    }

    /** High watermark: compaction fires only above this share of the usable window. */
    private const val TRIGGER = 0.70

    /**
     * Low watermark: when compaction fires, shrink to THIS share, not to [TRIGGER]. With
     * trigger == target the transcript lands just under the line and the next turn re-crosses
     * it — firing a blocking summarizer LLM call and a full prompt-cache-miss on EVERY turn
     * (compaction thrashing). The gap between the watermarks buys many quiet turns per event.
     */
    private const val TARGET = 0.50

    /**
     * Flat char-mass estimate for an image block (≈1.6k tokens). Counting base64 length is
     * catastrophically wrong: a single pasted screenshot is 200–700k base64 chars, which looks
     * like 50–170k "tokens" and pins the session in permanent compaction.
     */
    private const val IMAGE_CHARS_ESTIMATE = 6_400

    private const val KEEP_RECENT_DEFAULT = 14
    private const val KEEP_RECENT_MIN = 6
    private const val TRUNCATED_HEAD = 800
    private const val MIN_BUDGET = 40_000

    const val ACTIVE_TASK_PREFIX = "[CURRENT ACTIVE TASK — DO NOT LOSE THIS:]"
    const val RECAP_PREFIX = "[Structured summary of earlier steps — older detail compacted to save context]"
    const val SESSION_MEMORY_PREFIX = "[SESSION MEMORY — what you already did; do NOT re-do these steps]"
    private const val SESSION_MEMORY_MARKER = "[SESSION MEMORY"

    fun interface Summarizer {
        fun summarize(renderedSegment: String): String
    }

    data class CompressionMetrics(
        val inputChars: Int,
        val outputChars: Int,
        val ratio: Float,
        val method: String,
    )

    @Volatile
    var lastMetrics: CompressionMetrics? = null
        private set

    /**
     * Metrics of the last REAL compression (method != "none"). [lastMetrics] is overwritten by every
     * no-op call, so reading it a turn later shows "none" even when a compression just happened —
     * status reporting must use THIS field.
     */
    @Volatile
    var lastCompressionMetrics: CompressionMetrics? = null
        private set

    private fun record(metrics: CompressionMetrics) {
        lastMetrics = metrics
        if (metrics.method != "none") lastCompressionMetrics = metrics
    }

    fun compress(
        messages: List<ChatMessage>,
        contextWindowTokens: Int,
        outputReserveTokens: Int,
        systemPromptChars: Int = 0,
        summarizer: Summarizer? = null,
        activeTask: String = "",
    ): List<ChatMessage> {
        val triggerBudget = charBudget(contextWindowTokens, outputReserveTokens, systemPromptChars, TRIGGER)
        val budget = charBudget(contextWindowTokens, outputReserveTokens, systemPromptChars, TARGET)
        val originalChars = estimateChars(messages)

        if (originalChars <= triggerBudget) {
            record(CompressionMetrics(originalChars, originalChars, 1.0f, "none"))
            return messages
        }

        val keepRecent = keepRecentFor(budget, messages.size)
        var tailStart = (messages.size - keepRecent).coerceAtLeast(0)
        while (tailStart > 0 && messages[tailStart].role == Role.TOOL) tailStart--

        if (tailStart <= 0) {
            val result = truncateToBudget(messages.toList(), budget)
            val outputChars = estimateChars(result)
            record(CompressionMetrics(originalChars, outputChars, outputChars.toFloat() / originalChars.coerceAtLeast(1), "truncate"))
            return result
        }

        val old = messages.subList(0, tailStart).filterNot(::isSyntheticMemory)
        val tail = messages.subList(tailStart, messages.size).toList()
        val digest = TranscriptAnalyzer.analyze(old, activeTask)

        val recapBlock = if (summarizer != null) {
            val rendered = digest.renderForSummarizer()
            val input = if (rendered.length > 50) rendered else renderForSummary(old)
            val raw = runCatching { summarizer.summarize(input).trim() }.getOrDefault("")
            if (raw.isNotBlank()) SemanticCompressor.parseAndRender(raw) else ""
        } else {
            ""
        }

        val summaryText = buildString {
            if (activeTask.isNotBlank()) {
                appendLine(ACTIVE_TASK_PREFIX)
                appendLine(activeTask)
                appendLine()
            }
            if (recapBlock.isNotBlank()) {
                appendLine(RECAP_PREFIX)
                appendLine(recapBlock)
                appendLine()
            }
            appendLine(SESSION_MEMORY_PREFIX)
            append(digest.renderForAgent())
        }.trim()
        val summaryMessage = ChatMessage(Role.USER, listOf(ContentBlock.Text(summaryText)))

        var result: List<ChatMessage> = listOf(summaryMessage) + tail
        var method = if (recapBlock.isNotBlank()) "summarize" else "ledger"
        if (estimateChars(result) > budget) {
            result = truncateToBudget(result, budget)
            method = "truncate"
        }
        val outputChars = estimateChars(result)
        record(
            CompressionMetrics(
                inputChars = originalChars,
                outputChars = outputChars,
                ratio = outputChars.toFloat() / originalChars.coerceAtLeast(1),
                method = method,
            ),
        )
        return result
    }

    private fun keepRecentFor(budget: Int, messageCount: Int): Int {
        val byBudget = (budget / 2_500).coerceIn(KEEP_RECENT_MIN, KEEP_RECENT_DEFAULT)
        return minOf(byBudget, messageCount.coerceAtLeast(1))
    }

    private fun isSyntheticMemory(msg: ChatMessage): Boolean {
        if (msg.role != Role.USER) return false
        val text = msg.text
        return text.startsWith(SESSION_MEMORY_MARKER) ||
            text.contains(RECAP_PREFIX) ||
            text.startsWith("[CURRENT ACTIVE TASK")
    }

    private fun renderForSummary(segment: List<ChatMessage>): String = buildString {
        for (message in segment) {
            when (message.role) {
                Role.USER -> message.text.takeIf { it.isNotBlank() }?.let { appendLine("USER: $it") }
                Role.ASSISTANT -> {
                    message.text.takeIf { it.isNotBlank() }?.let { appendLine("ASSISTANT: $it") }
                    message.toolUses.forEach { appendLine("CALL ${it.name}(${it.inputJson.take(200)})") }
                }
                Role.TOOL -> message.content.filterIsInstance<ContentBlock.ToolResult>()
                    .forEach { appendLine("RESULT${if (it.isError) " (error)" else ""}: ${it.content.take(1_000)}") }
                Role.SYSTEM -> Unit
            }
        }
    }

    private fun truncateToBudget(messages: List<ChatMessage>, budget: Int): List<ChatMessage> {
        if (estimateChars(messages) <= budget) return messages
        val working = messages.toMutableList()
        compactRange(working, budget) { it.role == Role.TOOL }
        if (estimateChars(working) <= budget) return working
        compactRange(working, budget) { it.role == Role.ASSISTANT || it.role == Role.USER }
        if (estimateChars(working) <= budget) return working

        while (estimateChars(working) > budget && working.size > 2) {
            val dropIdx = working.indexOfFirst { !isSyntheticMemory(it) }
            if (dropIdx < 0 || dropIdx >= working.size - 1) break
            val drop = working[dropIdx]
            working.removeAt(dropIdx)
            if (drop.role == Role.ASSISTANT &&
                drop.toolUses.isNotEmpty() &&
                dropIdx < working.size &&
                working[dropIdx].role == Role.TOOL
            ) {
                working.removeAt(dropIdx)
            }
        }
        return working
    }

    private inline fun compactRange(
        working: MutableList<ChatMessage>,
        budget: Int,
        eligible: (ChatMessage) -> Boolean,
    ) {
        var total = estimateChars(working)
        for (index in working.indices) {
            if (total <= budget) return
            val message = working[index]
            if (!eligible(message)) continue
            if (isSyntheticMemory(message)) continue
            val before = message.content.sumOf(::blockLength)
            val compacted = message.copy(content = message.content.map(::truncateBlock))
            working[index] = compacted
            total -= before - compacted.content.sumOf(::blockLength)
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
        is ContentBlock.Image -> block
        is ContentBlock.ToolUse -> block
    }

    private fun charBudget(
        contextWindowTokens: Int,
        outputReserveTokens: Int,
        systemPromptChars: Int = 0,
        fraction: Double = TRIGGER,
    ): Int {
        val usableTokens = (contextWindowTokens - outputReserveTokens).coerceAtLeast(0)
        val raw = (usableTokens * charsPerToken * fraction).toInt()
        val floored = if (contextWindowTokens >= 32_000) {
            raw.coerceAtLeast(MIN_BUDGET)
        } else {
            raw.coerceAtLeast(2_000)
        }
        return (floored - systemPromptChars).coerceAtLeast(2_000)
    }

    fun estimatedTokens(messages: List<ChatMessage>): Int =
        (estimateChars(messages) / charsPerToken).toInt()

    fun compactionThresholdTokens(contextWindowTokens: Int, outputReserveTokens: Int, systemPromptChars: Int = 0): Int =
        (charBudget(contextWindowTokens, outputReserveTokens, systemPromptChars) / charsPerToken).toInt()

    private fun estimateChars(messages: List<ChatMessage>): Int =
        messages.sumOf { message -> message.content.sumOf(::blockLength) }

    private fun blockLength(block: ContentBlock): Int = when (block) {
        is ContentBlock.Text -> block.text.length
        is ContentBlock.ToolUse -> block.name.length + block.inputJson.length
        is ContentBlock.Image -> IMAGE_CHARS_ESTIMATE
        is ContentBlock.ToolResult -> block.content.length
    }
}
