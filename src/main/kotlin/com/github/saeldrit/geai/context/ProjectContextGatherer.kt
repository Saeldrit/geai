package com.github.saeldrit.geai.context

import com.github.saeldrit.geai.tools.fs.FsPaths
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project

/** Builds a compact, cheap project snapshot to seed the system prompt. */
object ProjectContextGatherer {

    private val IGNORED = setOf(".git", ".idea", ".gradle", "build", "out", "target", "node_modules", ".geai")

    fun snapshot(project: Project): String = ReadAction.compute<String, RuntimeException> {
        val sb = StringBuilder()
        sb.appendLine("Name: ${project.name}")
        project.basePath?.let { sb.appendLine("Base path: $it") }
        sb.appendLine("Modules: ${ModuleManager.getInstance(project).modules.size}")

        FsPaths.projectRoot(project)?.let { root ->
            val names = root.children.map { it.name }.toSet()
            val build = when {
                names.any { it.startsWith("build.gradle") || it.startsWith("settings.gradle") } -> "Gradle"
                "pom.xml" in names -> "Maven"
                "package.json" in names -> "Node/npm"
                else -> "unknown"
            }
            sb.appendLine("Build system: $build")
            val topDirs = root.children
                .filter { it.isDirectory && it.name !in IGNORED }
                .map { it.name }
                .sorted()
            if (topDirs.isNotEmpty()) sb.appendLine("Top-level dirs: ${topDirs.take(25).joinToString(", ")}")
        }
        sb.toString().trimEnd()
    }
}
