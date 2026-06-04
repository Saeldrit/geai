package com.github.saeldrit.geai.tools.grace

import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** graph_query now finds classes live from IntelliJ's index — no graph build, always fresh. */
class GraphQueryToolTest : BasePlatformTestCase() {

    private fun query(json: String): String =
        GraphQueryTool.execute(ToolArgs.parse(json), ToolContext(project, EmptyProgressIndicator())).content

    fun testFindsClassByNameSubstringWithNoGraphBuild() {
        myFixture.addFileToProject("p/GoalService.java", "package p;\npublic class GoalService {}")
        myFixture.addFileToProject("p/Other.java", "package p;\npublic class Other {}")
        val out = query("""{"query":"GoalService"}""")
        assertTrue("finds the class as a psi: id, live (no reindex)", out.contains("psi:p.GoalService"))
        assertFalse("substring narrows — unrelated class excluded", out.contains("psi:p.Other"))
    }

    fun testSymbolKindFiltersToClasses() {
        myFixture.addFileToProject("p/Widget.java", "package p;\npublic class Widget {}")
        val out = query("""{"kind":"SYMBOL","query":"Widget"}""")
        assertTrue(out.contains("psi:p.Widget"))
    }

    fun testNoMatchGuidesToOtherTools() {
        myFixture.addFileToProject("p/Z.java", "package p;\npublic class Z {}")
        val out = query("""{"query":"NoSuchSymbolXyz"}""")
        assertTrue(out.contains("No nodes matched", ignoreCase = true))
    }
}
