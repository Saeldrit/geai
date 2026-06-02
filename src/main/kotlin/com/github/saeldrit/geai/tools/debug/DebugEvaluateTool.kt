package com.github.saeldrit.geai.tools.debug

import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult

/** Evaluates one or more expressions in the currently paused stack frame and returns their values. */
object DebugEvaluateTool : AgentTool {
    override val name = "debug_evaluate"
    override val description =
        "Evaluate one or more expressions in the current paused stack frame and return their values. " +
            "Pass SEVERAL at once in 'expressions' instead of calling this repeatedly — they share the " +
            "frame and run in one round-trip. Lazy/proxy values (jOOQ, Hibernate, deferred collections) " +
            "are materialised automatically, so a concrete accessor (e.g. `order.items()`, `list.size()`) " +
            "returns the real value, not a 'Collecting data…' placeholder. To see a whole object's fields, " +
            "prefer debug_dump_object over many single-field evaluations."
    override val parametersJsonSchema = """
        {"type":"object","properties":{
          "expressions":{"type":"array","items":{"type":"string"},"description":"One or more expressions to evaluate in the paused frame. Pass several at once instead of calling repeatedly."},
          "timeout_seconds":{"type":"integer","description":"Max seconds per expression (default 8, max 60)"}
        },"required":["expressions"]}
    """.trimIndent()

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        // Accept the batch form, and tolerate a single "expression" string for backward compatibility.
        val expressions = args.stringList("expressions").ifEmpty { listOfNotNull(args.stringOrNull("expression")) }
        if (expressions.isEmpty()) return ToolResult.error("Provide 'expressions' (array of expressions to evaluate).")
        val timeoutMs = args.int("timeout_seconds", 8).coerceIn(1, 60) * 1000L
        return FrameInspection.evaluateAll(context.project, expressions, timeoutMs)
    }
}
