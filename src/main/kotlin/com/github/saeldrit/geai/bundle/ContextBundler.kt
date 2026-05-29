package com.github.saeldrit.geai.bundle

import com.github.saeldrit.geai.anchor.AnchorException
import com.github.saeldrit.geai.anchor.AnchorResolvers
import com.github.saeldrit.geai.graph.EdgeKind
import com.github.saeldrit.geai.graph.GeaiGraphStore
import com.github.saeldrit.geai.graph.GraphEdge
import com.github.saeldrit.geai.graph.GraphNode
import com.github.saeldrit.geai.spec.SpecStore
import com.intellij.openapi.project.Project

/**
 * Assembles a minimal, precise context bundle for a task by walking the GRACE graph
 * (seed → expand → rank → resolve → pack). The navigator tier produces this cheaply; the author
 * tier writes code from it without spending tokens to "understand the project". Category-A rules
 * are packed verbatim; Category-B contracts/symbols are resolved live, never recalled.
 */
object ContextBundler {

    private const val CHAR_BUDGET = 12_000
    private const val MAX_EXPAND = 400
    private const val MAX_RESOLVE = 12
    private const val SEED_FALLBACK = 8

    data class Bundle(val text: String, val nodeIds: List<String>, val resolved: Int, val rules: Int)

    fun build(project: Project, query: String, seedIds: List<String>, maxNodes: Int, hops: Int): Bundle {
        val store = GeaiGraphStore.getInstance(project)
        val graph = store.graph()
        if (graph.nodes.isEmpty()) {
            return Bundle("Graph is empty. Run graph_reindex first.", emptyList(), 0, 0)
        }
        val nodesById = graph.nodes.associateBy { it.id }

        val seeds = if (seedIds.isNotEmpty()) {
            seedIds.mapNotNull { nodesById[it] }
        } else {
            store.query(null, query, null, SEED_FALLBACK)
        }
        if (seeds.isEmpty()) {
            return Bundle("No seeds matched '$query'. Try graph_query or pass explicit seed_ids.", emptyList(), 0, 0)
        }

        val seedSet = seeds.map { it.id }.toSet()
        val hopOf = expand(graph.edges, seedSet, hops)
        val candidates = hopOf.mapNotNull { (id, hop) -> nodesById[id]?.let { RankCandidate(it, hop) } }
        val ranked = Rankers.active().rank(query, seedSet, candidates).take(maxNodes)
        val rankedIds = ranked.map { it.node.id }.toSet()

        val governingSpecIds = graph.edges
            .filter { it.kind == EdgeKind.GOVERNED_BY && (it.from in rankedIds || it.from in seedSet) }
            .map { it.to }
            .toSet()

        val rules = renderRules(project, governingSpecIds)
        val resolved = renderResolved(project, ranked)
        val neighborhood = renderNeighborhood(ranked)

        val text = buildString {
            appendLine("# Context bundle for: $query")
            appendLine("seeds: ${seedSet.joinToString()}")
            if (rules.isNotBlank()) {
                appendLine("\n## Governing rules (Category A — obey verbatim)")
                append(rules)
            }
            if (resolved.text.isNotBlank()) {
                appendLine("\n\n## Live contracts & symbols (Category B — resolved now)")
                append(resolved.text)
            }
            if (neighborhood.isNotBlank()) {
                appendLine("\n\n## Neighborhood (navigate with graph_neighbors / resolve_ref)")
                append(neighborhood)
            }
        }.let { if (it.length > CHAR_BUDGET) it.take(CHAR_BUDGET) + "\n…[bundle truncated to budget]" else it }

        return Bundle(text, ranked.map { it.node.id }, resolved.count, governingSpecIds.size)
    }

    /** BFS from seeds up to [hops], recording the minimum hop distance per reached node. */
    private fun expand(edges: List<GraphEdge>, seedSet: Set<String>, hops: Int): Map<String, Int> {
        val adjacency = HashMap<String, MutableList<GraphEdge>>()
        edges.forEach { edge ->
            adjacency.getOrPut(edge.from) { mutableListOf() }.add(edge)
            adjacency.getOrPut(edge.to) { mutableListOf() }.add(edge)
        }
        val hopOf = LinkedHashMap<String, Int>()
        seedSet.forEach { hopOf[it] = 0 }
        var frontier = seedSet.toList()
        for (depth in 1..hops.coerceAtLeast(1)) {
            val next = mutableListOf<String>()
            frontier.forEach { id ->
                adjacency[id].orEmpty().forEach { edge ->
                    val other = if (edge.from == id) edge.to else edge.from
                    if (other !in hopOf) {
                        hopOf[other] = depth
                        next.add(other)
                    }
                }
            }
            frontier = next
            if (hopOf.size >= MAX_EXPAND) break
        }
        return hopOf
    }

    private fun renderRules(project: Project, governingSpecIds: Set<String>): String {
        val store = SpecStore.getInstance(project)
        return governingSpecIds.mapNotNull { specNodeId ->
            val spec = store.find(specNodeId.removePrefix("spec:")) ?: return@mapNotNull null
            val content = spec.items.filter { !it.kind.isReference && it.body.isNotBlank() }
            if (content.isEmpty()) null else buildString {
                appendLine("• ${spec.id} — ${spec.title}")
                content.forEach { appendLine("    [${it.kind.name}] ${it.body}") }
            }
        }.joinToString("").trimEnd()
    }

    private data class Resolved(val text: String, val count: Int)

    private fun renderResolved(project: Project, ranked: List<RankCandidate>): Resolved {
        var count = 0
        val text = ranked.asSequence()
            .mapNotNull { it.node.anchor?.let { anchor -> it.node to anchor } }
            .take(MAX_RESOLVE)
            .map { (node, anchor) ->
                try {
                    val live = AnchorResolvers.resolve(anchor, project)
                    count++
                    val head = live.signature ?: live.location ?: anchor
                    "• ${node.kind} $anchor\n    $head"
                } catch (e: AnchorException) {
                    "• ${node.kind} $anchor\n    [unresolved: ${e.message}]"
                }
            }
            .joinToString("\n")
        return Resolved(text, count)
    }

    private fun renderNeighborhood(ranked: List<RankCandidate>): String =
        ranked.joinToString("\n") { candidate ->
            val summary = candidate.node.summary?.let { " — $it" } ?: ""
            "[${candidate.node.kind} h${candidate.hops}] ${candidate.node.id}$summary"
        }
}
