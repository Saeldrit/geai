package com.github.saeldrit.geai.tools.fs

import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Exercises [SearchTextTool] against a real (light) project + index, covering the index-backed
 * substring path, the regex full-scan fallback, and the no-match case.
 */
class SearchTextToolTest : BasePlatformTestCase() {

    private fun search(json: String): String =
        SearchTextTool.execute(ToolArgs.parse(json), ToolContext(project, EmptyProgressIndicator())).content

    fun testIndexBackedSubstringFindsOnlyTheRightFile() {
        myFixture.addFileToProject("src/Alpha.java", "class Alpha { void uniqueMethodName() {} }")
        myFixture.addFileToProject("src/Beta.java", "class Beta { void other() {} }")
        val out = search("""{"query":"uniqueMethodName"}""")
        assertTrue("finds the match with file:line", out.contains("Alpha.java") && out.contains("uniqueMethodName"))
        assertFalse("the index narrowed — unrelated file is not even scanned", out.contains("Beta.java"))
    }

    fun testCaseInsensitiveSubstring() {
        myFixture.addFileToProject("src/Gamma.java", "class Gamma { /* HANDLER registered */ }")
        val out = search("""{"query":"handler registered"}""")
        assertTrue("case-insensitive substring still matches", out.contains("Gamma.java"))
    }

    fun testRegexFallbackFindsMatch() {
        myFixture.addFileToProject("src/Delta.java", "int x = 0;\nlong yy = 1;")
        val out = search("""{"query":"\\w+\\s+\\w+\\s*=","regex":true}""")
        assertTrue("regex path (full scan) finds the assignment", out.contains("Delta.java"))
    }

    fun testNoMatchReportsCleanly() {
        myFixture.addFileToProject("src/Eps.java", "class Eps {}")
        val out = search("""{"query":"NONEXISTENT_TOKEN_XYZ"}""")
        assertTrue("reports no matches", out.contains("No matches", ignoreCase = true))
    }
}
