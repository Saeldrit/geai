package com.github.saeldrit.geai.toolWindow

/** A one-click preset shown on the welcome screen. Clicking fills the composer with [prompt]. */
data class GeaiSkill(
    val id: String,
    val icon: String,
    val title: String,
    val badge: String?,
    val prompt: String,
)

/** Curated presets that map to geai's real capabilities. */
object GeaiSkills {

    fun all(): List<GeaiSkill> = listOf(
        GeaiSkill(
            "debug", "🐞", "Autonomous debugging with the agent", "NEW",
            "/debug Debug this: <describe the problem>. Set breakpoints along the data path, start the " +
                "debugger, trace where the data is lost or corrupted, and propose a fix with file:line.",
        ),
        GeaiSkill(
            "explain", "🔍", "Explain code and find usages", null,
            "/explain Explain how <class/function> works, and show where it is used (file:line).",
        ),
        GeaiSkill(
            "feature", "✏️", "Implement features and fixes", null,
            "/implement Implement: <what you need>. Study the affected code first, then make the smallest style-matching change.",
        ),
        GeaiSkill(
            "refactor", "♻️", "Systematic refactoring", null,
            "/refactor Refactor <area>: remove duplication, improve names, preserve behavior. Show a plan before editing.",
        ),
        GeaiSkill(
            "tests", "🧪", "Generate unit tests", null,
            "/test Generate unit tests for <class/function>, covering the main and edge cases, in the style of the existing tests.",
        ),
        GeaiSkill(
            "review", "🔀", "Code review", null,
            "/review Review the current changes: correctness, bugs, security, simplifications. Return findings with file:line.",
        ),
        GeaiSkill(
            "agents", "⚙️", "Create AGENTS.md for the project", null,
            "Study the project and create AGENTS.md: structure, build/test commands, conventions, key modules.",
        ),
        GeaiSkill(
            "security", "🛡️", "Security analysis", "BETA",
            "/security Audit the project for vulnerabilities (injection, secrets, authorization, unsafe deserialization) and propose fixes.",
        ),
    )
}
