package com.github.saeldrit.geai.agent

import com.github.saeldrit.geai.engine.ClaudeCodeEngine
import com.github.saeldrit.geai.session.GeaiSessionStore
import com.github.saeldrit.geai.settings.GeaiSettings
import com.github.saeldrit.geai.tools.GeaiToolset
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project

/**
 * Project-scoped entry point used by the UI. Owns the active [AgentSession], runs turns on a
 * cancellable background task, and persists progress after every tool result and at turn end so a
 * disconnect/restart can resume. Only one turn runs at a time.
 */
@Service(Service.Level.PROJECT)
class GeaiAgentService(private val project: Project) {

    companion object {
        fun getInstance(project: Project): GeaiAgentService = project.service()
    }

    @Volatile
    private var current: AgentSession? = null

    @Volatile
    private var running = false

    @Volatile
    private var currentIndicator: ProgressIndicator? = null

    @Synchronized
    fun currentSession(): AgentSession {
        current?.let { return it }
        val loaded = GeaiSessionStore.getInstance(project).loadMostRecent() ?: AgentSession()
        current = loaded
        return loaded
    }

    @Synchronized
    fun newSession(): AgentSession {
        val session = AgentSession()
        current = session
        return session
    }

    @Synchronized
    fun openSession(session: AgentSession) {
        current = session
    }

    fun isRunning(): Boolean = running

    fun stop() {
        currentIndicator?.cancel()
    }

    fun submit(userText: String, listener: AgentListener) {
        val text = userText.trim()
        if (text.isEmpty()) return
        if (running) {
            listener.onEvent(AgentEvent.Error("Geai is already working — press Stop first."))
            return
        }
        val session = currentSession()
        if (session.isEmpty && session.title == "New session") {
            session.title = text.take(60)
        }
        running = true

        val store = GeaiSessionStore.getInstance(project)
        val savingListener = AgentListener { event ->
            listener.onEvent(event)
            when (event) {
                is AgentEvent.ToolFinished, is AgentEvent.Done, is AgentEvent.Error, is AgentEvent.Cancelled ->
                    store.save(session)

                else -> Unit
            }
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Geai", true) {
            override fun run(indicator: ProgressIndicator) {
                currentIndicator = indicator
                try {
                    if (GeaiSettings.getInstance().state.useClaudeCodeEngine) {
                        ClaudeCodeEngine(project).run(session, text, savingListener, indicator)
                    } else {
                        AgentLoop(project, GeaiToolset.registry()).run(session, text, savingListener, indicator)
                    }
                } finally {
                    store.save(session)
                    running = false
                    currentIndicator = null
                }
            }
        })
    }
}
