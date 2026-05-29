package com.github.saeldrit.geai.tools.debug

import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult

/** Reads local variables and their values from the currently paused stack frame. */
object DebugVariablesTool : AgentTool {
    override val name = "debug_variables"
    override val description =
        "Read the local variables and their values in the current paused stack frame. Use after " +
            "await_pause to inspect the actual runtime state that reached the breakpoint."
    override val parametersJsonSchema = """
        {"type":"object","properties":{
          "timeout_seconds":{"type":"integer","description":"Max seconds to wait for the debugger (default 8, max 60)"}
        }}
    """.trimIndent()

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val timeoutMs = args.int("timeout_seconds", 8).coerceIn(1, 60) * 1000L
        return FrameInspection.locals(context.project, timeoutMs)
    }
}
