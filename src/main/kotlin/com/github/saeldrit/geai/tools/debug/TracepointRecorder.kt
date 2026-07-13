package com.github.saeldrit.geai.tools.debug

import com.github.saeldrit.geai.tools.fs.FsPaths
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal object TracepointRecorder {

    private const val MAX_RECORDS = 300
    private const val EVAL_TIMEOUT_MS = 2_500L

    private class State {
        val registry = ConcurrentHashMap<Pair<String, Int>, String>()
        val records = ArrayDeque<String>()
        val counter = AtomicInteger(0)
        val subscribed = AtomicBoolean(false)
        val capturing = AtomicBoolean(false)
    }

    private val states = ConcurrentHashMap<Project, State>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS")

    private fun state(project: Project): State = states.computeIfAbsent(project) { State() }

    fun register(project: Project, fileUrl: String, line0: Int, expression: String?) {
        val st = state(project)
        st.registry[fileUrl to line0] = expression?.trim().orEmpty()
        ensureSubscribed(project, st)
    }

    /** Unregister tracepoints in [fileUrl]; [line0] null = all lines in the file. Returns how many. */
    fun unregister(project: Project, fileUrl: String, line0: Int?): Int {
        val st = states[project] ?: return 0
        val keys = st.registry.keys.filter { it.first == fileUrl && (line0 == null || it.second == line0) }
        keys.forEach { st.registry.remove(it) }
        return keys.size
    }

    fun isRegistered(project: Project, fileUrl: String, line0: Int): Boolean =
        states[project]?.registry?.containsKey(fileUrl to line0) == true

    /** Is the CURRENT pause of [session] at a tracepoint (i.e. transient — about to auto-resume)? */
    fun isTracepointPause(project: Project, session: XDebugSession): Boolean {
        val pos = session.currentPosition ?: return false
        return isRegistered(project, pos.file.url, pos.line)
    }

    fun tracepointCount(project: Project): Int = states[project]?.registry?.size ?: 0

    /** Drain or peek the trace log. */
    fun log(project: Project, clear: Boolean): List<String> {
        val st = states[project] ?: return emptyList()
        synchronized(st.records) {
            val snapshot = st.records.toList()
            if (clear) st.records.clear()
            return snapshot
        }
    }

    private fun record(st: State, line: String) {
        synchronized(st.records) {
            st.records.addLast(line)
            while (st.records.size > MAX_RECORDS) st.records.removeFirst()
        }
    }

    private fun ensureSubscribed(project: Project, st: State) {
        if (!st.subscribed.compareAndSet(false, true)) return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val manager = XDebuggerManager.getInstance(project)
            manager.debugSessions.forEach { attach(project, it) }
            project.messageBus.connect().subscribe(
                XDebuggerManager.TOPIC,
                object : XDebuggerManagerListener {
                    override fun processStarted(debugProcess: XDebugProcess) {
                        attach(project, debugProcess.session)
                    }
                },
            )
        }
    }

    private fun attach(project: Project, session: XDebugSession) {
        session.addSessionListener(object : XDebugSessionListener {
            override fun sessionPaused() {
                AppExecutorUtil.getAppExecutorService().execute { onPaused(project, session) }
            }
        })
    }

    private fun onPaused(project: Project, session: XDebugSession) {
        val st = states[project] ?: return
        val posInfo = readPosition(session) ?: return
        val (url, line0, human) = posInfo
        val expr = st.registry[url to line0] ?: return

        if (!st.capturing.compareAndSet(false, true)) return
        try {
            val n = st.counter.incrementAndGet()
            val stamp = timeFormat.format(Date())
            val entry = if (expr.isBlank()) {
                "#$n $stamp $human — hit"
            } else {
                val value = runCatching { FrameInspection.evaluateSingle(project, expr, EVAL_TIMEOUT_MS) }
                    .getOrElse { "<eval failed: ${it.message}>" }
                "#$n $stamp $human — $expr = $value"
            }
            record(st, entry)
        } catch (t: Throwable) {
            thisLogger().warn("Tracepoint capture failed", t)
        } finally {
            st.capturing.set(false)
            ApplicationManager.getApplication().invokeLater {
                if (!session.isStopped && session.isPaused) {
                    if (isTracepointPause(project, session)) session.resume()
                }
            }
        }
    }

    private fun readPosition(session: XDebugSession): Triple<String, Int, String>? {
        val ref = java.util.concurrent.atomic.AtomicReference<Triple<String, Int, String>?>(null)
        ApplicationManager.getApplication().invokeAndWait {
            val pos = session.currentPosition ?: return@invokeAndWait
            val human = "${FsPaths.relativize(session.project, pos.file)}:${pos.line + 1}"
            ref.set(Triple(pos.file.url, pos.line, human))
        }
        return ref.get()
    }
}
