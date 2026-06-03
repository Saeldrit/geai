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

/** A whole graph snapshot. Immutable, so the lookup indexes below are built once on first use and
 *  reused for the snapshot's life — a reindex replaces the whole object, not its contents. */
data class CodeGraph(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
) {
    /** id -> node. Turns findNode and per-edge label lookups from O(N) scans into O(1). First id wins
     *  if ids ever collide, matching the previous firstOrNull behavior. */
    val nodesById: Map<String, GraphNode> by lazy {
        LinkedHashMap<String, GraphNode>(nodes.size).apply { nodes.forEach { putIfAbsent(it.id, it) } }
    }

    /** node id -> every edge touching it (filed under both endpoints). Turns neighbors() and the
     *  bundle's BFS adjacency from O(E) scans/rebuilds into O(degree), built once per snapshot. */
    val edgesByEndpoint: Map<String, List<GraphEdge>> by lazy {
        val map = HashMap<String, MutableList<GraphEdge>>(nodes.size.coerceAtLeast(16))
        edges.forEach { edge ->
            map.getOrPut(edge.from) { mutableListOf() }.add(edge)
            if (edge.to != edge.from) map.getOrPut(edge.to) { mutableListOf() }.add(edge)
        }
        map
    }

    companion object {
        val EMPTY = CodeGraph(emptyList(), emptyList())
    }
}
