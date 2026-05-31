package com.github.saeldrit.geai.benchmark

import com.github.saeldrit.geai.agent.AgentEvent
import com.github.saeldrit.geai.agent.AgentListener
import com.github.saeldrit.geai.agent.AgentLoop
import com.github.saeldrit.geai.agent.AgentSession
import com.github.saeldrit.geai.cost.Pricing
import com.github.saeldrit.geai.llm.TokenUsage
import com.github.saeldrit.geai.settings.GeaiSettings
import com.github.saeldrit.geai.settings.loopModel
import com.github.saeldrit.geai.tools.GeaiToolset
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Project-agnostic A/B harness: runs the SAME task list through the real agent loop under several
 * configs (baseline vs GRACE), capturing tokens/cost/tool-usage per run, and renders a Markdown
 * comparison. Works against any open [Project]; the automated test feeds it a fixed sample project.
 * Requires a configured provider + API key (it makes real LLM calls).
 */
object BenchmarkRunner {

    data class Task(val id: String, val prompt: String)
    data class Config(val name: String, val graceEnabled: Boolean)

    val DEFAULT_CONFIGS = listOf(Config("baseline", graceEnabled = false), Config("grace", graceEnabled = true))

    fun run(
        project: Project,
        tasks: List<Task>,
        stampMs: Long,
        configs: List<Config> = DEFAULT_CONFIGS,
        indicator: ProgressIndicator = EmptyProgressIndicator(),
    ): BenchmarkReport {
        val settings = GeaiSettings.getInstance().state
        val savedGrace = settings.graceEnabled
        val results = ArrayList<RunResult>()
        try {
            for (task in tasks) {
                for (cfg in configs) {
                    settings.graceEnabled = cfg.graceEnabled
                    results.add(runOne(project, task, cfg, settings.loopModel(), settings.modelPrices, indicator))
                }
            }
        } finally {
            settings.graceEnabled = savedGrace
        }
        return BenchmarkReport(results, stampMs)
    }

    private fun runOne(
        project: Project,
        task: Task,
        cfg: Config,
        model: String,
        priceTable: String?,
        indicator: ProgressIndicator,
    ): RunResult {
        val collector = Collector()
        val session = AgentSession()
        val start = System.currentTimeMillis()
        runCatching {
            AgentLoop(project, GeaiToolset.registry()).run(session, task.prompt, collector, indicator)
        }.onFailure { collector.error = it.message ?: it.javaClass.simpleName }
        val wall = System.currentTimeMillis() - start

        val usage = collector.usage
        val cost = Pricing.rateFor(Pricing.parse(priceTable), model)?.let { Pricing.costUsd(usage, it) }
        return RunResult(
            taskId = task.id,
            config = cfg.name,
            model = model,
            inputTokens = usage.inputTokens,
            outputTokens = usage.outputTokens,
            cacheReadTokens = usage.cacheReadTokens,
            cacheWriteTokens = usage.cacheWriteTokens,
            costUsd = cost,
            toolCounts = collector.toolCounts.toMap(),
            iterations = collector.iterations,
            wallMs = wall,
            answerChars = collector.answer.length,
            error = collector.error,
        )
    }

    /** Writes the report under `<project>/.geai/benchmark/run-<stamp>.md` and returns the path. */
    fun writeReport(project: Project, report: BenchmarkReport): Path {
        val base = project.basePath
        val dir = if (base != null) Paths.get(base, ".geai", "benchmark") else Paths.get(PathManager.getSystemPath(), "geai", project.locationHash, "benchmark")
        Files.createDirectories(dir)
        val file = dir.resolve("run-${report.stampMs}.md")
        Files.writeString(file, report.toMarkdown(), StandardCharsets.UTF_8)
        return file
    }

    /** Collects per-run metrics from the agent event stream. */
    private class Collector : AgentListener {
        val toolCounts = HashMap<String, Int>()
        var usage: TokenUsage = TokenUsage.ZERO
        var iterations = 0
        val answer = StringBuilder()
        var error: String? = null

        override fun onEvent(event: AgentEvent) {
            when (event) {
                is AgentEvent.ToolStarted -> toolCounts.merge(event.tool, 1, Int::plus)
                is AgentEvent.Thinking -> iterations++
                is AgentEvent.AssistantText -> answer.append(event.text)
                is AgentEvent.Done -> usage = event.usage
                is AgentEvent.Error -> error = event.text
                else -> Unit
            }
        }
    }
}
