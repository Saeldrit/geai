package com.github.saeldrit.geai.tools.debug

import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult
import com.github.saeldrit.geai.tools.fs.FsPaths

object SetBreakpointTool : AgentTool {
    override val name = "set_breakpoint"
    override val description =
        "Set a line breakpoint at path:line (1-based). Idempotent. Place breakpoints along the " +
            "suspect data-flow path, then start_debug and await_pause to observe the real execution. " +
            "Optional 'condition' pauses only when it is true (e.g. \"userId == null\") — use it to " +
            "catch the ONE interesting iteration instead of stepping through thousands. Optional " +
            "'temporary' removes the breakpoint after its first hit. To trace a value WITHOUT " +
            "stopping, prefer set_tracepoint."
    override val parametersJsonSchema = """
        {"type":"object","properties":{
          "path":{"type":"string","description":"Source file (project-relative or absolute)"},
          "line":{"type":"integer","description":"1-based line number"},
          "condition":{"type":"string","description":"Pause only when this expression is true, e.g. \"id == 42\" (optional)"},
          "temporary":{"type":"boolean","description":"Auto-remove after the first hit (optional, default false)"}
        },"required":["path","line"]}
    """.trimIndent()

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val path = args.string("path")
        val line = args.intOrNull("line") ?: return ToolResult.error("Missing required integer 'line'")
        val condition = args.stringOrNull("condition")
        val temporary = args.boolean("temporary", false)
        val file = FsPaths.resolve(context.project, path) ?: return ToolResult.error("File not found: $path")
        if (file.isDirectory) return ToolResult.error("Path is a directory: $path")
        return DebuggerSupport.addBreakpoint(context.project, file, line, condition, temporary)
    }
}
