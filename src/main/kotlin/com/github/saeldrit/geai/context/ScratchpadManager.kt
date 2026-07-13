package com.github.saeldrit.geai.context

object ScratchpadManager {

    private const val NORMAL_KEEP_ON_SWITCH = 15

    fun cleanForNewTask(scratchpad: MutableList<NoteEntry>): CleanupStats {
        val critical = scratchpad.filter { it.priority == NotePriority.CRITICAL }
            .distinctBy { it.text }
        val normal = scratchpad.filter { it.priority == NotePriority.NORMAL }
        val normalKept = normal.takeLast(NORMAL_KEEP_ON_SWITCH)
        val lowCount = scratchpad.count { it.priority == NotePriority.LOW }

        scratchpad.clear()
        scratchpad.addAll(critical)
        scratchpad.addAll(normalKept)

        return CleanupStats(
            criticalKept = critical.size,
            normalKept = normalKept.size,
            normalDropped = normal.size - normalKept.size,
            lowDropped = lowCount,
        )
    }

    fun retainWithinBudget(notes: List<NoteEntry>, maxEntries: Int): List<NoteEntry> {
        if (notes.size <= maxEntries) return notes
        val critical = notes.filter { it.priority == NotePriority.CRITICAL }
        if (critical.size >= maxEntries) return critical.takeLast(maxEntries)
        val remaining = maxEntries - critical.size
        val nonCritical = notes.filter { it.priority != NotePriority.CRITICAL }
        val normal = nonCritical.filter { it.priority == NotePriority.NORMAL }.takeLast(remaining)
        val lowSlots = remaining - normal.size
        val low = if (lowSlots > 0) nonCritical.filter { it.priority == NotePriority.LOW }.takeLast(lowSlots) else emptyList()
        return critical + normal + low
    }
}

data class CleanupStats(
    val criticalKept: Int,
    val normalKept: Int,
    val normalDropped: Int,
    val lowDropped: Int,
) {
    val totalCleaned: Int get() = normalDropped + lowDropped
}
