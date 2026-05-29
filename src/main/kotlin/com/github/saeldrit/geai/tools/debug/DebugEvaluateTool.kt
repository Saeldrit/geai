package com.github.saeldrit.geai.tools.debug

import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult

/** Evaluates an arbitrary expression in the currently paused stack frame and returns its value. */
object DebugEvaluateTool : AgentTool {
    override val name = "debug_evaluate"
    override val description =
        "Evaluate an expression in the current paused stack frame (e.g. a variable name, a field " +
            "access, or a call) and return its value. Use this to test hypotheses about the runtime state."
    override val parametersJsonSchema = """
        {"type":"object","properties":{
          "expression":{"type":"string","description":"Expression to evaluate in the paused frame's language"},
          "timeout_seconds":{"type":"integer","description":"Max seconds to wait (default 8, max 60)"}
        },"required":["expression"]}
    """.trimIndent()

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val expression = args.string("expression")
        val timeoutMs = args.int("timeout_seconds", 8).coerceIn(1, 60) * 1000L
        return FrameInspection.evaluate(context.project, expression, timeoutMs)
    }
}
