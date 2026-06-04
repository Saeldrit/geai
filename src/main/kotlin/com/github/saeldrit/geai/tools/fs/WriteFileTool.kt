package com.github.saeldrit.geai.tools.fs

import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.util.concurrent.atomic.AtomicReference

/** Creates or overwrites a project file (undoable write command). */
object WriteFileTool : AgentTool {
    override val name = "write_file"
    override val mutating = true
    override val description =
        "Create a new file or fully overwrite an existing one. Parent directories are created as " +
            "needed. New files must be project-relative. For surgical changes prefer edit_file."
    override val parametersJsonSchema = """
        {"type":"object","properties":{
          "path":{"type":"string","description":"Project-relative path (or an existing absolute path to overwrite)"},
          "content":{"type":"string","description":"Full file content to write"}
        },"required":["path","content"]}
    """.trimIndent()

    private val ABSOLUTE = Regex("^([a-zA-Z]:[\\\\/]|/).*")

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val path = args.string("path")
        val content = args.string("content")
        val project = context.project
        val resultRef = AtomicReference<ToolResult>()

        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                resultRef.set(
                    runCatching {
                        val file = createOrFind(project, path)
                            ?: return@runCatching ToolResult.error(
                                "Cannot create '$path': use a project-relative path with an existing or creatable parent.",
                            )
                        VfsUtil.saveText(file, content)
                        ToolResult.ok("Wrote ${content.length} chars to ${FsPaths.relativize(project, file)}")
                    }.getOrElse { ToolResult.error("Write failed: ${it.message}") },
                )
            }
        }
        return resultRef.get() ?: ToolResult.error("Write produced no result")
    }

    private fun createOrFind(project: Project, path: String): VirtualFile? {
        // Overwrite an existing file only when it lives inside the project — an absolute path that
        // happens to exist elsewhere on disk (e.g. a system file) must never be clobbered.
        FsPaths.resolve(project, path)?.let { return it.takeIf { f -> FsPaths.isInsideProject(project, f) } }
        if (ABSOLUTE.matches(path.trim())) return null // don't create new files outside the project
        val base = project.basePath ?: return null
        val baseDir = LocalFileSystem.getInstance().findFileByPath(base) ?: return null
        val normalized = path.trim().replace('\\', '/').trimStart('/')
        val fileName = normalized.substringAfterLast('/')
        if (fileName.isBlank()) return null
        val dirPart = normalized.substringBeforeLast('/', "")
        val dir = if (dirPart.isEmpty()) baseDir else VfsUtil.createDirectoryIfMissing(baseDir, dirPart) ?: return null
        return dir.findChild(fileName) ?: dir.createChildData(this, fileName)
    }
}
