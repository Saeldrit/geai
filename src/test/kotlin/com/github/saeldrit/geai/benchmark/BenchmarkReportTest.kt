package com.github.saeldrit.geai.benchmark

import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkReportTest {

    private fun result(config: String, billedIn: Int, grace: Int, file: Int) = RunResult(
        taskId = "tls-fix",
        config = config,
        model = "claude-sonnet-4-6",
        inputTokens = billedIn,
        outputTokens = 0,
        cacheReadTokens = 0,
        cacheWriteTokens = 0,
        costUsd = null,
        toolCounts = mapOf("context_bundle" to grace, "read_file" to file),
        iterations = 1,
        wallMs = 100,
        answerChars = 50,
        error = null,
    )

    @Test
    fun `markdown groups by task and renders a comparison row per config`() {
        val report = BenchmarkReport(listOf(result("baseline", 1000, 0, 5), result("grace", 600, 3, 1)), stampMs = 42L)
        val md = report.toMarkdown()
        assertTrue(md.contains("## Task: tls-fix"))
        assertTrue(md.contains("| baseline |"))
        assertTrue(md.contains("| grace |"))
        assertTrue("grace/file tool counts shown", md.contains("3/1"))
    }

    @Test
    fun `delta line reports the billed-token ratio and verdict`() {
        val report = BenchmarkReport(listOf(result("baseline", 1000, 0, 5), result("grace", 600, 3, 1)), stampMs = 1L)
        val md = report.toMarkdown()
        assertTrue("ratio 0.60 shown", md.contains("0.60×"))
        assertTrue("verdict when cheaper", md.contains("GRACE cheaper"))
    }
}
