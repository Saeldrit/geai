package com.github.saeldrit.geai.benchmark

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/** Tools-menu entry for the A/B benchmark; delegates to [BenchmarkLauncher] (same as the UI button). */
class GeaiRunBenchmarkAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        BenchmarkLauncher.launch(project)
    }
}
