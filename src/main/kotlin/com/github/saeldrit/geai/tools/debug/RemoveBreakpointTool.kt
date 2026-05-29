package com.github.saeldrit.geai.tools.debug

import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult
import com.github.saeldrit.geai.tools.fs.FsPaths

/** Removes a line breakpoint at a specific line, or all breakpoints in a file. */
object RemoveBreakpointTool : AgentTool {
    override val name = "remove_breakpoint"
    override val description =
        "Remove the breakpoint at path:line (1-based). Omit 'line' to clear all breakpoints in the file."
    override val parametersJsonSchema = """
        {"type":"object","properties":{
          "path":{"type":"string","description":"Source file (project-relative or absolute)"},
          "line":{"type":"integer","description":"1-based line; omit to remove all in the file"}
        },"required":["path"]}
    """.trimIndent()

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val path = args.string("path")
        val line = args.intOrNull("line")
        val file = FsPaths.resolve(context.project, path) ?: return ToolResult.error("File not found: $path")
        return DebuggerSupport.removeBreakpoint(context.project, file, line)
    }
}
