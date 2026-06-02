package com.github.saeldrit.geai.tools.debug

import com.github.saeldrit.geai.tools.ToolResult
import com.github.saeldrit.geai.tools.fs.FsPaths
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.XBreakpointManager
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** How to advance a PAUSED debug session — see [DebuggerSupport.step]. */
internal enum class StepKind { OVER, INTO, OUT, RESUME }

/**
 * EDT/write-action-safe wrapper over the generic XDebugger API. Stays language-agnostic by
 * discovering a [XLineBreakpointType] that `canPutAt` the requested line, so it works for Java,
 * Kotlin, and any language whose debugger registers line breakpoints.
 */
internal object DebuggerSupport {

    fun manager(project: Project): XDebuggerManager = XDebuggerManager.getInstance(project)

    fun breakpointManager(project: Project): XBreakpointManager = manager(project).breakpointManager

    fun addBreakpoint(project: Project, file: VirtualFile, line1: Int): ToolResult {
        val line0 = line1 - 1
        val location = "${FsPaths.relativize(project, file)}:$line1"
        return onEdtWrite {
            val type = XDebuggerUtil.getInstance().lineBreakpointTypes.firstOrNull { it.canPutAt(file, line0, project) }
                ?: return@onEdtWrite ToolResult.error("No breakpoint can be placed at $location (not an executable line?).")
            val mgr = breakpointManager(project)
            existingAt(mgr, file.url, line0)?.let { mgr.removeBreakpoint(it) }
            addTyped(mgr, type, file, line0)
            ToolResult.ok("Breakpoint set at $location")
        }
    }

    fun removeBreakpoint(project: Project, file: VirtualFile, line1: Int?): ToolResult = onEdtWrite {
        val mgr = breakpointManager(project)
        val inFile = mgr.allBreakpoints.filterIsInstance<XLineBreakpoint<*>>().filter { it.fileUrl == file.url }
        val target = if (line1 == null) inFile else inFile.filter { it.line == line1 - 1 }
        if (target.isEmpty()) {
            ToolResult.ok("No matching breakpoints to remove in ${FsPaths.relativize(project, file)}.")
        } else {
            target.forEach { mgr.removeBreakpoint(it) }
            ToolResult.ok("Removed ${target.size} breakpoint(s) in ${FsPaths.relativize(project, file)}.")
        }
    }

    fun listBreakpoints(project: Project): ToolResult = onEdt {
        val lineBreakpoints = breakpointManager(project).allBreakpoints.filterIsInstance<XLineBreakpoint<*>>()
        if (lineBreakpoints.isEmpty()) {
            ToolResult.ok("No line breakpoints are set.")
        } else {
            val rows = lineBreakpoints.map { bp ->
                val state = if (bp.isEnabled) "enabled" else "disabled"
                val condition = bp.conditionExpression?.expression?.takeIf { it.isNotBlank() }?.let { " when [$it]" } ?: ""
                "${bp.fileUrl.substringAfterLast('/')}:${bp.line + 1} ($state)$condition"
            }
            ToolResult.ok("${lineBreakpoints.size} breakpoint(s):\n${rows.joinToString("\n")}")
        }
    }

    /** Cheap check: is a debug session live (running or paused)? Used to auto-advertise the debug group. */
    fun hasActiveSession(project: Project): Boolean {
        val ref = AtomicReference(false)
        ApplicationManager.getApplication().invokeAndWait { ref.set(manager(project).debugSessions.isNotEmpty()) }
        return ref.get()
    }

    /**
     * One-shot snapshot for await_pause: (paused location or null, whether ANY debug session is live).
     * Lets await_pause return the instant it is paused, AND bail out when the session has ENDED instead
     * of polling out the whole timeout for a pause that can never come.
     */
    fun pauseSnapshot(project: Project): Pair<String?, Boolean> {
        val ref = AtomicReference<Pair<String?, Boolean>>(null to false)
        ApplicationManager.getApplication().invokeAndWait {
            val mgr = manager(project)
            val active = mgr.debugSessions.isNotEmpty()
            val session = mgr.currentSession
            val paused = if (session != null && session.isPaused) describeSession(project, session) else null
            ref.set(paused to active)
        }
        return ref.get()
    }

    fun describeState(project: Project): ToolResult = onEdt {
        val sessions = manager(project).debugSessions
        if (sessions.isEmpty()) {
            ToolResult.ok("No active debug session.")
        } else {
            ToolResult.ok(sessions.joinToString("\n") { describeSession(project, it) })
        }
    }

    fun describeSession(project: Project, session: XDebugSession): String {
        val status = when {
            session.isStopped -> "stopped"
            session.isPaused -> "paused"
            else -> "running"
        }
        val position = if (session.isPaused) positionString(project, session.currentPosition) else null
        return "Session '${session.sessionName}': $status" + (position?.let { " at $it" } ?: "")
    }

    fun pausedSessionDescription(project: Project): String? {
        val ref = AtomicReference<String?>()
        ApplicationManager.getApplication().invokeAndWait {
            val session = manager(project).currentSession
            ref.set(if (session != null && session.isPaused) describeSession(project, session) else null)
        }
        return ref.get()
    }

    /**
     * Advance a PAUSED debug session ([StepKind]) and block (cooperatively) until it pauses again or
     * the program ends. Event-driven via an [XDebugSessionListener] so we catch the NEW pause, not the
     * stale one we started from. Returns the new paused location, or notes that the session ended / is
     * still running. This is what lets the agent walk the suspect path itself instead of asking the
     * user to step. Honors cancellation by polling [indicator] while awaiting the next pause.
     */
    fun step(project: Project, kind: StepKind, timeoutMs: Long, indicator: ProgressIndicator): ToolResult {
        val session = currentSession(project)
            ?: return ToolResult.error("No active debug session. Set a breakpoint, start_debug, then await_pause.")
        val wasPaused = AtomicReference(false)
        ApplicationManager.getApplication().invokeAndWait { wasPaused.set(session.isPaused) }
        if (!wasPaused.get()) {
            return ToolResult.error(
                "Debugger is not paused — cannot ${kind.name.lowercase()}. Call await_pause first; it returns when a breakpoint is hit.",
            )
        }

        val latch = CountDownLatch(1)
        val stopped = AtomicBoolean(false)
        val listener = object : XDebugSessionListener {
            override fun sessionPaused() = latch.countDown()
            override fun sessionStopped() {
                stopped.set(true)
                latch.countDown()
            }
        }
        ApplicationManager.getApplication().invokeAndWait {
            session.addSessionListener(listener)
            when (kind) {
                StepKind.OVER -> session.stepOver(false)
                StepKind.INTO -> session.stepInto()
                StepKind.OUT -> session.stepOut()
                StepKind.RESUME -> session.resume()
            }
        }
        try {
            val verb = kind.name.lowercase()
            val deadline = System.currentTimeMillis() + timeoutMs
            var signalled = false
            while (System.currentTimeMillis() < deadline) {
                if (indicator.isCanceled) throw ProcessCanceledException()
                if (latch.await(200, TimeUnit.MILLISECONDS)) {
                    signalled = true
                    break
                }
            }
            return when {
                stopped.get() -> ToolResult.ok("$verb → the debug session ended (program finished or was stopped).")
                !signalled -> ToolResult.ok(
                    "Issued $verb; no pause within ${timeoutMs / 1000}s — the program is still running. " +
                        "Call await_pause with a longer timeout, or debug_state to poll.",
                )

                else -> ToolResult.ok("Stepped $verb → ${pausedSessionDescription(project) ?: "paused (position unavailable)"}")
            }
        } finally {
            ApplicationManager.getApplication().invokeAndWait { session.removeSessionListener(listener) }
        }
    }

    private fun currentSession(project: Project): XDebugSession? {
        val ref = AtomicReference<XDebugSession?>()
        ApplicationManager.getApplication().invokeAndWait { ref.set(manager(project).currentSession) }
        return ref.get()
    }

    private fun positionString(project: Project, position: XSourcePosition?): String? {
        position ?: return null
        return "${FsPaths.relativize(project, position.file)}:${position.line + 1}"
    }

    private fun existingAt(mgr: XBreakpointManager, url: String, line0: Int): XLineBreakpoint<*>? =
        mgr.allBreakpoints.filterIsInstance<XLineBreakpoint<*>>().firstOrNull { it.fileUrl == url && it.line == line0 }

    @Suppress("UNCHECKED_CAST")
    private fun addTyped(mgr: XBreakpointManager, type: XLineBreakpointType<*>, file: VirtualFile, line0: Int) {
        val typed = type as XLineBreakpointType<XBreakpointProperties<*>>
        val properties = typed.createBreakpointProperties(file, line0)
        mgr.addLineBreakpoint(typed, file.url, line0, properties)
    }

    private fun onEdt(block: () -> ToolResult): ToolResult {
        val ref = AtomicReference<ToolResult>()
        ApplicationManager.getApplication().invokeAndWait { ref.set(block()) }
        return ref.get() ?: ToolResult.error("Debugger operation produced no result.")
    }

    private fun onEdtWrite(block: () -> ToolResult): ToolResult =
        onEdt { WriteAction.compute<ToolResult, RuntimeException> { block() } }
}
