package com.github.saeldrit.geai.tools.psi

import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult
import com.github.saeldrit.geai.tools.fs.FsPaths
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil

/**
 * Problems the IDE sees in a file WITHOUT running a build: syntax/parse errors (always, straight from
 * PSI) plus the analyzer's errors & warnings for files the IDE has already analyzed. Fast post-edit
 * feedback. NOT authoritative for compilation — the analyzer pass only covers files the IDE has
 * opened/analyzed; for a definitive answer the agent should build via the `run` group (run_command).
 */
object DiagnosticsTool : AgentTool {
    override val name = "diagnostics"
    override val description =
        "Show problems the IDE sees in a file WITHOUT running a build: syntax errors (always) plus the " +
            "analyzer's errors & warnings when the file has been analyzed. Fast post-edit check — NOT a " +
            "substitute for a build; for authoritative compile errors, build via run_command."
    override val parametersJsonSchema = """
        {"type":"object","properties":{
          "path":{"type":"string","description":"Project-relative or absolute file path to check"}
        },"required":["path"]}
    """.trimIndent()

    private const val MAX = 100
    private const val NOTE =
        "\n(syntax is always checked; analyzer findings appear only for files the IDE has analyzed — " +
            "for an authoritative compile, build via run_command)"

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val path = args.string("path")
        val project = context.project
        return ReadAction.compute<ToolResult, RuntimeException> {
            val file = FsPaths.resolve(project, path) ?: return@compute ToolResult.error("File not found: $path")
            if (file.isDirectory) return@compute ToolResult.error("Path is a directory: $path")
            val psiFile = PsiManager.getInstance(project).findFile(file)
                ?: return@compute ToolResult.error("Cannot load PSI for $path")
            val document = FileDocumentManager.getInstance().getDocument(file)

            val rows = ArrayList<String>()
            // Syntax/parse errors — always reliable, no analysis pass needed.
            PsiTreeUtil.collectElementsOfType(psiFile, PsiErrorElement::class.java).forEach { err ->
                val line = document?.getLineNumber(err.textOffset)?.plus(1) ?: 0
                rows.add("error    line $line  syntax: ${err.errorDescription}")
            }
            // Analyzer errors/warnings — present only for files the IDE has already analyzed.
            if (document != null) {
                runCatching {
                    DaemonCodeAnalyzerImpl.getHighlights(document, HighlightSeverity.WARNING, project).forEach { info ->
                        val line = document.getLineNumber(info.startOffset) + 1
                        rows.add("${info.severity.toString().lowercase()}  line $line  ${(info.description ?: "").trim().take(160)}")
                    }
                }
            }

            val header = "${FsPaths.relativize(project, file)}: ${rows.size} problem(s)"
            if (rows.isEmpty()) {
                ToolResult.ok("$header — none detected.$NOTE")
            } else {
                ToolResult.ok("$header:\n" + rows.take(MAX).joinToString("\n") + NOTE)
            }
        }
    }
}
