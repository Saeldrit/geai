package com.github.saeldrit.geai.tools.psi

import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache

/**
 * Locate JVM symbols by SHORT name through the IDE's symbol index — the semantic alternative to
 * grepping a name to find where a class/method/field lives. Returns each match's kind, fully-qualified
 * name, file:line, and a ready `psi:` anchor to hand to resolve_ref or find_usages.
 */
object FindSymbolTool : AgentTool {
    override val name = "find_symbol"
    override val description =
        "Find JVM symbols (class / method / field) by SHORT name via the IDE index — use this instead of " +
            "grepping a name to locate code. Returns kind, fully-qualified name, file:line and a psi: anchor " +
            "you can pass to resolve_ref or find_usages. Optional 'kind' filter: class | method | field."
    override val parametersJsonSchema = """
        {"type":"object","properties":{
          "name":{"type":"string","description":"Short symbol name, e.g. FoodController or buildResponse"},
          "kind":{"type":"string","description":"Optional filter: class | method | field"}
        },"required":["name"]}
    """.trimIndent()

    private const val MAX = 50

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val name = args.string("name").trim()
        if (name.isEmpty()) return ToolResult.error("find_symbol needs a non-empty 'name'.")
        val kind = args.stringOrNull("kind")?.trim()?.lowercase()
        val project = context.project
        if (DumbService.isDumb(project)) return ToolResult.error("IDE is still indexing — retry shortly.")

        return ReadAction.compute<ToolResult, RuntimeException> {
            try {
                val scope = GlobalSearchScope.projectScope(project)
                val cache = PsiShortNamesCache.getInstance(project)
                val rows = ArrayList<String>()

                if (kind == null || kind == "class") {
                    cache.getClassesByName(name, scope).forEach { c ->
                        val fq = c.qualifiedName ?: c.name ?: return@forEach
                        rows.add("class  $fq  ${JvmSymbols.locationOf(c).orEmpty()}  → psi:$fq")
                    }
                }
                if (kind == null || kind == "method") {
                    cache.getMethodsByName(name, scope).forEach { m ->
                        val fq = m.containingClass?.qualifiedName ?: return@forEach
                        rows.add("method $fq#${m.name}  ${JvmSymbols.locationOf(m).orEmpty()}  → psi:$fq#${m.name}")
                    }
                }
                if (kind == null || kind == "field") {
                    cache.getFieldsByName(name, scope).forEach { f ->
                        val fq = f.containingClass?.qualifiedName ?: return@forEach
                        rows.add("field  $fq#${f.name}  ${JvmSymbols.locationOf(f).orEmpty()}  → psi:$fq#${f.name}")
                    }
                }

                if (rows.isEmpty()) {
                    ToolResult.ok("No JVM symbol named '$name' in project sources. Check spelling; for plain text use search_text.")
                } else {
                    val capped = rows.size > MAX
                    ToolResult.ok(
                        "${rows.size} symbol(s)${if (capped) " (showing $MAX)" else ""} named '$name':\n" +
                            rows.take(MAX).joinToString("\n"),
                    )
                }
            } catch (e: JvmSymbols.JavaUnavailable) {
                ToolResult.error(e.message ?: "Java PSI unavailable.")
            }
        }
    }
}
