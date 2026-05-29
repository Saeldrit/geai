package com.github.saeldrit.geai.tools.grace

import com.github.saeldrit.geai.graph.GeaiGraphStore
import com.github.saeldrit.geai.graph.GraphIndexer
import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult

/**
 * Rebuilds the derived GRACE graph from current code + specs. Safe and idempotent — touches only
 * the gitignored `.geai/graph` index, never user code — so it runs without approval. Call after
 * structural edits or new specs so graph_query/graph_neighbors reflect reality.
 */
object GraphReindexTool : AgentTool {
    override val name = "graph_reindex"
    override val description =
        "Rebuild the GRACE code graph (files -> classes -> methods, inheritance, and spec governance " +
            "edges) from the current project. Run after adding code/specs before relying on graph_query."
    override val parametersJsonSchema = """{"type":"object","properties":{}}"""

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult = runCatching {
        val graph = GraphIndexer.reindex(context.project)
        GeaiGraphStore.getInstance(context.project).replaceAll(graph)
        ToolResult.ok("Graph rebuilt: ${graph.nodes.size} nodes, ${graph.edges.size} edges.")
    }.getOrElse { ToolResult.error("graph_reindex failed: ${it.message}") }
}
