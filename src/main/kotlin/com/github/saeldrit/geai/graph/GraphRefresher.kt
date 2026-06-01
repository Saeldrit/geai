package com.github.saeldrit.geai.graph

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the GRACE graph fresh after the agent edits files WITHOUT an incremental patch (the store has
 * no per-file patch API). Edits call [markDirty]; a debounce coalesces a burst of edits into a single
 * full background reindex on idle — so graph_query / context_bundle stop lying after the first edit.
 * A full reindex is cheap relative to a turn and far simpler/safer than incremental node/edge surgery.
 */
@Service(Service.Level.PROJECT)
class GraphRefresher(private val project: Project) : Disposable {

    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val running = AtomicBoolean(false)
    private val rerunRequested = AtomicBoolean(false)

    /** Mark the graph stale and (re)start the idle debounce. Returns immediately — never blocks the edit. */
    fun markDirty() {
        if (project.isDisposed) return
        alarm.cancelAllRequests()
        alarm.addRequest({ refresh() }, DEBOUNCE_MS)
    }

    private fun refresh() {
        if (project.isDisposed) return
        // PSI is unreliable while the IDE indexes — defer one more debounce window, don't index in dumb mode.
        if (DumbService.isDumb(project)) {
            markDirty()
            return
        }
        // Coalesce: if a reindex is already in flight, just record that another is wanted and bail.
        if (!running.compareAndSet(false, true)) {
            rerunRequested.set(true)
            return
        }
        try {
            GeaiGraphStore.getInstance(project).replaceAll(GraphIndexer.reindex(project))
        } catch (_: ProcessCanceledException) {
            // A read was interrupted (e.g. by a write action) — not fatal; retry on the next idle window.
            rerunRequested.set(true)
        } catch (t: Throwable) {
            thisLogger().warn("Geai graph refresh failed", t)
        } finally {
            running.set(false)
            // An edit landed mid-reindex — run exactly once more to capture it.
            if (rerunRequested.compareAndSet(true, false)) markDirty()
        }
    }

    override fun dispose() {
        alarm.cancelAllRequests()
    }

    companion object {
        private const val DEBOUNCE_MS = 2500

        fun getInstance(project: Project): GraphRefresher = project.service()
    }
}
