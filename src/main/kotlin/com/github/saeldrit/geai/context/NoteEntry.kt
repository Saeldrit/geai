package com.github.saeldrit.geai.context

enum class NotePriority { CRITICAL, NORMAL, LOW }

data class NoteEntry(
    val text: String,
    val priority: NotePriority = NotePriority.NORMAL,
    val anchor: String? = null,
    val turn: Int = 0,
)
