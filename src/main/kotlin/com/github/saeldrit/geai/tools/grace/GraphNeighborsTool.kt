package com.github.saeldrit.geai.tools.grace

import com.github.saeldrit.geai.graph.EdgeKind
import com.github.saeldrit.geai.graph.GeaiGraphStore
import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult

/**
 * Walks edges from a graph node. The key navigation for governance: from a SYMBOL/CONTRACT, follow
 * GOVERNED_BY to reach the specs (invariants/policies) that constrain it before changing code.
 */
object GraphNeighborsTool : AgentTool {
    override val name = "graph_neighbors"
    override val description =
        "List edges from a graph node. edge_kind: CONTAINS (nesting), IMPLEMENTS (inheritance), " +
            "REFS (spec -> anchor), GOVERNED_BY (code -> governing spec). direction: out|in|both. " +
            "Use GOVERNED_BY before editing a symbol to find the rules it must obey."
    override val parametersJsonSchema = """
        {"type":"object","properties":{
          "node_id":{"type":"string","description":"Node id from graph_query, e.g. psi:com.app.GoalService or spec:goals"},
          "edge_kind":{"type":"string","description":"Filter: CONTAINS|IMPLEMENTS|REFS|GOVERNED_BY (optional)"},
          "direction":{"type":"string","description":"out|in|both (default both)"},
          "max_results":{"type":"integer","description":"Cap (default 50)"}
        },"required":["node_id"]}
    """.trimIndent()

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val nodeId = args.string("node_id").trim()
        val edgeKind = args.stringOrNull("edge_kind")?.let { runCatching { EdgeKind.valueOf(it.uppercase()) }.getOrNull() }
        val direction = when (args.stringOrNull("direction")?.lowercase()) {
            "out" -> GeaiGraphStore.Direction.OUT
            "in" -> GeaiGraphStore.Direction.IN
            else -> GeaiGraphStore.Direction.BOTH
        }
        val max = args.int("max_results", 50).coerceIn(1, 300)

        val store = GeaiGraphStore.getInstance(context.project)
        if (store.findNode(nodeId) == null) {
            return ToolResult.error("Node '$nodeId' not found. Use graph_query to find a valid id (or graph_reindex).")
        }
        val edges = store.neighbors(nodeId, edgeKind, direction).take(max)
        if (edges.isEmpty()) return ToolResult.ok("No matching edges from '$nodeId'.")

        val rows = edges.joinToString("\n") { edge ->
            val otherId = if (edge.from == nodeId) edge.to else edge.from
            val arrow = if (edge.from == nodeId) "→" else "←"
            val other = store.findNode(otherId)
            val label = other?.let { "[${it.kind}] ${it.name}" } ?: "(dangling)"
            "${edge.kind} $arrow $otherId  $label"
        }
        return ToolResult.ok("${edges.size} edge(s) for $nodeId:\n$rows")
    }
}
