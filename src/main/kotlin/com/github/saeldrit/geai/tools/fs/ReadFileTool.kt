package com.github.saeldrit.geai.tools.fs

import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager

/** Reads a text file and returns its content with 1-based line numbers. */
object ReadFileTool : AgentTool {
    override val name = "read_file"
    override val description =
        "Read a text file from the project. Returns content prefixed with 1-based line numbers. " +
            "Optionally restrict to an inclusive [start_line, end_line] range to save context."
    override val parametersJsonSchema = """
        {"type":"object","properties":{
          "path":{"type":"string","description":"Project-relative or absolute file path"},
          "start_line":{"type":"integer","description":"1-based first line (optional)"},
          "end_line":{"type":"integer","description":"1-based last line, inclusive (optional)"}
        },"required":["path"]}
    """.trimIndent()

    private const val MAX_BYTES = 2_000_000L
    // Whole-file read cap — kept tight because the dump lives in the transcript forever. The agent
    // is instructed to pass start_line/end_line for anything longer; a 150-line head is enough to
    // orient and decide what range to actually fetch.
    private const val MAX_LINES_WITHOUT_RANGE = 150

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val path = args.string("path")
        val startLine = args.intOrNull("start_line")
        val endLine = args.intOrNull("end_line")
        return ReadAction.compute<ToolResult, RuntimeException> {
            val file = FsPaths.resolve(context.project, path)
                ?: return@compute ToolResult.error("File not found: $path")
            if (file.isDirectory) return@compute ToolResult.error("Path is a directory, not a file: $path")
            if (file.length > MAX_BYTES) return@compute ToolResult.error("File too large to read (${file.length} bytes): $path")
            if (file.fileType.isBinary) return@compute ToolResult.error("Refusing to read binary file: $path")

            val document = FileDocumentManager.getInstance().getDocument(file)
                ?: return@compute ToolResult.error("Cannot load file content: $path")
            val lines = document.charsSequence.toString().split("\n")
            val noRange = startLine == null && endLine == null
            val from = (startLine ?: 1).coerceAtLeast(1)
            var to = (endLine ?: lines.size).coerceAtMost(lines.size)
            if (from > to) return@compute ToolResult.error("Invalid range: start_line ($from) > end_line ($to)")

            // A whole-file request on a long file is capped to a head so one read can't flood the
            // context; an explicit [start_line, end_line] range is always honoured verbatim.
            val capped = noRange && lines.size > MAX_LINES_WITHOUT_RANGE
            if (capped) to = MAX_LINES_WITHOUT_RANGE

            val body = (from..to).joinToString("\n") { i -> "$i\t${lines[i - 1]}" }
            val header = "// ${FsPaths.relativize(context.project, file)} (lines $from-$to of ${lines.size})"
            val note = if (capped) {
                "\n…[файл обрезан на $MAX_LINES_WITHOUT_RANGE из ${lines.size} строк; запроси конкретный диапазон через start_line/end_line]"
            } else {
                ""
            }
            ToolResult.ok("$header\n$body$note")
        }
    }
}
