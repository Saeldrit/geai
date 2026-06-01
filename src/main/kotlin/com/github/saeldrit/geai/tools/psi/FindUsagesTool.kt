package com.github.saeldrit.geai.tools.psi

import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch

/**
 * Find every reference to a JVM symbol (who reads / writes / calls it) through the IDE's reference
 * search — the semantic alternative to grepping a name, and the right tool for tracing data flow
 * ("where is X used, where does it reach the response"). Takes a `psi:` anchor (from find_symbol or
 * resolve_ref) and returns each usage's file:line, enclosing method/class, and the source line.
 */
object FindUsagesTool : AgentTool {
    override val name = "find_usages"
    override val description =
        "Find all references to a JVM symbol (who reads/writes/calls it) — the semantic alternative to " +
            "grepping a name, and the tool for tracing a value's flow. Pass a psi: anchor " +
            "(psi:<fqClass>[#member]); get it from find_symbol or resolve_ref. Returns each usage's " +
            "file:line, enclosing method/class, and the line."
    override val parametersJsonSchema = """
        {"type":"object","properties":{
          "ref":{"type":"string","description":"psi anchor of the symbol: psi:<fqClass>[#member], e.g. psi:io.saylo.app.FoodItem#micronutrients"}
        },"required":["ref"]}
    """.trimIndent()

    private const val MAX = 100

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val raw = args.string("ref").trim().removePrefix("psi:").trim()
        if (raw.isEmpty()) return ToolResult.error("find_usages needs a 'ref' (psi:<fqClass>[#member]).")
        val hash = raw.indexOf('#')
        val fqName = (if (hash >= 0) raw.substring(0, hash) else raw).trim()
        val member = if (hash >= 0) raw.substring(hash + 1).trim().ifBlank { null } else null
        val project = context.project
        if (DumbService.isDumb(project)) return ToolResult.error("IDE is still indexing — retry shortly.")

        return ReadAction.compute<ToolResult, RuntimeException> {
            try {
                val target = JvmSymbols.resolve(project, fqName, member)
                    ?: return@compute ToolResult.error("Symbol '$raw' not found. Use find_symbol to locate the exact name first.")

                val refs = ReferencesSearch.search(target, GlobalSearchScope.projectScope(project)).findAll()
                if (refs.isEmpty()) {
                    return@compute ToolResult.ok("No usages of $raw found in project sources.")
                }
                val rows = refs.asSequence().mapNotNull { ref ->
                    val element = ref.element
                    val loc = JvmSymbols.locationOf(element) ?: return@mapNotNull null
                    val where = JvmSymbols.enclosingName(element)?.let { " in $it" } ?: ""
                    "$loc$where  —  ${JvmSymbols.lineSnippet(element)}"
                }.distinct().take(MAX).toList()

                val capped = refs.size > rows.size
                ToolResult.ok(
                    "${refs.size} usage(s) of $raw${if (capped) " (showing ${rows.size})" else ""}:\n" +
                        rows.joinToString("\n"),
                )
            } catch (e: JvmSymbols.JavaUnavailable) {
                ToolResult.error(e.message ?: "Java PSI unavailable.")
            }
        }
    }
}
