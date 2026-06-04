package com.github.saeldrit.geai.tools.fs

import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext

/** Bounded full-text search across project content (substring or regex). */
object SearchTextTool : AgentTool {
    override val name = "search_text"
    override val description =
        "Search file contents across the project for a substring (default, case-insensitive) or a " +
            "regular expression. Returns 'path:line: snippet' rows, capped. Excluded/build folders are skipped."
    override val parametersJsonSchema = """
        {"type":"object","properties":{
          "query":{"type":"string","description":"Substring or regex to find"},
          "regex":{"type":"boolean","description":"Treat query as a regex (default false)"},
          "file_glob":{"type":"string","description":"Limit to files matching a glob, e.g. *.kt"},
          "max_results":{"type":"integer","description":"Cap on matches (default 30, max 1000). Narrow with file_glob/regex before raising."}
        },"required":["query"]}
    """.trimIndent()

    private const val MAX_BYTES = 1_000_000L
    private const val MAX_FILES = 20_000
    private const val MAX_LINE = 240

    /** Longest run of word chars; the trigram/word index needs >=3 chars to narrow soundly. */
    private val INDEXABLE_WORD = Regex("""\w{3,}""")

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val query = args.string("query")
        val isRegex = args.boolean("regex", false)
        val glob = args.stringOrNull("file_glob")
        // Default kept low: a 100-row table is ~24KB and lives in the transcript forever. If the
        // model needs more, it can ask explicitly — but usually it should narrow file_glob/regex first.
        val maxResults = args.int("max_results", 30).coerceIn(1, 1000)

        val regex = if (isRegex) {
            runCatching { Regex(query) }.getOrElse { return ToolResult.error("Invalid regex: ${it.message}") }
        } else {
            null
        }
        val nameRegex = glob?.let { Globs.toRegex(it) }

        // Index-backed narrowing: for a plain substring with an indexable (>=3 char) word, ask the IDE's
        // word/trigram index which files could contain that word — milliseconds, instead of reading EVERY
        // file. The substring necessarily contains the word, so the candidate set is a sound superset.
        // Regex, word-less queries, and dumb mode fall back to the bounded full scan (unchanged, correct).
        val anchorWord = if (isRegex) null else INDEXABLE_WORD.findAll(query).maxByOrNull { it.value.length }?.value

        return ReadAction.compute<ToolResult, RuntimeException> {
            val matches = ArrayList<String>()
            var scanned = 0

            fun scan(file: VirtualFile): Boolean {
                when {
                    context.indicator.isCanceled -> return false
                    file.isDirectory -> return true
                    nameRegex != null && !nameRegex.matches(file.name) -> return true
                    file.length > MAX_BYTES || file.fileType.isBinary -> return true
                    scanned >= MAX_FILES -> return false
                }
                scanned++
                val text = readTextOrNull(file) ?: return true
                val relative = FsPaths.relativize(context.project, file)
                for ((index, line) in text.split("\n").withIndex()) {
                    val hit = if (regex != null) regex.containsMatchIn(line) else line.contains(query, ignoreCase = true)
                    if (hit) {
                        matches.add("$relative:${index + 1}: ${line.trim().take(MAX_LINE)}")
                        if (matches.size >= maxResults) break
                    }
                }
                return matches.size < maxResults
            }

            val usedIndex = anchorWord != null && !DumbService.isDumb(context.project)
            if (usedIndex) {
                val candidates = ArrayList<VirtualFile>()
                PsiSearchHelper.getInstance(context.project).processCandidateFilesForText(
                    GlobalSearchScope.projectScope(context.project),
                    UsageSearchContext.ANY,
                    false, // case-insensitive candidate match; the line scan re-confirms
                    anchorWord!!,
                ) { vf -> candidates.add(vf); true }
                for (file in candidates) if (!scan(file)) break
            } else {
                ProjectFileIndex.getInstance(context.project).iterateContent { scan(it) }
            }
            if (context.indicator.isCanceled) throw ProcessCanceledException()

            val needle = if (isRegex) "/$query/" else "\"$query\""
            val via = if (usedIndex) "" else " (scanned $scanned files)"
            if (matches.isEmpty()) {
                ToolResult.ok("No matches for $needle${glob?.let { " in $it" } ?: ""}$via.")
            } else {
                val capped = if (matches.size >= maxResults) " (capped at $maxResults)" else ""
                ToolResult.ok("${matches.size} match(es)$capped for $needle:\n${matches.joinToString("\n")}")
            }
        }
    }
}
