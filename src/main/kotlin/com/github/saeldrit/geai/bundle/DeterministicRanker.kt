package com.github.saeldrit.geai.bundle

import com.github.saeldrit.geai.graph.NodeKind

/**
 * Dependency-free default ranker: closeness to the seed (graph hops), term overlap with the query,
 * and a kind prior that favours governing rules and contracts over plain files. Predictable and
 * cheap — no embeddings, no network, works offline on an empty index.
 */
object DeterministicRanker : Ranker {

    override val id = "deterministic"

    override fun rank(query: String, seedIds: Set<String>, candidates: List<RankCandidate>): List<RankCandidate> {
        val terms = tokenize(query)
        return candidates.sortedByDescending { score(it, terms) }
    }

    private fun score(candidate: RankCandidate, terms: Set<String>): Double {
        val hopScore = 1.0 / (1 + candidate.hops)
        val node = candidate.node
        val textScore = if (terms.isEmpty()) {
            0.0
        } else {
            val nodeTerms = tokenize("${node.name} ${node.id} ${node.summary.orEmpty()}")
            terms.count { it in nodeTerms }.toDouble() / terms.size
        }
        return 2.0 * hopScore + 1.5 * textScore + kindWeight(node.kind)
    }

    private fun kindWeight(kind: NodeKind): Double = when (kind) {
        NodeKind.INVARIANT, NodeKind.POLICY -> 1.0
        NodeKind.CONTRACT, NodeKind.STATE_MACHINE, NodeKind.FORMULA -> 0.8
        NodeKind.SYMBOL -> 0.6
        NodeKind.SPEC, NodeKind.INTENT -> 0.5
        NodeKind.FILE -> 0.2
    }

    private fun tokenize(text: String): Set<String> =
        text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()
}
