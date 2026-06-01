package com.github.saeldrit.geai.tools.psi

import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.OverridingMethodsSearch

/**
 * Walk a JVM type/method hierarchy through the IDE — the semantic alternative to grepping "implements X"
 * or "@Override". A class/interface anchor returns its subclasses & implementors; a method anchor
 * returns the methods that override it. Take the `psi:` anchor from find_symbol or resolve_ref.
 */
object FindImplementationsTool : AgentTool {
    override val name = "find_implementations"
    override val description =
        "Find implementations/subclasses of a type, or overrides of a method, via the IDE's hierarchy " +
            "search — instead of grepping 'implements X' / '@Override'. Pass a psi: anchor: a class or " +
            "interface (psi:<fqClass>) → its subclasses & implementors; a method (psi:<fqClass>#method) → " +
            "the methods that override it. Returns each result's name and file:line."
    override val parametersJsonSchema = """
        {"type":"object","properties":{
          "ref":{"type":"string","description":"psi anchor: psi:<fqClass> for a type, psi:<fqClass>#method for a method"}
        },"required":["ref"]}
    """.trimIndent()

    private const val MAX = 100

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val raw = args.string("ref").trim().removePrefix("psi:").trim()
        if (raw.isEmpty()) return ToolResult.error("find_implementations needs a 'ref' (psi:<fqClass>[#member]).")
        val hash = raw.indexOf('#')
        val fqName = (if (hash >= 0) raw.substring(0, hash) else raw).trim()
        val member = if (hash >= 0) raw.substring(hash + 1).trim().ifBlank { null } else null
        val project = context.project
        if (DumbService.isDumb(project)) return ToolResult.error("IDE is still indexing — retry shortly.")

        return ReadAction.compute<ToolResult, RuntimeException> {
            try {
                val scope = GlobalSearchScope.projectScope(project)
                when (val element = JvmSymbols.resolve(project, fqName, member)) {
                    is PsiClass -> {
                        val found = ClassInheritorsSearch.search(element, scope, true).findAll()
                        val located = found.mapNotNull { c ->
                            (c.qualifiedName ?: c.name)?.let { "$it  ${JvmSymbols.locationOf(c).orEmpty()}" }
                        }.distinct().take(MAX)
                        result("implementations/subclasses of $raw", located, found.size)
                    }

                    is PsiMethod -> {
                        val all = OverridingMethodsSearch.search(element, scope, true).findAll()
                        val located = all.mapNotNull { m ->
                            val fq = m.containingClass?.qualifiedName ?: m.containingClass?.name
                            fq?.let { "$it#${m.name}  ${JvmSymbols.locationOf(m).orEmpty()}" }
                        }.distinct().take(MAX)
                        result("overrides of $raw", located, all.size)
                    }

                    null -> ToolResult.error("Symbol '$raw' not found. Use find_symbol to locate the exact name first.")
                    else -> ToolResult.error("'$raw' is neither a class nor a method — hierarchy applies to types and methods.")
                }
            } catch (e: JvmSymbols.JavaUnavailable) {
                ToolResult.error(e.message ?: "Java PSI unavailable.")
            }
        }
    }

    private fun result(label: String, rows: List<String>, total: Int): ToolResult =
        if (rows.isEmpty()) {
            ToolResult.ok("No $label in project sources.")
        } else {
            val capped = total > rows.size
            ToolResult.ok("$total $label${if (capped) " (showing ${rows.size})" else ""}:\n" + rows.joinToString("\n"))
        }
}
