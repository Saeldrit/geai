package com.github.saeldrit.geai.bundle

import com.github.saeldrit.geai.graph.GraphNode
import com.github.saeldrit.geai.graph.NodeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicRankerTest {

    private fun symbol(id: String, name: String, summary: String?) =
        GraphNode(id, NodeKind.SYMBOL, name, id, summary, emptyList())

    @Test
    fun `relevant near node outranks irrelevant far file`() {
        val relevant = RankCandidate(symbol("psi:com.app.GoalService", "GoalService", "class com.app.GoalService"), hops = 1)
        val farFile = RankCandidate(GraphNode("file:misc/Util.kt", NodeKind.FILE, "Util.kt", "file:misc/Util.kt", null, emptyList()), hops = 3)

        val ranked = DeterministicRanker.rank("goal service create", seedIds = setOf("seed"), candidates = listOf(farFile, relevant))

        assertEquals("query-matching, closer, higher-prior node ranks first", "psi:com.app.GoalService", ranked.first().node.id)
    }

    @Test
    fun `governing rule outranks plain file at equal distance`() {
        val rule = RankCandidate(GraphNode("spec:goals#POLICY:p1", NodeKind.POLICY, "p1", null, "additive changes only", emptyList()), hops = 2)
        val file = RankCandidate(GraphNode("file:x.kt", NodeKind.FILE, "x.kt", "file:x.kt", null, emptyList()), hops = 2)

        val ranked = DeterministicRanker.rank("", seedIds = emptySet(), candidates = listOf(file, rule))

        assertTrue("kind prior favours POLICY over FILE", ranked.first().node.kind == NodeKind.POLICY)
    }
}
