package com.github.saeldrit.geai.tools.grace

import com.github.saeldrit.geai.graph.GeaiGraphStore
import com.github.saeldrit.geai.graph.NodeKind
import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult

/**
 * Finds nodes in the GRACE graph by kind/name/tag. Returns node ids (which double as anchors for
 * code nodes) so the agent can then resolve_ref or graph_neighbors. Cheap structural navigation
 * without grepping or reading whole files.
 */
object GraphQueryTool : AgentTool {
    override val name = "graph_query"
    override val description =
        "Find nodes in the GRACE code graph by kind (FILE/SYMBOL/CONTRACT/SPEC/INVARIANT/FORMULA/" +
            "STATE_MACHINE/INTENT/POLICY), name/text, or tag. Returns ids+anchors. Run graph_reindex " +
            "first if the graph is empty. Use graph_neighbors to walk edges from a result."
    override val parametersJsonSchema = """
        {"type":"object","properties":{
          "kind":{"type":"string","description":"Restrict to one node kind (optional)"},
          "query":{"type":"string","description":"Free-text over name/id/anchor/summary/tags (optional)"},
          "tag":{"type":"string","description":"Match nodes carrying this tag (optional)"},
          "max_results":{"type":"integer","description":"Cap (default 40)"}
        }}
    """.trimIndent()

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val kind = args.stringOrNull("kind")?.let { runCatching { NodeKind.valueOf(it.uppercase()) }.getOrNull() }
        val query = args.stringOrNull("query")
        val tag = args.stringOrNull("tag")
        val max = args.int("max_results", 40).coerceIn(1, 200)

        val store = GeaiGraphStore.getInstance(context.project)
        if (store.graph().nodes.isEmpty()) {
            return ToolResult.ok("Graph is empty. Run graph_reindex to build it from code + specs.")
        }
        val results = store.query(kind, query, tag, max)
        if (results.isEmpty()) return ToolResult.ok("No nodes matched. Try graph_reindex or a broader filter.")

        val rows = results.joinToString("\n") { node ->
            val anchor = node.anchor?.let { "  anchor=$it" } ?: ""
            val summary = node.summary?.let { " — $it" } ?: ""
            "[${node.kind}] ${node.id}$summary$anchor"
        }
        return ToolResult.ok("${results.size} node(s):\n$rows")
    }
}
