package com.github.saeldrit.geai.tools.grace

import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * graph_neighbors now resolves code structure from LIVE PSI (no materialized graph). These assert it
 * works with NO graph build at all — the cold-project win — and reflects the real class structure.
 */
class GraphNeighborsToolTest : BasePlatformTestCase() {

    private fun neighbors(json: String): String =
        GraphNeighborsTool.execute(ToolArgs.parse(json), ToolContext(project, EmptyProgressIndicator())).content

    fun testContainsListsClassMethodsFromPsi() {
        myFixture.addFileToProject("p/Base.java", "package p;\npublic class Base { public void greet() {} }")
        myFixture.addFileToProject(
            "p/Sub.java",
            "package p;\npublic class Sub extends Base { public void run() {} public int compute() { return 1; } }",
        )
        val out = neighbors("""{"node_id":"psi:p.Sub","edge_kind":"CONTAINS","direction":"out"}""")
        assertTrue("class -> its declared methods, live from PSI", out.contains("psi:p.Sub#run") && out.contains("psi:p.Sub#compute"))
    }

    fun testImplementsListsSuperTypeNotObject() {
        myFixture.addFileToProject("p/Base.java", "package p;\npublic class Base {}")
        myFixture.addFileToProject("p/Sub.java", "package p;\npublic class Sub extends Base {}")
        val out = neighbors("""{"node_id":"psi:p.Sub","edge_kind":"IMPLEMENTS","direction":"out"}""")
        assertTrue("class -> its super type", out.contains("psi:p.Base"))
        assertFalse("java.lang.Object is filtered out", out.contains("java.lang.Object"))
    }

    fun testWorksWithNoGraphBuild() {
        // No graph_reindex anywhere — PSI is the source, so navigation is immediate on a cold project.
        myFixture.addFileToProject("p/Solo.java", "package p;\npublic class Solo { public void only() {} }")
        val out = neighbors("""{"node_id":"psi:p.Solo"}""")
        assertTrue("immediate PSI navigation", out.contains("psi:p.Solo#only"))
    }
}
