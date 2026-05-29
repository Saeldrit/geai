package com.github.saeldrit.geai.graph

/**
 * GRACE code graph — the navigable map a cheap model walks instead of guessing. Nodes are project
 * entities (files, symbols, contracts) and Category-A spec items; edges are typed relations. Every
 * code node carries an [GraphNode.anchor] so its content is resolved live, never cached as prose.
 * The graph is derived: rebuilt from code plus specs, it is not a source of truth itself.
 */
enum class NodeKind {
    FILE, SYMBOL, CONTRACT,
    SPEC, INVARIANT, FORMULA, STATE_MACHINE, INTENT, POLICY,
}

enum class EdgeKind {
    /** structural nesting: file -> class -> method, spec -> item */
    CONTAINS,

    /** class -> its superclass / implemented interface */
    IMPLEMENTS,

    /** spec reference item -> the symbol/contract anchor it points at */
    REFS,

    /** code node -> the spec that governs it (the inverse navigation of REFS) */
    GOVERNED_BY,
}

data class GraphNode(
    val id: String,
    val kind: NodeKind,
    val name: String,
    /** Anchor (scheme:locator) to resolve live ground truth; null for pure Category-A nodes. */
    val anchor: String?,
    val summary: String?,
    val tags: List<String>,
)

data class GraphEdge(
    val from: String,
    val to: String,
    val kind: EdgeKind,
)

/** A whole graph snapshot. */
data class CodeGraph(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
) {
    companion object {
        val EMPTY = CodeGraph(emptyList(), emptyList())
    }
}
