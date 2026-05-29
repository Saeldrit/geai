package com.github.saeldrit.geai.tools.fs

import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.VirtualFile
import java.util.ArrayDeque

/** Lists directory entries, optionally recursively, skipping build/VCS noise. */
object ListFilesTool : AgentTool {
    override val name = "list_files"
    override val description =
        "List entries of a project directory. Defaults to the project root and one level deep. " +
            "Set recursive=true for a bounded recursive walk. Build/VCS folders are skipped."
    override val parametersJsonSchema = """
        {"type":"object","properties":{
          "path":{"type":"string","description":"Directory path (project-relative or absolute). Defaults to project root."},
          "recursive":{"type":"boolean","description":"Recurse into subdirectories (default false)"},
          "max_results":{"type":"integer","description":"Cap on entries returned (default 200)"}
        }}
    """.trimIndent()

    private val IGNORED = setOf(
        ".git", ".idea", ".gradle", "build", "out", "target", "dist",
        "node_modules", ".geai", ".kotlin", "bin",
    )

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val path = args.stringOrNull("path")
        val recursive = args.boolean("recursive", false)
        val maxResults = args.int("max_results", 200).coerceIn(1, 2000)

        return ReadAction.compute<ToolResult, RuntimeException> {
            val root = if (path.isNullOrBlank()) {
                FsPaths.projectRoot(context.project)
            } else {
                FsPaths.resolve(context.project, path)
            } ?: return@compute ToolResult.error("Directory not found: ${path ?: "<project root>"}")
            if (!root.isDirectory) return@compute ToolResult.error("Not a directory: ${root.path}")

            val entries = ArrayList<String>()
            if (recursive) {
                val queue = ArrayDeque<VirtualFile>()
                queue.add(root)
                while (queue.isNotEmpty() && entries.size < maxResults) {
                    val dir = queue.poll()
                    for (child in dir.children.sortedBy { it.name }) {
                        if (child.name in IGNORED) continue
                        entries.add(describe(context.project, child))
                        if (entries.size >= maxResults) break
                        if (child.isDirectory) queue.add(child)
                    }
                }
            } else {
                for (child in root.children.sortedWith(compareBy({ !it.isDirectory }, { it.name }))) {
                    if (child.name in IGNORED) continue
                    entries.add(describe(context.project, child))
                    if (entries.size >= maxResults) break
                }
            }
            val header = "// ${FsPaths.relativize(context.project, root)}/ (${entries.size} entries)"
            ToolResult.ok((listOf(header) + entries).joinToString("\n"))
        }
    }

    private fun describe(project: com.intellij.openapi.project.Project, file: VirtualFile): String {
        val rel = FsPaths.relativize(project, file)
        return if (file.isDirectory) "$rel/" else "$rel (${file.length} bytes)"
    }
}
