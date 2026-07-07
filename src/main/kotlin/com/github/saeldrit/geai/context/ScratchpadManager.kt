package com.github.saeldrit.geai.context

/**
 * Operations on the structured scratchpad — kept in one place for consistency.
 */
object ScratchpadManager {

    /**
     * Clean the scratchpad when a new task is detected:
     * 1. Drop all LOW notes
     * 2. Summarize NORMAL notes into a single recap entry
     * 3. Keep all CRITICAL notes unchanged
     *
     * [summarizer] is optional — if available, use LLM to compress NORMAL notes;
     * otherwise concatenate with ";" and truncate to 500 chars.
     */
    fun cleanForNewTask(
        scratchpad: MutableList<NoteEntry>,
        summarizer: ContextCompressor.Summarizer? = null,
    ): CleanupStats {
        val critical = scratchpad.filter { it.priority == NotePriority.CRITICAL }
        val normal = scratchpad.filter { it.priority == NotePriority.NORMAL }
        val lowCount = scratchpad.count { it.priority == NotePriority.LOW }

        scratchpad.clear()
        scratchpad.addAll(critical)

        if (normal.isNotEmpty()) {
            val recapText = if (summarizer != null) {
                val raw = normal.joinToString("\n") { "- ${it.text}" }
                val summary = runCatching { summarizer.summarize(raw) }.getOrDefault("")
                if (summary.isNotBlank()) summary else raw.take(500)
            } else {
                normal.joinToString("; ") { it.text }.take(500)
            }
            scratchpad.add(NoteEntry(
                text = "[Recap from previous task] $recapText",
                priority = NotePriority.NORMAL,
            ))
        }

        return CleanupStats(
            criticalKept = critical.size,
            normalSummarized = normal.size,
            lowDropped = lowCount,
        )
    }

    /**
     * Priority-aware eviction: keep as many notes as [maxEntries] allows,
     * preferring CRITICAL > NORMAL > LOW.
     */
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
    val normalSummarized: Int,
    val lowDropped: Int,
) {
    val totalCleaned: Int get() = normalSummarized + lowDropped
}
