package com.github.saeldrit.geai.tools.debug

import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult

object DebugTraceTool : AgentTool {
    override val name = "debug_trace"
    override val description =
        "Walk the debugger N steps in ONE call, evaluating a watched expression at each stop. " +
            "Returns the FULL trace: step 0 (starting state), then step 1, 2, … N — each with " +
            "file:line, the expression's current value, and local variables. Use this INSTEAD of " +
            "calling debug_step + debug_evaluate N times — it saves N-1 LLM round-trips. " +
            "Great for tracing how a value changes as execution advances."
    override val parametersJsonSchema = """
        {"type":"object","properties":{
          "expression":{"type":"string","description":"The expression to trace across steps — a variable name or accessor you're watching"},
          "kind":{"type":"string","enum":["over","into","out"],"description":"Step kind: over=step over line, into=step into call, out=step out of method"},
          "max_steps":{"type":"integer","description":"How many steps to take (default 5, max 20). Each step waits for the next pause."},
          "timeout_seconds":{"type":"integer","description":"Max seconds PER step (default 20, max 60)"}
        },"required":["expression","kind"]}
    """.trimIndent()

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val expression = args.string("expression")
        val kind = when (args.string("kind").trim().lowercase()) {
            "over" -> StepKind.OVER
            "into" -> StepKind.INTO
            "out" -> StepKind.OUT
            else -> return ToolResult.error("'kind' must be one of: over, into, out.")
        }
        val maxSteps = args.int("max_steps", 5).coerceIn(1, 20)
        val perStepTimeoutMs = args.int("timeout_seconds", 20).coerceIn(1, 60) * 1000L

        val project = context.project
        val builder = StringBuilder()

        val startLoc = DebuggerSupport.pausedSessionDescription(project) ?: "unknown"
        val startValue = FrameInspection.evaluateSingle(project, expression, perStepTimeoutMs)
        builder.appendLine("── Trace: $expression (${kind.name.lowercase()}) ──")
        builder.appendLine("[0] $startLoc")
        builder.appendLine("    $expression = $startValue")

        for (i in 1..maxSteps) {
            if (context.indicator.isCanceled) {
                builder.appendLine("    ⚠ Tracing cancelled at step $i.")
                break
            }

            val stepResult = DebuggerSupport.step(project, kind, perStepTimeoutMs, context.indicator)
            if (stepResult.isError) {
                builder.appendLine("    ⚠ Step $i error: ${stepResult.content}")
                break
            }

            val loc = DebuggerSupport.pausedSessionDescription(project)
            if (loc == null) {
                builder.appendLine("[$i] ${stepResult.content}")
                break
            }

            val value = FrameInspection.evaluateSingle(project, expression, perStepTimeoutMs)
            builder.appendLine("[$i] $loc")
            builder.appendLine("    $expression = $value")

            if (i <= 3 || i == maxSteps) {
                val vars = FrameInspection.locals(project, perStepTimeoutMs)
                if (!vars.isError) {
                    val varLines = vars.content.lines().take(8)
                    if (varLines.isNotEmpty()) {
                        builder.appendLine("    locals:")
                        varLines.forEach { builder.appendLine("      $it") }
                    }
                }
            }
        }
        builder.appendLine("── trace end ──")
        return ToolResult.ok(builder.toString().trimEnd())
    }
}
