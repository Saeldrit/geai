package com.github.saeldrit.geai.knowledge

enum class Axis {
    NAV,

    STYLE,

    TECH,

    LESSON,
}

data class KnowledgeEntry(
    val id: String,
    val axis: Axis,
    val tags: List<String>,
    val title: String,
    val location: String?,
    val version: Int,
    val body: String,
)
