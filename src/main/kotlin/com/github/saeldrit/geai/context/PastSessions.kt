package com.github.saeldrit.geai.context

import com.github.saeldrit.geai.llm.Role
import com.github.saeldrit.geai.session.GeaiSessionStore
import com.intellij.openapi.project.Project

object PastSessions {

    private const val MAX_MATCHES = 3
    private const val ANSWER_HEAD = 300

    fun hint(project: Project, task: String, currentSessionId: String): String = runCatching {
        val words = Regex("[\\p{L}\\d_]{4,}").findAll(task.lowercase()).map { it.value }.toSet()
        if (words.isEmpty()) return ""
        val store = GeaiSessionStore.getInstance(project)
        val scored = store.listMeta().asSequence()
            .filter { it.id != currentSessionId && it.messageCount >= 2 }
            .map { meta -> meta to words.count { w -> meta.title.lowercase().contains(w) } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(MAX_MATCHES)
            .toList()
        if (scored.isEmpty()) return ""

        buildString {
            appendLine("<past_sessions>")
            appendLine("Possibly related PREVIOUS sessions in this project — check before re-investigating from scratch:")
            scored.forEach { (meta, _) ->
                val lastAnswer = store.load(meta.id)?.messages
                    ?.lastOrNull { it.role == Role.ASSISTANT && it.text.isNotBlank() }
                    ?.text?.replace(Regex("\\s+"), " ")?.take(ANSWER_HEAD)
                appendLine("• «${meta.title}» → ${lastAnswer ?: "(no recorded answer)"}")
            }
            append("</past_sessions>")
        }
    }.getOrDefault("")
}
