package com.github.saeldrit.geai.tools.debug

import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult

/**
 * Advances the paused debugger yourself — the agent drives execution instead of asking the user to
 * step. Issues the step/resume and WAITS for the next pause, returning the new `file:line`, so one
 * call both moves and reports where you landed. Walk the suspect path with repeated calls.
 */
object DebugStepTool : AgentTool {
    override val name = "debug_step"
    override val description =
        "Advance the PAUSED debugger and wait for the next pause, returning the new file:line. " +
            "kind: 'over' = run the current line and stop on the next; 'into' = step into the call on " +
            "this line; 'out' = run until the current method returns; 'resume' = continue to the next " +
            "breakpoint or program end. Drive the debugger YOURSELF with this — never ask the user to step."
    override val parametersJsonSchema = """
        {"type":"object","properties":{
          "kind":{"type":"string","enum":["over","into","out","resume"],"description":"over=step over line, into=step into call, out=step out of method, resume=continue to next breakpoint/end"},
          "timeout_seconds":{"type":"integer","description":"Max seconds to wait for the next pause (default 20, max 300)"}
        },"required":["kind"]}
    """.trimIndent()

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val kind = when (args.string("kind").trim().lowercase()) {
            "over" -> StepKind.OVER
            "into" -> StepKind.INTO
            "out" -> StepKind.OUT
            "resume" -> StepKind.RESUME
            else -> return ToolResult.error("'kind' must be one of: over, into, out, resume.")
        }
        val timeoutMs = args.int("timeout_seconds", 20).coerceIn(1, 300) * 1000L
        return DebuggerSupport.step(context.project, kind, timeoutMs, context.indicator)
    }
}
