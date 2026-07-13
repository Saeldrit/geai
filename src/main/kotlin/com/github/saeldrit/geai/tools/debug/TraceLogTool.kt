package com.github.saeldrit.geai.tools.debug

import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult

object TraceLogTool : AgentTool {
    override val name = "trace_log"
    override val idempotentPoll = true
    override val description =
        "Read the tracepoint log: every `file:line — expr = value` captured since tracepoints were " +
            "set (chronological). Call it after running the scenario to see how the value evolved " +
            "through the flow — the divergence point is where the recorded value first goes wrong. " +
            "Pass clear=true to reset the log before the next run."
    override val parametersJsonSchema = """
        {"type":"object","properties":{
          "clear":{"type":"boolean","description":"Clear the log after reading (default false)"}
        }}
    """.trimIndent()

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val clear = args.boolean("clear", false)
        val records = TracepointRecorder.log(context.project, clear)
        val active = TracepointRecorder.tracepointCount(context.project)
        if (records.isEmpty()) {
            return ToolResult.ok(
                "Trace log is empty ($active tracepoint(s) active). Either the scenario has not run " +
                    "yet, the traced lines were never reached (check the flow actually passes through " +
                    "them), or the log was just cleared.",
            )
        }
        val cleared = if (clear) " (log cleared)" else ""
        return ToolResult.ok("${records.size} tracepoint hit(s)$cleared:\n${records.joinToString("\n")}")
    }
}
