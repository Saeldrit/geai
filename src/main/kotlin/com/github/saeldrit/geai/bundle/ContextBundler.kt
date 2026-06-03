package com.github.saeldrit.geai.bundle

import com.github.saeldrit.geai.anchor.AnchorException
import com.github.saeldrit.geai.anchor.AnchorResolvers
import com.github.saeldrit.geai.graph.EdgeKind
import com.github.saeldrit.geai.graph.GeaiGraphStore
import com.github.saeldrit.geai.graph.GraphEdge
import com.github.saeldrit.geai.graph.NodeKind
import com.github.saeldrit.geai.spec.SpecStore
import com.intellij.openapi.project.Project

/**
 * Assembles a minimal, precise context bundle by walking the GRACE graph
 * (seed → expand → rank → resolve → pack). Output is a set of WHOLE XML atoms: under budget,
 * least-relevant atoms are dropped entirely — never truncated mid-content — so every atom the model
 * sees is complete and un-torn. Governing rules (Category A) are protected from dropping; contracts
 * and symbols (Category B) are resolved live, never recalled.
 */
object ContextBundler {

    private const val CHAR_BUDGET = 12_000
    private const val MAX_EXPAND = 400
    private const val MAX_RESOLVE = 12
    private const val SEED_FALLBACK = 8

    data class Bundle(
        val text: String,
        val nodeIds: List<String>,
        val resolved: Int,
        val rules: Int,
        val dropped: Int,
    )

    fun build(project: Project, query: String, seedIds: List<String>, maxNodes: Int, hops: Int, charBudget: Int = CHAR_BUDGET): Bundle {
        val store = GeaiGraphStore.getInstance(project)
        val graph = store.graph()
        if (graph.nodes.isEmpty()) {
            return Bundle("Graph is empty. Run graph_reindex first.", emptyList(), 0, 0, 0)
        }
        val nodesById = graph.nodesById

        val seeds = if (seedIds.isNotEmpty()) seedIds.mapNotNull { nodesById[it] } else store.query(null, query, null, SEED_FALLBACK)
        if (seeds.isEmpty()) {
            return Bundle("No seeds matched '$query'. Try graph_query or pass explicit seed_ids.", emptyList(), 0, 0, 0)
        }

        val seedSet = seeds.map { it.id }.toSet()
        val hopOf = expand(graph.edgesByEndpoint, seedSet, hops)
        val candidates = hopOf.mapNotNull { (id, hop) -> nodesById[id]?.let { RankCandidate(it, hop) } }
        val ranked = Rankers.active().rank(query, seedSet, candidates).take(maxNodes)
        val rankedIds = ranked.map { it.node.id }.toSet()

        val governingSpecIds = graph.edges
            .filter { it.kind == EdgeKind.GOVERNED_BY && (it.from in rankedIds || it.from in seedSet) }
            .map { it.to }
            .toSet()

        // Build atoms in priority order: protected rules → resolved contracts/symbols → nav nodes.
        val ruleAtoms = ruleAtoms(project, governingSpecIds)
        var resolvedCount = 0
        val anchored = ranked.filter { it.node.anchor != null }.take(MAX_RESOLVE)
        val resolvedAtoms = anchored.map { candidate ->
            val anchor = candidate.node.anchor!!
            val isContract = candidate.node.kind == NodeKind.CONTRACT
            try {
                val live = AnchorResolvers.resolve(anchor, project)
                resolvedCount++
                Atom(
                    id = candidate.node.id,
                    tag = if (isContract) "contract" else "sym",
                    attrs = mapOf("id" to candidate.node.id, "anchor" to anchor) + (live.location?.let { mapOf("loc" to it) } ?: emptyMap()),
                    body = live.signature?.let { "$it\n${live.content}" } ?: live.content,
                )
            } catch (e: AnchorException) {
                Atom(candidate.node.id, "sym", mapOf("id" to candidate.node.id, "anchor" to anchor), "[unresolved: ${e.message}]")
            }
        }
        val resolvedIds = resolvedAtoms.map { it.id }.toSet()
        val navAtoms = ranked.filterNot { it.node.id in resolvedIds }.map { candidate ->
            Atom(
                id = candidate.node.id,
                tag = "node",
                attrs = mapOf("id" to candidate.node.id, "kind" to candidate.node.kind.name),
                body = candidate.node.summary ?: candidate.node.name,
            )
        }

        // Whole-or-absent packing: protected rules always in; others added while they fit entirely.
        val ordered = ruleAtoms + resolvedAtoms + navAtoms
        var used = 0
        val decided = ordered.map { atom ->
            val include = atom.protected || used + atom.chars <= charBudget
            if (include) used += atom.chars
            atom to include
        }
        val included = decided.filter { it.second }.map { it.first }
        val dropped = decided.count { !it.second }

        GraceTelemetry.getInstance(project).recordBundle(query, decided, charBudget, used, System.currentTimeMillis())

        val text = buildString {
            appendLine("<bundle query=\"${query.replace("\"", "'")}\" atoms=\"${included.size}\" dropped=\"$dropped\">")
            included.forEach { append(it.render()); append("\n") }
            append("</bundle>")
        }

        return Bundle(text, included.map { it.id }, resolvedCount, ruleAtoms.size, dropped)
    }

    private fun ruleAtoms(project: Project, governingSpecIds: Set<String>): List<Atom> {
        val store = SpecStore.getInstance(project)
        return governingSpecIds.flatMap { specNodeId ->
            val spec = store.find(specNodeId.removePrefix("spec:")) ?: return@flatMap emptyList()
            spec.items.filter { !it.kind.isReference && it.body.isNotBlank() }.map { item ->
                Atom(
                    id = "${spec.id}/${item.kind.name}/${item.id ?: ""}",
                    tag = "rule",
                    attrs = mapOf("kind" to item.kind.name, "spec" to spec.id) + (item.id?.let { mapOf("id" to it) } ?: emptyMap()),
                    body = item.body,
                    protected = true,
                )
            }
        }
    }

    /**
     * BFS from seeds up to [hops], recording the minimum hop distance per reached node. Walks the
     * snapshot's prebuilt endpoint adjacency (each edge filed under both endpoints) instead of
     * rebuilding the full adjacency map on every turn.
     */
    private fun expand(adjacency: Map<String, List<GraphEdge>>, seedSet: Set<String>, hops: Int): Map<String, Int> {
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
}
