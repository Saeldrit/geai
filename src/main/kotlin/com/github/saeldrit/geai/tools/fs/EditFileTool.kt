package com.github.saeldrit.geai.tools.fs

import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import java.util.concurrent.atomic.AtomicReference

/** Exact-substring replacement in a file (undoable). Mirrors the discipline of the host IDE's edit. */
object EditFileTool : AgentTool {
    override val name = "edit_file"
    override val mutating = true
    override val description =
        "Replace an exact substring in a file. 'old_string' must match exactly (including whitespace) " +
            "and be unique unless replace_all=true. Fails clearly if not found or ambiguous."
    override val parametersJsonSchema = """
        {"type":"object","properties":{
          "path":{"type":"string","description":"File to edit (project-relative or absolute)"},
          "old_string":{"type":"string","description":"Exact text to replace (non-empty)"},
          "new_string":{"type":"string","description":"Replacement text"},
          "replace_all":{"type":"boolean","description":"Replace every occurrence (default false)"}
        },"required":["path","old_string","new_string"]}
    """.trimIndent()

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val path = args.string("path")
        val oldString = args.string("old_string")
        val newString = args.string("new_string")
        val replaceAll = args.boolean("replace_all", false)
        val project = context.project
        val resultRef = AtomicReference<ToolResult>()

        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                resultRef.set(applyEdit(context, path, oldString, newString, replaceAll))
            }
        }
        return resultRef.get() ?: ToolResult.error("Edit produced no result")
    }

    private fun applyEdit(
        context: ToolContext,
        path: String,
        oldString: String,
        newString: String,
        replaceAll: Boolean,
    ): ToolResult {
        if (oldString.isEmpty()) return ToolResult.error("'old_string' must be non-empty")
        val file = FsPaths.resolve(context.project, path) ?: return ToolResult.error("File not found: $path")
        if (file.isDirectory) return ToolResult.error("Path is a directory: $path")
        val document = FileDocumentManager.getInstance().getDocument(file)
            ?: return ToolResult.error("Cannot load file content: $path")

        val text = document.charsSequence.toString()
        val occurrences = countOccurrences(text, oldString)
        return when {
            occurrences == 0 -> ToolResult.error("'old_string' not found in $path")
            occurrences > 1 && !replaceAll ->
                ToolResult.error("'old_string' matches $occurrences times in $path — add surrounding context or set replace_all=true")
            else -> {
                val updated = if (replaceAll) text.replace(oldString, newString) else text.replaceFirst(oldString, newString)
                document.setText(updated)
                FileDocumentManager.getInstance().saveDocument(document)
                val changed = if (replaceAll) occurrences else 1
                ToolResult.ok("Edited ${FsPaths.relativize(context.project, file)} ($changed replacement(s))")
            }
        }
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }
}
