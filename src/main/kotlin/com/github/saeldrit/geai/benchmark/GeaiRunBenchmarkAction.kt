package com.github.saeldrit.geai.benchmark

import com.github.saeldrit.geai.llm.LlmClientFactory
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

/**
 * Tools-menu action: runs the GRACE A/B benchmark (baseline vs GRACE) on the CURRENT project for a
 * task the user types, then writes the Markdown report to `.geai/benchmark/`. Makes real LLM calls,
 * so it requires a configured provider + key (e.g. OpenRouter).
 */
class GeaiRunBenchmarkAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (!LlmClientFactory.isConfigured()) {
            Messages.showWarningDialog(project, "Configure a provider and API key first (Settings | Tools | Geai).", "Geai Benchmark")
            return
        }
        val prompt = Messages.showMultilineInputDialog(
            project,
            "Task to run under baseline vs GRACE (same task, two configs):",
            "Geai A/B Benchmark",
            DEFAULT_TASK,
            null,
            null,
        )?.takeIf { it.isNotBlank() } ?: return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Geai A/B benchmark", true) {
            override fun run(indicator: ProgressIndicator) {
                val report = BenchmarkRunner.run(
                    project = project,
                    tasks = listOf(BenchmarkRunner.Task("task", prompt)),
                    stampMs = System.currentTimeMillis(),
                    indicator = indicator,
                )
                val path = BenchmarkRunner.writeReport(project, report)
                ApplicationManager.getApplication().invokeLater {
                    Messages.showInfoMessage(project, "Report written:\n$path\n\n${report.toMarkdown().take(1500)}", "Geai Benchmark Done")
                }
            }
        })
    }

    private companion object {
        const val DEFAULT_TASK =
            "Найди, где в проекте конфигурируется HTTP/сетевой клиент, и предложи фикс, если настройки " +
                "небезопасны (таймауты, проверка сертификата). Только диагноз + минимальная правка."
    }
}
