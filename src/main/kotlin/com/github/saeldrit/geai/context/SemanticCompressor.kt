package com.github.saeldrit.geai.context

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.intellij.openapi.diagnostic.thisLogger
import java.io.StringReader

object SemanticCompressor {

    const val DOCTRINE: String =
        "You compress an AI coding agent's conversation transcript into a structured JSON summary. " +
            "Your output must be a SINGLE JSON object (no markdown fences, no preamble, no commentary) " +
            "with exactly these fields:\n" +
            "{\n" +
            "  \"taskDescription\": \"one-line description of the CURRENT task (the one the agent is actively working on right now)\",\n" +
            "  \"findings\": [{\"location\": \"file:line\", \"summary\": \"what was found\"}],\n" +
            "  \"decisions\": [\"decision made\"],\n" +
            "  \"codeChanges\": [\"file modified: what changed\"],\n" +
            "  \"openQuestions\": [\"unresolved item\"],\n" +
            "  \"nextSteps\": [\"planned action\"],\n" +
            "  \"userPreferences\": [\"durable user preference or skill to preserve\"]\n" +
            "}\n\n" +
            "CRITICAL Rules:\n" +
            "- taskDescription MUST be the CURRENT active task (what the agent is working on NOW), NOT earlier tasks.\n" +
            "- If there is an ACTIVE_TASK field in the input, use it as taskDescription verbatim.\n" +
            "- Preserve ALL file:line locations — these are critical for the agent to keep working.\n" +
            "- Keep findings CONCRETE: what was observed at each location, not vague summaries.\n" +
            "- Keep decisions ACTIONABLE: what was decided and why (one line each).\n" +
            "- Drop: raw file contents, tool call details, chatter, thinking, and filler.\n" +
            "- Be terse. Each array element is one short line. The goal is maximum information density.\n" +
            "- If a field has no entries, use an empty array [].\n" +
            "- Output ONLY the JSON object. No explanation before or after."

    const val MAX_TOKENS: Int = 2000

    @Volatile var lastExtractedPreferences: List<String> = emptyList()

    private val PREFERENCE_PATTERNS = listOf(
        Regex("(?:отвечай|пиши|говори|respond|reply|answer)\\s+(?:на\\s+)?русском", RegexOption.IGNORE_CASE) to "Respond in Russian",
        Regex("(?:ответ[а-я]+|реализац[а-я]+|пиши|respond|write|code)\\s+(?:на\\s+)?английском", RegexOption.IGNORE_CASE) to "Respond in English",
        Regex("(?:не|без|никаких?|не надо|не нужно|no|without|skip)\\s+(?:комментари[а-я]+|comments?)", RegexOption.IGNORE_CASE) to "No code comments",
        Regex("(?:добавляй|пиши|add|write|include)\\s+(?:комментари[а-я]+|comments?)", RegexOption.IGNORE_CASE) to "Add code comments",
        Regex("(?:таб[а-я]+|tabs?)\\s+(?:вместо|instead|prefer)", RegexOption.IGNORE_CASE) to "Use tabs for indentation",
        Regex("(?:пробел[а-я]+|spaces?)\\s+(?:вместо|instead|prefer)", RegexOption.IGNORE_CASE) to "Use spaces for indentation",
        Regex("(?:функциональный?|functional)\\s+(?:стиль|style|подход)", RegexOption.IGNORE_CASE) to "Prefer functional style",
        Regex("(?:императивн|imperative)\\s+(?:стиль|style|подход)", RegexOption.IGNORE_CASE) to "Prefer imperative style",
        Regex("(?:идиоматичн|idiomatic)\\s+(?:kotlin|java|python|код)", RegexOption.IGNORE_CASE) to "Use idiomatic code style",
        Regex("(?:без|никаких?|не добавляй?|no|skip|without)\\s+(?:тест[а-я]+|tests?|unit.?test)", RegexOption.IGNORE_CASE) to "Skip tests",
        Regex("(?:пиши|добавляй|write|add|include)\\s+(?:тест[а-я]+|tests?)", RegexOption.IGNORE_CASE) to "Write tests",
        Regex("(?:tdd|test.driven|test.first)", RegexOption.IGNORE_CASE) to "Use TDD approach",
        Regex("(?:verbose|подробно|детально|detailed?)", RegexOption.IGNORE_CASE) to "Give detailed explanations",
        Regex("(?:кратко|tersely?|briefly?|concise|коротко)", RegexOption.IGNORE_CASE) to "Keep responses brief",
        Regex("(?:clean\\s*architecture|чистая\\s+архитектур)", RegexOption.IGNORE_CASE) to "Use clean architecture",
        Regex("(?:mvvm|model.view.viewmodel)", RegexOption.IGNORE_CASE) to "Use MVVM pattern",
        Regex("(?:repository\\s*pattern|паттерн\\s+репозитор)", RegexOption.IGNORE_CASE) to "Use repository pattern",
        Regex("(?:dependency\\s*injection|di|внедрение\\s+зависимост)", RegexOption.IGNORE_CASE) to "Use dependency injection",
        Regex("(?:result\\s*type|sealed\\s*class.*result|result\\s*<)", RegexOption.IGNORE_CASE) to "Use Result types for errors",
        Regex("(?:try.catch|exceptions?|исключения)", RegexOption.IGNORE_CASE) to "Use exceptions for errors",
        Regex("(?:data\\s*class|data\\s*class вместо)", RegexOption.IGNORE_CASE) to "Prefer data classes",
        Regex("(?:sealed\\s*(?:class|interface)|sealed\\s*вместо)", RegexOption.IGNORE_CASE) to "Prefer sealed types",
        Regex("(?:extension\\s*function|функции\\s*расширения)", RegexOption.IGNORE_CASE) to "Use extension functions",
        Regex("(?:coroutines?|корутин[а-я]+|suspend\\s*fun)", RegexOption.IGNORE_CASE) to "Use coroutines for async",
        Regex("(?:rxjava|reactive|observable)", RegexOption.IGNORE_CASE) to "Use reactive programming",
        Regex("(?:camelCase|camel\\s*case|верблюжий)", RegexOption.IGNORE_CASE) to "Use camelCase naming",
        Regex("(?:snake_case|snake\\s*case|змеиный)", RegexOption.IGNORE_CASE) to "Use snake_case naming",
        Regex("(?:без|никаких?|no|skip)\\s+(?:лог[а-я]+|logging?|print|println)", RegexOption.IGNORE_CASE) to "No logging statements",
        Regex("(?:добавляй|используй|add|use)\\s+(?:лог[а-я]+|logging?)", RegexOption.IGNORE_CASE) to "Add logging",
    )

    fun detectPreferences(text: String): List<String> = PREFERENCE_PATTERNS.mapNotNull { (pattern, label) ->
        if (pattern.containsMatchIn(text)) label else null
    }.distinct()

    fun extractPreferencesFromTranscript(messages: List<com.github.saeldrit.geai.llm.ChatMessage>): List<String> {
        val userText = messages.filter { it.role == com.github.saeldrit.geai.llm.Role.USER }
            .joinToString("\n") { it.text }
        return detectPreferences(userText)
    }

    fun parseAndRender(llmResponse: String): String {
        val summary = tryParse(llmResponse) ?: return llmResponse
        val quality = validateQuality(summary)
        thisLogger().debug("SemanticCompressor: quality=$quality")
        if (!quality.isAcceptable) {
            thisLogger().debug("SemanticCompressor: summary quality too low, using raw text")
            return llmResponse
        }
        val rendered = summary.renderForInjection()
        return if (rendered.length <= llmResponse.length + 200) rendered else llmResponse
    }

    data class QualityReport(
        val hasTask: Boolean,
        val findingCount: Int,
        val findingsWithLocation: Int,
        val decisionCount: Int,
        val changeCount: Int,
    ) {
        val isAcceptable: Boolean get() = hasTask && findingCount > 0 && findingsWithLocation > 0
        val score: Int get() {
            var s = 0
            if (hasTask) s += 20
            if (findingCount > 0) s += 20
            if (findingsWithLocation > 0) s += 20
            if (findingsWithLocation >= findingCount / 2) s += 15
            if (decisionCount > 0) s += 10
            if (changeCount > 0) s += 15
            return s
        }
    }

    @Volatile
    var lastQualityReport: QualityReport? = null
        private set

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


    private fun extractJson(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) return trimmed
        val fencePattern = Regex("```(?:json)?\\s*\\n?(\\{.*?})\\s*\\n?```", RegexOption.DOT_MATCHES_ALL)
        fencePattern.find(trimmed)?.let { return it.groupValues[1].trim() }
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
