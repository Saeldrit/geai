package com.github.saeldrit.geai.tools.fs

import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager

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
    private const val MAX_LINES_WITHOUT_RANGE = 400

    /**
     * Hard span cap that applies EVEN WHEN a range is given. A model that passes only `start_line`
     * (e.g. `start_line=1` to "start at the top") would otherwise disable the no-range cap and dump the
     * whole file — which then re-ships on every subsequent turn. A deliberate range up to this many
     * lines is honored; beyond it the read is truncated with a "read in smaller chunks" hint.
     */
    private const val MAX_LINES_WITH_RANGE = 800

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
            val requestedTo = (endLine ?: lines.size).coerceAtMost(lines.size)
            if (from > requestedTo) return@compute ToolResult.error("Invalid range: start_line ($from) > end_line ($requestedTo)")

            // The span cap applies whether or not an explicit range was given, so a lone start_line
            // can't bypass it. No-range reads are capped tighter (400) than deliberate ranges (800).
            val maxSpan = if (noRange) MAX_LINES_WITHOUT_RANGE else MAX_LINES_WITH_RANGE
            val capped = requestedTo - from + 1 > maxSpan
            val to = if (capped) from + maxSpan - 1 else requestedTo

            val body = (from..to).joinToString("\n") { i -> "$i\t${lines[i - 1]}" }
            val header = "// ${FsPaths.relativize(context.project, file)} (lines $from-$to of ${lines.size})"
            val note = if (capped) {
                "\n…[truncated to $maxSpan lines (requested $from-$requestedTo of ${lines.size}); read the next range in a follow-up call with start_line/end_line]"
            } else {
                ""
            }
            ToolResult.ok("$header\n$body$note")
        }
    }
}