package com.github.saeldrit.geai.context

data class SemanticSummary(
    val taskDescription: String,
    val findings: List<Finding>,
    val decisions: List<String>,
    val codeChanges: List<String>,
    val openQuestions: List<String>,
    val nextSteps: List<String>,
    val userPreferences: List<String> = emptyList(),
) {
    fun renderForInjection(): String = buildString {
        appendLine("<task>$taskDescription</task>")
        if (findings.isNotEmpty()) {
            appendLine("<findings>")
            findings.forEach { appendLine("- ${it.location}: ${it.summary}") }
            appendLine("</findings>")
        }
        if (decisions.isNotEmpty()) {
            appendLine("<decisions>")
            decisions.forEach { appendLine("- $it") }
            appendLine("</decisions>")
        }
        if (codeChanges.isNotEmpty()) {
            appendLine("<code_changes>")
            codeChanges.forEach { appendLine("- $it") }
            appendLine("</code_changes>")
        }
        if (openQuestions.isNotEmpty()) {
            appendLine("<open_questions>")
            openQuestions.forEach { appendLine("- $it") }
            appendLine("</open_questions>")
        }
        if (nextSteps.isNotEmpty()) {
            appendLine("<next_steps>")
            nextSteps.forEach { appendLine("- $it") }
            appendLine("</next_steps>")
        }
    }.trimEnd()
}

data class Finding(
    val location: String,
    val summary: String,
)
