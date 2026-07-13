package com.github.saeldrit.geai.agent

import com.github.saeldrit.geai.context.ContextCompressor
import com.github.saeldrit.geai.context.TranscriptSummary
import com.github.saeldrit.geai.llm.ContentBlock
import com.github.saeldrit.geai.llm.LlmClientFactory
import com.github.saeldrit.geai.llm.LlmException
import com.github.saeldrit.geai.llm.TokenUsage
import com.github.saeldrit.geai.session.GeaiSessionStore
import com.github.saeldrit.geai.settings.GeaiSettings
import com.github.saeldrit.geai.settings.loopModel
import com.github.saeldrit.geai.tools.GeaiToolset
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class GeaiAgentService(private val project: Project) {

    companion object {
        fun getInstance(project: Project): GeaiAgentService = project.service()

        private const val COMPACT_TARGET_TOKENS = 48_000
    }

    @Volatile
    private var current: AgentSession? = null

    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var currentIndicator: ProgressIndicator? = null

    @Volatile
    private var lastTurnLabel: com.intellij.history.Label? = null

    @Volatile
    private var lastTurnTitle: String = ""

    private val lastSaveMs = java.util.concurrent.atomic.AtomicLong(0L)

    private fun shouldSave(): Boolean {
        val now = System.currentTimeMillis()
        val prev = lastSaveMs.get()
        return now - prev >= 300L && lastSaveMs.compareAndSet(prev, now)
    }

    @Synchronized
    fun currentSession(): AgentSession {
        current?.let { return it }
        val loaded = GeaiSessionStore.getInstance(project).loadMostRecent() ?: AgentSession()
        current = loaded
        return loaded
    }

    @Synchronized
    fun newSession(): AgentSession {
        current?.let { if (!it.isEmpty) GeaiSessionStore.getInstance(project).save(it) }
        val session = AgentSession()
        current = session
        return session
    }

    @Synchronized
    fun openSession(session: AgentSession) {
        current = session
    }

    fun isRunning(): Boolean = running.get()

    fun stop() {
        currentIndicator?.cancel()
    }

    fun submit(userText: String, listener: AgentListener, attachments: List<Attachment> = emptyList()) {
        val text = userText.trim()
        if (text.isEmpty() && attachments.isEmpty()) return
        if (!running.compareAndSet(false, true)) {
            listener.onEvent(AgentEvent.Error("Geai is already working — press Stop first."))
            return
        }
        val session = currentSession()
        if (session.isEmpty && session.title == "New session") {
            session.title = text.take(60)
        }

        val store = GeaiSessionStore.getInstance(project)
        val textFileBlocks = attachments.filter { !it.isImage }.map { att ->
            ContentBlock.Text("[Attached file: ${att.name}]\n${String(java.util.Base64.getDecoder().decode(att.base64Data))}")
        }

        val savingListener = AgentListener { event ->
            listener.onEvent(event)
            when (event) {
                is AgentEvent.ToolFinished -> if (shouldSave()) store.saveAsync(session)
                is AgentEvent.Done, is AgentEvent.Error, is AgentEvent.Cancelled -> store.save(session)
                else -> Unit
            }
        }

        lastTurnLabel = runCatching {
            com.intellij.history.LocalHistory.getInstance().putSystemLabel(project, "geai: before turn — ${text.take(60)}")
        }.getOrNull()
        lastTurnTitle = text.take(60)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Geai", true) {
            override fun run(indicator: ProgressIndicator) {
                currentIndicator = indicator
                try {
                    AgentLoop(project, GeaiToolset.registry()).run(session, text, savingListener, indicator, attachments)
                } finally {
                    store.save(session)
                    running.set(false)
                    currentIndicator = null
                }
            }
        })
    }

    fun revertLastTurn(listener: AgentListener) {
        if (running.get()) {
            listener.onEvent(AgentEvent.Info("Geai is still working — stop the turn before reverting."))
            return
        }
        val label = lastTurnLabel
        if (label == null) {
            listener.onEvent(AgentEvent.Info("Nothing to revert: no turn has run in this window yet."))
            return
        }
        val base = project.basePath?.let { com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(it) }
        if (base == null) {
            listener.onEvent(AgentEvent.Error("Cannot revert: project base directory is unavailable."))
            return
        }
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            val choice = com.intellij.openapi.ui.Messages.showYesNoDialog(
                project,
                "Revert ALL file changes made since the last turn started" +
                    (lastTurnTitle.takeIf { it.isNotBlank() }?.let { " (“$it”)" } ?: "") +
                    "?\n\nThis restores project files from Local History; the chat itself is kept. " +
                    "Your own edits made after the turn started will also be reverted.",
                "Geai — Revert Last Turn",
                "Revert Files",
                "Cancel",
                com.intellij.openapi.ui.Messages.getWarningIcon(),
            )
            if (choice != com.intellij.openapi.ui.Messages.YES) return@invokeLater
            runCatching { label.revert(project, base) }
                .onSuccess {
                    lastTurnLabel = null
                    listener.onEvent(AgentEvent.Info("↩ Project files reverted to the state before the last turn (Local History)."))
                }
                .onFailure { e ->
                    listener.onEvent(AgentEvent.Error("Revert failed: ${e.message}. Use Local History (right-click project → Local History → Show History) to revert manually — look for the label \"geai: before turn\"."))
                }
        }
    }

    fun compact(listener: AgentListener) {
        if (!running.compareAndSet(false, true)) {
            listener.onEvent(AgentEvent.Info("Geai is busy right now — I'll compress the context after the current turn finishes."))
            return
        }
        val session = currentSession()
        val store = GeaiSessionStore.getInstance(project)
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Geai: compressing context", true) {
            override fun run(indicator: ProgressIndicator) {
                currentIndicator = indicator
                try {
                    val before = ContextCompressor.estimatedTokens(session.messages)
                    val client = try {
                        LlmClientFactory.create()
                    } catch (e: LlmException) {
                        listener.onEvent(AgentEvent.Info("Can't compress: ${e.message ?: "LLM not configured"}."))
                        return
                    }
                    val settings = GeaiSettings.getInstance().state
                    var spent = TokenUsage.ZERO
                    val summarizer = TranscriptSummary.summarizer(client, settings.loopModel(), indicator) { used -> spent += used }
                    val compacted = runCatching {
                        ContextCompressor.compress(session.messages, COMPACT_TARGET_TOKENS, 0, 0, summarizer)
                    }.getOrNull()
                    if (compacted.isNullOrEmpty()) {
                        listener.onEvent(AgentEvent.Info("Compression failed — context unchanged."))
                        return
                    }
                    session.totalUsage += spent
                    if (compacted !== session.messages) {
                        session.messages.clear()
                        session.messages.addAll(compacted)
                    }
                    store.save(session)
                    val after = ContextCompressor.estimatedTokens(session.messages)
                    listener.onEvent(
                        AgentEvent.Info(
                            if (after < before) {
                                "🗜 Context compressed: ~$before → ~$after tokens (compression spent ↑${spent.inputTokens} ↓${spent.outputTokens})."
                            } else {
                                "Context is already compact (~$after tokens) — nothing to compress."
                            },
                        ),
                    )
                    listener.onEvent(AgentEvent.Done(session.totalUsage))
                } finally {
                    running.set(false)
                    currentIndicator = null
                }
            }
        })
    }
}
