package com.github.saeldrit.geai.context

/**
 * Structured representation of a compressed transcript segment. Produced by [SemanticCompressor]
 * from an LLM call that returns JSON instead of prose, so each category of retained information
 * (findings, decisions, changes…) is a discrete field that can be rendered compactly and survives
 * re-compression without losing structure.
 */
data class SemanticSummary(
    /** One-line description of the task the agent was working on. */
    val taskDescription: String,
    /** Concrete findings with file:line locations. */
    val findings: List<Finding>,
    /** Architectural or implementation decisions already made. */
    val decisions: List<String>,
    /** Files modified and what was changed. */
    val codeChanges: List<String>,
    /** Unresolved items or things that still need verification. */
    val openQuestions: List<String>,
    /** Planned next actions. */
    val nextSteps: List<String>,
    /** Durable user preferences / skills that should persist across compressions. */
    val userPreferences: List<String> = emptyList(),
) {
    /**
     * Render the summary into a compact, tagged text block for injection into the transcript.
     * Uses XML-like tags so the model can parse structure; keeps it terse so the output is
     * always shorter than the raw transcript it replaced.
     */
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

/** A single finding anchored to a source location. */
data class Finding(
    /** File path and line reference (e.g. "src/Foo.kt:42" or "Foo.kt:42-50"). */
    val location: String,
    /** What was observed or concluded at that location. */
    val summary: String,
)
