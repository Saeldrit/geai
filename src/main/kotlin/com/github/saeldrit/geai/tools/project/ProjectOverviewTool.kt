package com.github.saeldrit.geai.tools.project

import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult
import com.github.saeldrit.geai.tools.fs.FsPaths
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.ProjectRootManager

/** A quick orientation map of the project. The agent should call this first on a fresh task. */
object ProjectOverviewTool : AgentTool {
    override val name = "project_overview"
    override val description =
        "High-level map of the project: name, base path, modules, source roots, and top-level entries. " +
            "Call this first to orient before searching or reading files."
    override val parametersJsonSchema = """{"type":"object","properties":{}}"""

    private val IGNORED = setOf(".git", ".idea", ".gradle", "build", "out", "target", "node_modules", ".geai")

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val project = context.project
        return ReadAction.compute<ToolResult, RuntimeException> {
            val sb = StringBuilder()
            sb.appendLine("Project: ${project.name}")
            project.basePath?.let { sb.appendLine("Base path: $it") }

            val modules = ModuleManager.getInstance(project).modules
            sb.appendLine("Modules (${modules.size}): ${modules.take(30).joinToString(", ") { it.name }}")

            val sourceRoots = ProjectRootManager.getInstance(project).contentSourceRoots
            if (sourceRoots.isNotEmpty()) {
                sb.appendLine(
                    "Source roots (${sourceRoots.size}): " +
                        sourceRoots.take(20).joinToString(", ") { FsPaths.relativize(project, it) },
                )
            }

            FsPaths.projectRoot(project)?.let { dir ->
                val top = dir.children
                    .filter { it.name !in IGNORED }
                    .sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                    .take(40)
                sb.appendLine("Top-level entries:")
                top.forEach { sb.appendLine("  ${it.name}${if (it.isDirectory) "/" else ""}") }
            }
            ToolResult.ok(sb.toString().trimEnd())
        }
    }
}
