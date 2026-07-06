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
            "debug", "🐞", "Debug an issue", null,
            "/debug Debug this: <describe the problem>. Set breakpoints along the data path, start the " +
                "debugger, trace where the data is lost or corrupted, and propose a fix with file:line.",
        ),
        GeaiSkill(
            "explain", "🔍", "Explain code", null,
            "/explain Explain how <class/function> works, and show where it is used (file:line).",
        ),
        GeaiSkill(
            "feature", "✏️", "Implement a feature", null,
            "/implement Implement: <what you need>. Study the affected code first, then make the smallest style-matching change.",
        ),
        GeaiSkill(
            "review", "🔀", "Review changes", null,
            "/review Review the current changes: correctness, bugs, security, simplifications. Return findings with file:line.",
        ),
    )
}
