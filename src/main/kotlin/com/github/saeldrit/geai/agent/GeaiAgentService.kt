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

/**
 * Project-scoped entry point used by the UI. Owns the active [AgentSession], runs turns on a
 * cancellable background task, and persists progress after every tool result and at turn end so a
 * disconnect/restart can resume. Only one turn runs at a time.
 */
@Service(Service.Level.PROJECT)
class GeaiAgentService(private val project: Project) {

    companion object {
        fun getInstance(project: Project): GeaiAgentService = project.service()

        /**
         * Manual /compact folds the transcript toward this size. The effective budget is
         * `target * CHARS_PER_TOKEN * SAFETY` chars — NOT exactly this many tokens, and with no output
         * reserve subtracted (/compact generates no reply). So ~24_000 * 4 * 0.6 ≈ 57.6k chars ≈ 14k
         * tokens — aggressive on purpose (the SAFETY factor stays; we only dropped the parasitic reserve).
         */
        private const val COMPACT_TARGET_TOKENS = 24_000
    }

    @Volatile
    private var current: AgentSession? = null

    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var currentIndicator: ProgressIndicator? = null

    /** Debounce session saves to ~once per 300ms. ToolFinished can fire from several parallel tool-pool
     *  threads at once, so the window check is an atomic compare-and-set — only one thread wins it. */
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
        // Non-image attachments (text files) are prepended as text blocks
        val textFileBlocks = attachments.filter { !it.isImage }.map { att ->
            ContentBlock.Text("[Attached file: ${att.name}]\n${String(java.util.Base64.getDecoder().decode(att.base64Data))}")
        }

        val savingListener = AgentListener { event ->
            listener.onEvent(event)
            when (event) {
                is AgentEvent.ToolFinished -> if (shouldSave()) store.save(session)
                is AgentEvent.Done, is AgentEvent.Error, is AgentEvent.Cancelled -> store.save(session)
                else -> Unit
            }
        }

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

    /**
     * Manual "/compact": fold the current session's transcript into a dense recap so the next turn
     * re-sends far fewer tokens, without losing the thread. One cheap summariser call. The VISIBLE
     * chat is untouched (it stays for the human); only the model's context shrinks. No-op while a turn
     * runs — guarded by [running] so it can't race a turn mutating the same transcript.
     */
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
                        // outputReserve = 0: /compact does not generate a reply, so no reserve to subtract.
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
