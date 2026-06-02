package com.github.saeldrit.geai.tools.debug

import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult

/**
 * Advances the paused debugger yourself — the agent drives execution instead of asking the user to
 * step. Issues the step/resume and WAITS for the next pause, returning the new `file:line`, so one
 * call both moves and reports where you landed. Walk the suspect path with repeated calls.
 *
 * Use `repeat` to take MULTIPLE steps in ONE tool call — this saves LLM round-trips.
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
          "repeat":{"type":"integer","description":"How many steps to take in ONE call (default 1, max 30). Each step waits for the next pause. Saves LLM round-trips."},
          "timeout_seconds":{"type":"integer","description":"Max seconds to wait PER step (default 20, max 300)"}
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
        val repeat = args.int("repeat", 1).coerceIn(1, 30)
        val perStepTimeoutMs = args.int("timeout_seconds", 20).coerceIn(1, 300) * 1000L
        val totalTimeoutMs = (perStepTimeoutMs * repeat).coerceAtMost(300_000L)

        if (repeat <= 1) {
            return DebuggerSupport.step(context.project, kind, totalTimeoutMs, context.indicator)
        }

        val builder = StringBuilder()
        for (i in 1..repeat) {
            val result = DebuggerSupport.step(context.project, kind, perStepTimeoutMs, context.indicator)
            builder.append("[$i/$repeat] ").appendLine(result.content)
            if (result.isError || result.content.contains("ended")) break
        }
        return ToolResult.ok(builder.toString().trimEnd())
    }
}
