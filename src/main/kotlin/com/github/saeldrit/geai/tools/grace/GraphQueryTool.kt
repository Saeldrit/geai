package com.github.saeldrit.geai.tools.grace

import com.github.saeldrit.geai.graph.NodeKind
import com.github.saeldrit.geai.graph.PsiStructure
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
        "Find code symbols (classes, by name substring) and governance spec headers — resolved LIVE from " +
            "IntelliJ's index + specs (no graph build needed). Returns psi:/spec: ids to feed graph_neighbors " +
            "or resolve_ref. For methods use find_symbol; for spec items use spec_lookup."
    override val parametersJsonSchema = """
        {"type":"object","properties":{
          "kind":{"type":"string","description":"Restrict: SYMBOL (classes) or SPEC (optional)"},
          "query":{"type":"string","description":"Name substring over classes / spec id+title (optional)"},
          "max_results":{"type":"integer","description":"Cap (default 40)"}
        }}
    """.trimIndent()

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val kind = args.stringOrNull("kind")?.let { runCatching { NodeKind.valueOf(it.uppercase()) }.getOrNull() }
        val query = args.stringOrNull("query")
        val max = args.int("max_results", 40).coerceIn(1, 200)

        // Live from IntelliJ's class index + the spec store — fresh and works with no graph build.
        val nodes = PsiStructure.findNodes(context.project, kind, query, max)
        if (nodes.isEmpty()) {
            return ToolResult.ok(
                "No nodes matched${query?.let { " '$it'" } ?: ""}. Broaden the query, or use find_symbol " +
                    "(precise symbol) / spec_lookup (spec items).",
            )
        }
        val rows = nodes.joinToString("\n") { node ->
            val anchor = node.anchor?.let { "  anchor=$it" } ?: ""
            val summary = node.summary?.let { " — $it" } ?: ""
            "[${node.kind}] ${node.id}$summary$anchor"
        }
        return ToolResult.ok("${nodes.size} node(s):\n$rows")
    }
}
