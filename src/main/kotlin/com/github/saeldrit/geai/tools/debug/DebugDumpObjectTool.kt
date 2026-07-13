package com.github.saeldrit.geai.tools.debug

import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult

object DebugDumpObjectTool : AgentTool {
    override val name = "debug_dump_object"
    override val description =
        "Dump an object and its fields N levels deep in ONE call (instead of probing field-by-field with " +
            "debug_evaluate). Give an expression (a variable or accessor) and a depth; returns an indented " +
            "tree of fields with real values — lazy/proxy values (jOOQ, Hibernate) are materialised, and " +
            "large collections are truncated per level. Use this FIRST to understand an object at a breakpoint."
    override val parametersJsonSchema = """
        {"type":"object","properties":{
          "expression":{"type":"string","description":"The object to dump — a variable name or an accessor evaluated in the paused frame"},
          "depth":{"type":"integer","description":"How many levels deep to expand (default 3, max 5)"},
          "timeout_seconds":{"type":"integer","description":"Max seconds per value (default 8, max 60)"}
        },"required":["expression"]}
    """.trimIndent()

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val expression = args.string("expression")
        val depth = args.int("depth", 3)
        val timeoutMs = args.int("timeout_seconds", 8).coerceIn(1, 60) * 1000L
        return FrameInspection.dumpObject(context.project, expression, depth, timeoutMs)
    }
}
