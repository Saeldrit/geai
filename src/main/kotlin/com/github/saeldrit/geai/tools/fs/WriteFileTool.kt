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

object WriteFileTool : AgentTool {
    override val name = "write_file"
    override val mutating = true
    override val description =
        "Create/overwrite a file, or APPEND to one. Parent directories are created as needed. New " +
            "files must be project-relative. For a LARGE deliverable, write a skeleton first with " +
            "mode=overwrite, then grow it section by section with mode=append — each call persists, " +
            "so partial progress survives an interruption and no single call is huge. For surgical " +
            "changes to existing code prefer edit_file."
    override val parametersJsonSchema = """
        {"type":"object","properties":{
          "path":{"type":"string","description":"Project-relative path (or an existing absolute path to overwrite)"},
          "content":{"type":"string","description":"File content to write (full file for overwrite, or the chunk to add for append)"},
          "mode":{"type":"string","enum":["overwrite","append"],"description":"overwrite (default) replaces the whole file; append adds content to the end (creating the file if absent)"}
        },"required":["path","content"]}
    """.trimIndent()

    private val ABSOLUTE = Regex("^([a-zA-Z]:[\\\\/]|/).*")

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val path = args.string("path")
        val content = args.string("content")
        val append = args.stringOrNull("mode")?.equals("append", ignoreCase = true) == true
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
                        // Append preserves what is already on disk — the load-bearing half of
                        // incremental authoring: a big file is grown in bounded chunks, each durable.
                        val finalText = if (append && file.length > 0) VfsUtil.loadText(file) + content else content
                        VfsUtil.saveText(file, finalText)
                        val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file)
                        val syntax = PostEditCheck.syntaxWarning(project, file, document)
                        val verb = if (append) "Appended ${content.length} chars to" else "Wrote ${content.length} chars to"
                        ToolResult.ok("$verb ${FsPaths.relativize(project, file)} (now ${finalText.length} chars)$syntax")
                    }.getOrElse { ToolResult.error("Write failed: ${it.message}") },
                )
            }
        }
        return resultRef.get() ?: ToolResult.error("Write produced no result")
    }

    private fun createOrFind(project: Project, path: String): VirtualFile? {
        FsPaths.resolve(project, path)?.let { return it.takeIf { f -> FsPaths.isInsideProject(project, f) } }
        if (ABSOLUTE.matches(path.trim())) return null
        val base = project.basePath ?: return null
        val baseDir = LocalFileSystem.getInstance().findFileByPath(base) ?: return null
        val normalized = path.trim().replace('\\', '/').trimStart('/')
        val fileName = normalized.substringAfterLast('/')
        val dirPath = normalized.substringBeforeLast('/', "")
        val dir = if (dirPath.isEmpty()) baseDir else VfsUtil.createDirectories("$base/$dirPath")
        return dir.findChild(fileName) ?: dir.createChildData(this, fileName)
    }
}