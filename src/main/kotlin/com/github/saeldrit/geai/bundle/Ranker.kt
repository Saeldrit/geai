package com.github.saeldrit.geai.bundle

import com.github.saeldrit.geai.graph.GraphNode

/** A graph node reached during expansion, with its minimum hop distance from the seed set. */
data class RankCandidate(val node: GraphNode, val hops: Int)

/**
 * Relevance ranking seam for context bundles. The deterministic ranker ships by default; a vector
 * (embedding) ranker can implement this interface later and grow with project knowledge, without
 * changing the bundler. This is GRACE's "vector layer present from day one, paid only when useful".
 */
interface Ranker {
    val id: String
    fun rank(query: String, seedIds: Set<String>, candidates: List<RankCandidate>): List<RankCandidate>
}
