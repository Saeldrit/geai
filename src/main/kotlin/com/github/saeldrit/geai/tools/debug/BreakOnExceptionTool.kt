package com.github.saeldrit.geai.tools.debug

import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.impl.breakpoints.XExpressionImpl
import java.util.concurrent.atomic.AtomicReference

object BreakOnExceptionTool : AgentTool {
    override val name = "break_on_exception"
    override val description =
        "Enable or disable breaking on thrown exceptions (the debugger's exception breakpoints, e.g. " +
            "Java's 'Any exception'). Enable it when the bug manifests as an exception/crash: the " +
            "session pauses AT THE THROW with the full stack and live variables (await_pause returns " +
            "the location). Optional 'condition' filters hits, e.g. " +
            "\"this instanceof java.lang.IllegalStateException\". Disable it when done — a busy app " +
            "may throw (and pause on) unrelated exceptions."
    override val parametersJsonSchema = """
        {"type":"object","properties":{
          "enabled":{"type":"boolean","description":"true = pause on thrown exceptions, false = stop pausing"},
          "condition":{"type":"string","description":"Optional filter expression evaluated at the throw; 'this' is the exception object."}
        },"required":["enabled"]}
    """.trimIndent()

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val enabled = args.boolean("enabled", true)
        val condition = args.stringOrNull("condition")?.trim()?.takeIf { it.isNotEmpty() }

        val ref = AtomicReference<ToolResult>()
        ApplicationManager.getApplication().invokeAndWait {
            ref.set(
                WriteAction.compute<ToolResult, RuntimeException> {
                    val mgr = DebuggerSupport.breakpointManager(context.project)
                    val exceptionBps = mgr.allBreakpoints.filter { bp ->
                        bp !is XLineBreakpoint<*> && (
                            bp.type.id.contains("exception", ignoreCase = true) ||
                                bp.type.javaClass.simpleName.contains("Exception")
                            )
                    }
                    if (exceptionBps.isEmpty()) {
                        return@compute ToolResult.error(
                            "No exception breakpoint type is available (non-JVM IDE or unsupported debugger). " +
                                "Fall back to a conditional line breakpoint near the failure.",
                        )
                    }
                    exceptionBps.forEach { bp ->
                        bp.isEnabled = enabled
                        if (condition != null) bp.conditionExpression = XExpressionImpl.fromText(condition)
                        else if (!enabled) bp.conditionExpression = null
                    }
                    val names = exceptionBps.joinToString(", ") { it.type.title }
                    val cond = condition?.let { " with condition [$it]" } ?: ""
                    ToolResult.ok(
                        if (enabled) {
                            "Enabled ${exceptionBps.size} exception breakpoint(s): $names$cond. " +
                                "Trigger the failing scenario, then await_pause — the session will stop at the throw."
                        } else {
                            "Disabled ${exceptionBps.size} exception breakpoint(s): $names."
                        },
                    )
                },
            )
        }
        return ref.get() ?: ToolResult.error("Exception breakpoint operation produced no result.")
    }
}
