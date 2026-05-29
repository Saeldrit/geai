package com.github.saeldrit.geai.tools.fs

import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.roots.ProjectFileIndex

/** Finds files by name (glob or substring). */
object FindFilesTool : AgentTool {
    override val name = "find_files"
    override val description =
        "Find files whose name matches a glob (e.g. *.kt, *Service*.java) or a plain substring. " +
            "Build/VCS folders are skipped. Use this to locate where something lives before reading it."
    override val parametersJsonSchema = """
        {"type":"object","properties":{
          "name":{"type":"string","description":"Glob (*, ?) or substring to match against file names"},
          "max_results":{"type":"integer","description":"Cap on results (default 100)"}
        },"required":["name"]}
    """.trimIndent()

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val pattern = args.string("name")
        val maxResults = args.int("max_results", 100).coerceIn(1, 1000)
        val regex = if (pattern.contains('*') || pattern.contains('?')) Globs.toRegex(pattern) else null

        return ReadAction.compute<ToolResult, RuntimeException> {
            val found = ArrayList<String>()
            ProjectFileIndex.getInstance(context.project).iterateContent { file ->
                when {
                    context.indicator.isCanceled -> return@iterateContent false
                    file.isDirectory -> return@iterateContent true
                }
                val matches = regex?.matches(file.name) ?: file.name.contains(pattern, ignoreCase = true)
                if (matches) {
                    found.add(FsPaths.relativize(context.project, file))
                    if (found.size >= maxResults) return@iterateContent false
                }
                true
            }
            if (context.indicator.isCanceled) throw ProcessCanceledException()

            if (found.isEmpty()) {
                ToolResult.ok("No files matching '$pattern'.")
            } else {
                val capped = if (found.size >= maxResults) " (capped at $maxResults)" else ""
                ToolResult.ok("${found.size} file(s)$capped:\n${found.sorted().joinToString("\n")}")
            }
        }
    }
}
