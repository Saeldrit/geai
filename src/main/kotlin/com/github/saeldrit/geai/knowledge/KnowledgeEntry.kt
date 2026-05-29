package com.github.saeldrit.geai.knowledge

/** The four knowledge axes geai navigates by. */
enum class Axis {
    /** Symbol -> location: where a class/method/handler lives (file:line), so it is found without re-searching. */
    NAV,

    /** Code style and conventions of this project. */
    STYLE,

    /** Technical facts: stack, build, frameworks, invariants. */
    TECH,

    /** Hard-won lessons / anti-patterns: what must not be done. */
    LESSON,
}

/** One immutable knowledge record. [version] backs optimistic CAS updates across concurrent agents. */
data class KnowledgeEntry(
    val id: String,
    val axis: Axis,
    val tags: List<String>,
    val title: String,
    val location: String?,
    val version: Int,
    val body: String,
)
