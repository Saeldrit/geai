package com.github.saeldrit.geai.tools.debug

import com.github.saeldrit.geai.tools.ToolResult
import com.github.saeldrit.geai.tools.fs.FsPaths
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.XBreakpointManager
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import java.util.concurrent.atomic.AtomicReference

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
