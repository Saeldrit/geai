package com.github.saeldrit.geai.grace

import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult
import com.github.saeldrit.geai.tools.grace.ContextBundleTool
import com.github.saeldrit.geai.tools.grace.GraphQueryTool
import com.github.saeldrit.geai.tools.grace.ResolveRefTool
import com.github.saeldrit.geai.tools.grace.SpecRecordTool
import com.github.saeldrit.geai.tools.grace.SpecValidateTool
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class GraceChainTest : BasePlatformTestCase() {

    private fun run(tool: AgentTool, json: String): ToolResult =
        tool.execute(ToolArgs.parse(json), ToolContext(project, EmptyProgressIndicator()))

    fun testGraceChainResolvesGraphsAndBundles() {
        myFixture.addFileToProject(
            "src/com/app/GoalService.java",
            "package com.app;\npublic class GoalService {\n  public void create() {}\n}\n",
        )

        val resolved = run(ResolveRefTool, """{"ref":"psi:com.app.GoalService"}""")
        assertFalse("psi anchor should resolve: ${resolved.content}", resolved.isError)
        assertTrue("resolved content names the class", resolved.content.contains("GoalService"))

        val query = run(GraphQueryTool, """{"query":"GoalService"}""")
        assertFalse(query.isError)
        assertTrue("query finds the symbol: ${query.content}", query.content.contains("GoalService"))

        assertFalse(run(SpecRecordTool, """{"spec_id":"goals","kind":"POLICY","item_id":"additive","body":"API changes must be additive."}""").isError)
        val recordRef = run(SpecRecordTool, """{"spec_id":"goals","kind":"GOVERNED_BY","item_id":"svc","ref":"psi:com.app.GoalService#create"}""")
        assertFalse("recording a reference item should succeed: ${recordRef.content}", recordRef.isError)

        val validate = run(SpecValidateTool, """{"spec_id":"goals"}""")
        assertFalse("anchors resolve, so validation must not report broken: ${validate.content}", validate.content.contains("BROKEN"))

        val bundle = run(ContextBundleTool, """{"query":"change GoalService.create"}""")
        assertFalse("bundle should build: ${bundle.content}", bundle.isError)
        assertTrue("bundle is non-trivial", bundle.content.length > 20)
    }
}
