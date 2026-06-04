package com.github.saeldrit.geai.bundle

import com.github.saeldrit.geai.spec.SpecItemKind
import com.github.saeldrit.geai.spec.SpecStore
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The context bundle is now sourced from live PSI (seeds + neighbourhood) instead of a materialized
 * graph. These lock in the quality contract — a seed resolves to live content and its governing rule
 * is included — and that it works with NO graph build (cold project).
 */
class ContextBundlerTest : BasePlatformTestCase() {

    fun testBundleResolvesSeedLiveAndIncludesGoverningRule() {
        myFixture.addFileToProject(
            "com/app/GoalService.java",
            "package com.app;\npublic class GoalService { public void create() {} }",
        )
        val specs = SpecStore.getInstance(project)
        specs.upsertItem("goals", "Goals", null, SpecItemKind.POLICY, "additive", emptyList(), null, "API changes must be additive.")
        specs.upsertItem("goals", "Goals", null, SpecItemKind.GOVERNED_BY, "svc", emptyList(), "psi:com.app.GoalService", "")

        val bundle = ContextBundler.build(project, "GoalService", emptyList(), maxNodes = 24, hops = 2)

        assertFalse("no stale 'graph empty' message", bundle.text.contains("Graph is empty"))
        assertTrue("seed class resolved live into the bundle", bundle.text.contains("GoalService"))
        assertTrue("governing rule pulled in via GOVERNED_BY", bundle.text.contains("additive"))
        assertTrue("at least one node", bundle.nodeIds.isNotEmpty())
    }

    fun testBundleWorksWithNoGraphBuild() {
        myFixture.addFileToProject(
            "com/app/Widget.java",
            "package com.app;\npublic class Widget { public void render() {} }",
        )
        // No graph_reindex anywhere — PSI is the source.
        val bundle = ContextBundler.build(project, "Widget", emptyList(), maxNodes = 24, hops = 2)
        assertFalse(bundle.text.contains("Graph is empty"))
        assertTrue("bundle built around the live symbol", bundle.text.contains("Widget"))
    }
}
