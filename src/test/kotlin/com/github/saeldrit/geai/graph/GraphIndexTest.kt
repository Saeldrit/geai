package com.github.saeldrit.geai.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the [CodeGraph] lookup indexes (nodesById / edgesByEndpoint) used by the context bundle's
 * graph walk (ContextBundler.expand). The key guarantee is parity: the indexed neighbor set must equal
 * what a full edge scan produced, for every node and direction.
 */
class GraphIndexTest {

    private fun node(id: String) = GraphNode(id, NodeKind.SYMBOL, id.substringAfterLast(':'), null, null, emptyList())
    private fun edge(from: String, to: String, kind: EdgeKind = EdgeKind.CONTAINS) = GraphEdge(from, to, kind)

    @Test
    fun `nodesById resolves by id and keeps the first on a collision`() {
        val first = node("a").copy(name = "first")
        val dup = node("a").copy(name = "second")
        val graph = CodeGraph(listOf(first, node("b"), dup), emptyList())
        assertSame("first id wins, matching the old firstOrNull", first, graph.nodesById["a"])
        assertEquals("b", graph.nodesById["b"]?.id)
        assertNull(graph.nodesById["missing"])
    }

    @Test
    fun `edgesByEndpoint files every edge under both endpoints`() {
        val e1 = edge("a", "b")
        val e2 = edge("b", "c")
        val graph = CodeGraph(listOf(node("a"), node("b"), node("c")), listOf(e1, e2))
        assertEquals(listOf(e1), graph.edgesByEndpoint["a"])
        assertEquals("b is touched by both edges", listOf(e1, e2), graph.edgesByEndpoint["b"])
        assertEquals(listOf(e2), graph.edgesByEndpoint["c"])
        assertTrue(graph.edgesByEndpoint["x"].isNullOrEmpty())
    }

    @Test
    fun `a self-loop edge is filed once, not twice`() {
        val loop = edge("a", "a")
        val graph = CodeGraph(listOf(node("a")), listOf(loop))
        assertEquals(listOf(loop), graph.edgesByEndpoint["a"])
    }

    @Test
    fun `endpoint index yields the same neighbors as a full edge scan`() {
        val edges = listOf(
            edge("a", "b", EdgeKind.CONTAINS),
            edge("a", "c", EdgeKind.IMPLEMENTS),
            edge("d", "a", EdgeKind.GOVERNED_BY),
            edge("b", "c", EdgeKind.REFS),
        )
        val graph = CodeGraph(listOf("a", "b", "c", "d").map(::node), edges)

        for (id in listOf("a", "b", "c", "d", "missing")) {
            val indexed = graph.edgesByEndpoint[id].orEmpty()
            assertEquals("BOTH for $id", edges.filter { it.from == id || it.to == id }, indexed.filter { it.from == id || it.to == id })
            assertEquals("OUT for $id", edges.filter { it.from == id }, indexed.filter { it.from == id })
            assertEquals("IN for $id", edges.filter { it.to == id }, indexed.filter { it.to == id })
        }
    }
}
