package com.github.saeldrit.geai.tools.grace

import com.github.saeldrit.geai.bundle.ContextBundler
import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult

/**
 * Assembles a minimal, precise context bundle for a task from the GRACE graph: governing rules
 * (Category A, verbatim) + live contracts/symbols (Category B, resolved now) + a navigable
 * neighborhood. The cheapest path to "understand enough to act" — prefer this over scattered
 * search_text/read_file when starting a feature or diagnosis.
 */
object ContextBundleTool : AgentTool {
    override val name = "context_bundle"
    override val description =
        "Build a minimal context bundle for a task from live PSI + specs: governing rules, " +
            "live-resolved contracts/symbols, and a neighborhood to navigate. Start here for a feature or diagnosis."
    override val parametersJsonSchema = """
        {"type":"object","properties":{
          "query":{"type":"string","description":"The task or question to gather context for"},
          "seed_ids":{"type":"array","items":{"type":"string"},"description":"Explicit graph node ids to seed from (optional; else derived from query)"},
          "max_nodes":{"type":"integer","description":"Max ranked nodes to include (default 24)"},
          "hops":{"type":"integer","description":"Graph expansion depth from seeds (default 2)"}
        },"required":["query"]}
    """.trimIndent()

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val query = args.string("query").trim()
        val seedIds = args.stringList("seed_ids")
        val maxNodes = args.int("max_nodes", 24).coerceIn(1, 100)
        val hops = args.int("hops", 2).coerceIn(1, 5)

        val bundle = ContextBundler.build(context.project, query, seedIds, maxNodes, hops)
        val footer = "\n\n(${bundle.nodeIds.size} atoms, ${bundle.resolved} resolved, ${bundle.rules} rules, ${bundle.dropped} dropped to fit budget)"
        return ToolResult.ok(bundle.text + footer)
    }
}
