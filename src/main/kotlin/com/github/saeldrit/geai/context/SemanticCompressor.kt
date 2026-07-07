package com.github.saeldrit.geai.context

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.intellij.openapi.diagnostic.thisLogger
import java.io.StringReader

/**
 * Parses an LLM response into a [SemanticSummary] and renders it for transcript injection.
 *
 * The LLM is asked to return JSON matching the [SemanticSummary] schema. If the response is valid
 * JSON the structured summary is rendered via [SemanticSummary.renderForInjection]; otherwise the
 * raw text is returned as-is (plain-text fallback — never lose context because of formatting).
 *
 * This object does NOT call the LLM itself — it provides the prompt doctrine and handles parsing.
 * The actual LLM call is made by the [ContextCompressor.Summarizer] implementation.
 */
object SemanticCompressor {

    /**
     * System prompt for the structured compression call. Instructs the model to return JSON matching
     * the [SemanticSummary] schema. Deliberately strict about format — fallback handles misses.
     */
    const val DOCTRINE: String =
        "You compress an AI coding agent's conversation transcript into a structured JSON summary. " +
            "Your output must be a SINGLE JSON object (no markdown fences, no preamble, no commentary) " +
            "with exactly these fields:\n" +
            "{\n" +
            "  \"taskDescription\": \"one-line description of the task\",\n" +
            "  \"findings\": [{\"location\": \"file:line\", \"summary\": \"what was found\"}],\n" +
            "  \"decisions\": [\"decision made\"],\n" +
            "  \"codeChanges\": [\"file modified: what changed\"],\n" +
            "  \"openQuestions\": [\"unresolved item\"],\n" +
            "  \"nextSteps\": [\"planned action\"],\n" +
            "  \"userPreferences\": [\"durable user preference or skill to preserve\"]\n" +
            "}\n\n" +
            "Rules:\n" +
            "- Preserve ALL file:line locations — these are critical for the agent to keep working.\n" +
            "- Preserve the ORIGINAL task description — what the user asked for.\n" +
            "- Keep findings CONCRETE: what was observed at each location, not vague summaries.\n" +
            "- Keep decisions ACTIONABLE: what was decided and why (one line each).\n" +
            "- Drop: raw file contents, tool call details, chatter, thinking, and filler.\n" +
            "- Be terse. Each array element is one short line. The goal is maximum information density.\n" +
            "- If a field has no entries, use an empty array [].\n" +
            "- Output ONLY the JSON object. No explanation before or after."

    /** Max output tokens for the compression call — enough for a structured summary, not more. */
    const val MAX_TOKENS: Int = 2000

    /** Volatile field holding user preferences extracted during the last compression parse. Cleared by the caller after processing. */
    @Volatile var lastExtractedPreferences: List<String> = emptyList()

    private val PREFERENCE_PATTERNS = listOf(
        // Language
        Regex("(?:отвечай|пиши|говори|respond|reply|answer)\\s+(?:на\\s+)?русском", RegexOption.IGNORE_CASE) to "Respond in Russian",
        Regex("(?:ответ[а-я]+|реализац[а-я]+|пиши|respond|write|code)\\s+(?:на\\s+)?английском", RegexOption.IGNORE_CASE) to "Respond in English",
        // Style
        Regex("(?:не|без|никаких?|не надо|не нужно|no|without|skip)\\s+(?:комментари[а-я]+|comments?)", RegexOption.IGNORE_CASE) to "No code comments",
        Regex("(?:добавляй|пиши|add|write|include)\\s+(?:комментари[а-я]+|comments?)", RegexOption.IGNORE_CASE) to "Add code comments",
        Regex("(?:таб[а-я]+|tabs?)\\s+(?:вместо|instead|prefer)", RegexOption.IGNORE_CASE) to "Use tabs for indentation",
        Regex("(?:пробел[а-я]+|spaces?)\\s+(?:вместо|instead|prefer)", RegexOption.IGNORE_CASE) to "Use spaces for indentation",
        Regex("(?:функциональный?|functional)\\s+(?:стиль|style|подход)", RegexOption.IGNORE_CASE) to "Prefer functional style",
        Regex("(?:императивн|imperative)\\s+(?:стиль|style|подход)", RegexOption.IGNORE_CASE) to "Prefer imperative style",
        Regex("(?:идиоматичн|idiomatic)\\s+(?:kotlin|java|python|код)", RegexOption.IGNORE_CASE) to "Use idiomatic code style",
        // Testing
        Regex("(?:без|никаких?|не добавляй?|no|skip|without)\\s+(?:тест[а-я]+|tests?|unit.?test)", RegexOption.IGNORE_CASE) to "Skip tests",
        Regex("(?:пиши|добавляй|write|add|include)\\s+(?:тест[а-я]+|tests?)", RegexOption.IGNORE_CASE) to "Write tests",
        Regex("(?:tdd|test.driven|test.first)", RegexOption.IGNORE_CASE) to "Use TDD approach",
        // Response style
        Regex("(?:verbose|подробно|детально|detailed?)", RegexOption.IGNORE_CASE) to "Give detailed explanations",
        Regex("(?:кратко|tersely?|briefly?|concise|коротко)", RegexOption.IGNORE_CASE) to "Keep responses brief",
        // Architecture
        Regex("(?:clean\\s*architecture|чистая\\s+архитектур)", RegexOption.IGNORE_CASE) to "Use clean architecture",
        Regex("(?:mvvm|model.view.viewmodel)", RegexOption.IGNORE_CASE) to "Use MVVM pattern",
        Regex("(?:repository\\s*pattern|паттерн\\s+репозитор)", RegexOption.IGNORE_CASE) to "Use repository pattern",
        Regex("(?:dependency\\s*injection|di|внедрение\\s+зависимост)", RegexOption.IGNORE_CASE) to "Use dependency injection",
        // Error handling
        Regex("(?:result\\s*type|sealed\\s*class.*result|result\\s*<)", RegexOption.IGNORE_CASE) to "Use Result types for errors",
        Regex("(?:try.catch|exceptions?|исключения)", RegexOption.IGNORE_CASE) to "Use exceptions for errors",
        // Code generation
        Regex("(?:data\\s*class|data\\s*class вместо)", RegexOption.IGNORE_CASE) to "Prefer data classes",
        Regex("(?:sealed\\s*(?:class|interface)|sealed\\s*вместо)", RegexOption.IGNORE_CASE) to "Prefer sealed types",
        Regex("(?:extension\\s*function|функции\\s*расширения)", RegexOption.IGNORE_CASE) to "Use extension functions",
        // Async
        Regex("(?:coroutines?|корутин[а-я]+|suspend\\s*fun)", RegexOption.IGNORE_CASE) to "Use coroutines for async",
        Regex("(?:rxjava|reactive|observable)", RegexOption.IGNORE_CASE) to "Use reactive programming",
        // Naming
        Regex("(?:camelCase|camel\\s*case|верблюжий)", RegexOption.IGNORE_CASE) to "Use camelCase naming",
        Regex("(?:snake_case|snake\\s*case|змеиный)", RegexOption.IGNORE_CASE) to "Use snake_case naming",
        // Logging
        Regex("(?:без|никаких?|no|skip)\\s+(?:лог[а-я]+|logging?|print|println)", RegexOption.IGNORE_CASE) to "No logging statements",
        Regex("(?:добавляй|используй|add|use)\\s+(?:лог[а-я]+|logging?)", RegexOption.IGNORE_CASE) to "Add logging",
    )

    /** Scan [text] for common user preference patterns. */
    fun detectPreferences(text: String): List<String> = PREFERENCE_PATTERNS.mapNotNull { (pattern, label) ->
        if (pattern.containsMatchIn(text)) label else null
    }.distinct()

    /** Extract user preferences from all user messages in the transcript. */
    fun extractPreferencesFromTranscript(messages: List<com.github.saeldrit.geai.llm.ChatMessage>): List<String> {
        val userText = messages.filter { it.role == com.github.saeldrit.geai.llm.Role.USER }
            .joinToString("\n") { it.text }
        return detectPreferences(userText)
    }

    /**
     * Try to parse [llmResponse] as a [SemanticSummary] JSON and render it for injection.
     * Returns the structured rendering on success, or the raw [llmResponse] as-is on any failure.
     */
    fun parseAndRender(llmResponse: String): String {
        val summary = tryParse(llmResponse) ?: return llmResponse
        val quality = validateQuality(summary)
        thisLogger().debug("SemanticCompressor: quality=$quality")
        // If summary is too low quality (no findings, no task), fall back to raw text
        if (!quality.isAcceptable) {
            thisLogger().debug("SemanticCompressor: summary quality too low, using raw text")
            return llmResponse
        }
        val rendered = summary.renderForInjection()
        // Sanity: if structured rendering is somehow longer than the raw response, prefer the raw one.
        return if (rendered.length <= llmResponse.length + 200) rendered else llmResponse
    }

    /** Quality metrics for a parsed summary. */
    data class QualityReport(
        val hasTask: Boolean,
        val findingCount: Int,
        val findingsWithLocation: Int,
        val decisionCount: Int,
        val changeCount: Int,
    ) {
        /** Acceptable only if task exists AND at least some findings have locations. */
        val isAcceptable: Boolean get() = hasTask && findingCount > 0 && findingsWithLocation > 0
        /** Quality score 0-100 for metrics tracking. */
        val score: Int get() {
            var s = 0
            if (hasTask) s += 20
            if (findingCount > 0) s += 20
            if (findingsWithLocation > 0) s += 20
            if (findingsWithLocation >= findingCount / 2) s += 15 // most findings have locations
            if (decisionCount > 0) s += 10
            if (changeCount > 0) s += 15
            return s
        }
    }

    @Volatile
    var lastQualityReport: QualityReport? = null
        private set

    /** Validate that a parsed summary contains useful content. */
    fun validateQuality(summary: SemanticSummary): QualityReport {
        val report = QualityReport(
            hasTask = summary.taskDescription.isNotBlank(),
            findingCount = summary.findings.size,
            findingsWithLocation = summary.findings.count { it.location.isNotBlank() },
            decisionCount = summary.decisions.size,
            changeCount = summary.codeChanges.size,
        )
        lastQualityReport = report
        return report
    }

    /**
     * Attempt to parse an LLM response as [SemanticSummary]. Handles:
     * - Raw JSON: `{"taskDescription": ...}`
     * - Markdown-wrapped: `` ```json\n{...}\n``` ``
     * - Returns null on any parse failure (caller falls back to raw text).
     */
    internal fun tryParse(raw: String): SemanticSummary? = runCatching {
        val json = extractJson(raw) ?: return null
        val obj = parseObject(json)
        val result = SemanticSummary(
            taskDescription = obj.stringOrEmpty("taskDescription"),
            findings = obj.arrayOrEmpty("findings").mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val o = el.asJsonObject
                Finding(
                    location = o.stringOrEmpty("location"),
                    summary = o.stringOrEmpty("summary"),
                )
            },
            decisions = obj.stringArray("decisions"),
            codeChanges = obj.stringArray("codeChanges"),
            openQuestions = obj.stringArray("openQuestions"),
            nextSteps = obj.stringArray("nextSteps"),
            userPreferences = obj.stringArray("userPreferences"),
        )
        lastExtractedPreferences = result.userPreferences
        result
    }.onFailure {
        thisLogger().debug("SemanticCompressor: failed to parse LLM response as JSON", it)
    }.getOrNull()

    // ---- internal helpers ----

    /** Extract the JSON object from a response that may be wrapped in markdown code fences. */
    private fun extractJson(raw: String): String? {
        val trimmed = raw.trim()
        // Direct JSON object
        if (trimmed.startsWith("{")) return trimmed
        // Markdown-wrapped: ```json\n{...}\n``` or ```\n{...}\n```
        val fencePattern = Regex("```(?:json)?\\s*\\n?(\\{.*?})\\s*\\n?```", RegexOption.DOT_MATCHES_ALL)
        fencePattern.find(trimmed)?.let { return it.groupValues[1].trim() }
        // Last resort: find the first { ... } block
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start >= 0 && end > start) return trimmed.substring(start, end + 1)
        return null
    }

    private fun parseObject(json: String): JsonObject =
        JsonParser.parseReader(JsonReader(StringReader(json)).apply { isLenient = true }).asJsonObject

    private fun JsonObject.stringOrEmpty(key: String): String =
        get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString.orEmpty()

    private fun JsonObject.arrayOrEmpty(key: String): List<com.google.gson.JsonElement> {
        val arr = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return (0 until arr.size()).map { arr[it] }
    }

    private fun JsonObject.stringArray(key: String): List<String> =
        arrayOrEmpty(key).mapNotNull { el ->
            if (el.isJsonPrimitive) el.asString else null
        }
}
