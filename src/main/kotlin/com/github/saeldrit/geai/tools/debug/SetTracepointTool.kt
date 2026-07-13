package com.github.saeldrit.geai.tools.debug

import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult
import com.github.saeldrit.geai.tools.fs.FsPaths

object SetTracepointTool : AgentTool {
    override val name = "set_tracepoint"
    override val description =
        "Set a TRACEPOINT at path:line — a breakpoint that does NOT stop the program: on every hit " +
            "it evaluates 'expression' in the paused frame, records `file:line — expr = value` to the " +
            "trace log, and resumes automatically. Use it to trace a value through its whole flow in " +
            "ONE run: set tracepoints along the suspect path (source → transformations → sink), " +
            "start_debug / trigger the scenario, then read `trace_log`. Massively faster than manual " +
            "stepping. Omit 'expression' to record only that the line was reached."
    override val parametersJsonSchema = """
        {"type":"object","properties":{
          "path":{"type":"string","description":"Source file (project-relative or absolute)"},
          "line":{"type":"integer","description":"1-based line number"},
          "expression":{"type":"string","description":"Expression to evaluate and record on each hit, e.g. 'user.id' or 'order.total'. Optional."}
        },"required":["path","line"]}
    """.trimIndent()

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val path = args.string("path")
        val line = args.intOrNull("line") ?: return ToolResult.error("Missing required integer 'line'")
        val expression = args.stringOrNull("expression")?.trim()?.takeIf { it.isNotEmpty() }
        val file = FsPaths.resolve(context.project, path) ?: return ToolResult.error("File not found: $path")
        if (file.isDirectory) return ToolResult.error("Path is a directory: $path")

        val result = DebuggerSupport.addBreakpoint(context.project, file, line)
        if (result.isError) return result
        TracepointRecorder.register(context.project, file.url, line - 1, expression)
        val what = expression?.let { "recording [$it]" } ?: "recording hits"
        return ToolResult.ok(
            "Tracepoint set at ${FsPaths.relativize(context.project, file)}:$line — $what on every hit, " +
                "auto-resuming. Run the scenario, then call trace_log to read the captured flow. " +
                "(${TracepointRecorder.tracepointCount(context.project)} tracepoint(s) active.)",
        )
    }
}
