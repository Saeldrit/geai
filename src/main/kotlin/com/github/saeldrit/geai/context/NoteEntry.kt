package com.github.saeldrit.geai.context

enum class NotePriority { CRITICAL, NORMAL, LOW }

data class NoteEntry(
    val text: String,
    val priority: NotePriority = NotePriority.NORMAL,
    val anchor: String? = null,      // e.g. "src/main/Foo.kt:42"
    val turn: Int = 0,               // iteration when note was created
)
