package com.github.saeldrit.geai.benchmark

import com.github.saeldrit.geai.settings.GeaiSecrets
import com.github.saeldrit.geai.settings.GeaiSettings
import com.github.saeldrit.geai.settings.LlmProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.relativeTo

class BenchmarkRunTest : HeavyPlatformTestCase() {

    fun testBenchmarkBaselineVsGrace() {
        val apiKey = System.getenv("GEAI_BENCH_KEY")
        if (apiKey.isNullOrBlank()) {
            println("[benchmark] GEAI_BENCH_KEY not set — skipping live benchmark.")
            return
        }

        copySampleIntoProject()
        configure(apiKey)

        val tasks = listOf(
            BenchmarkRunner.Task(
                "tls-fix",
                "Найди, где в проекте конфигурируется HTTP/TLS клиент, и предложи фикс, если настройки " +
                    "небезопасны (таймауты, проверка сертификата). Только диагноз + минимальная правка.",
            ),
        )

        val report = BenchmarkRunner.run(project, tasks, stampMs = System.currentTimeMillis())
        val out = repoResultsDir().resolve("run-${report.stampMs}.md")
        Files.createDirectories(out.parent)
        Files.writeString(out, report.toMarkdown())
        println("[benchmark] report written: $out")
        println(report.toMarkdown())
    }

    private fun configure(apiKey: String) {
        val state = GeaiSettings.getInstance().state
        val provider = System.getenv("GEAI_BENCH_PROVIDER")
            ?.let { runCatching { LlmProvider.valueOf(it.trim().uppercase()) }.getOrNull() }
            ?: LlmProvider.ANTHROPIC
        state.provider = provider
        state.model = System.getenv("GEAI_BENCH_MODEL")?.takeIf { it.isNotBlank() } ?: provider.defaultModel
        state.baseUrl = System.getenv("GEAI_BENCH_BASEURL")?.takeIf { it.isNotBlank() }
        state.autoApproveEditTools = true
        state.autoApproveReadTools = true
        state.modelPrices = System.getenv("GEAI_BENCH_PRICES")?.takeIf { it.isNotBlank() }
        GeaiSecrets.setApiKey(provider, apiKey)
    }

    private fun copySampleIntoProject() {
        val sample = Paths.get(System.getProperty("user.dir"), "benchmark", "fixtures", "sample")
        val baseDir = project.basePath?.let { Paths.get(it) } ?: error("project has no base path")
        Files.walk(sample).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { src ->
                val target = baseDir.resolve(src.relativeTo(sample).toString())
                Files.createDirectories(target.parent)
                Files.copy(src, target)
            }
        }
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                val vBase = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(baseDir)
                vBase?.let { VfsUtil.markDirtyAndRefresh(false, true, true, it) }
            }
        }
    }

    private fun repoResultsDir(): Path = Paths.get(System.getProperty("user.dir"), "benchmark", "results")
}
