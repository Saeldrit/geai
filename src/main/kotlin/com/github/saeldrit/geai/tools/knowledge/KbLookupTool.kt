package com.github.saeldrit.geai.tools.knowledge

import com.github.saeldrit.geai.knowledge.Axis
import com.github.saeldrit.geai.knowledge.GeaiKnowledgeStore
import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult

/** Reads geai's project knowledge index — the cheap first stop before searching/reading files. */
object KbLookupTool : AgentTool {
    override val name = "kb_lookup"
    override val description =
        "Query the project knowledge index by axis (NAV/STYLE/TECH/LESSON), tags, or text. Returns " +
            "compact entries with version and file:line. ALWAYS try this before search_text/read_file " +
            "to reuse what was already discovered and save context."
    override val parametersJsonSchema = """
        {"type":"object","properties":{
          "axis":{"type":"string","enum":["NAV","STYLE","TECH","LESSON"],"description":"Restrict to one axis (optional)"},
          "tags":{"type":"array","items":{"type":"string"},"description":"Match any of these tags (optional)"},
          "query":{"type":"string","description":"Free-text match over title/body/tags/location (optional)"},
          "max_results":{"type":"integer","description":"Cap (default 30)"}
        }}
    """.trimIndent()

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val axis = args.stringOrNull("axis")?.let { runCatching { Axis.valueOf(it.uppercase()) }.getOrNull() }
        val tags = args.stringList("tags")
        val query = args.stringOrNull("query")
        val max = args.int("max_results", 30).coerceIn(1, 200)

        val results = GeaiKnowledgeStore.getInstance(context.project).query(axis, tags, query).take(max)
        if (results.isEmpty()) {
            return ToolResult.ok("No matching knowledge yet. Discover it with tools, then store it via kb_record.")
        }
        val rows = results.joinToString("\n") { entry ->
            val location = entry.location?.let { " @ $it" } ?: ""
            val tagList = if (entry.tags.isEmpty()) "" else " #${entry.tags.joinToString(",")}"
            val body = entry.body.replace('\n', ' ').trim().let { if (it.length > 200) it.take(200) + "…" else it }
            "[${entry.axis} v${entry.version}] ${entry.id}: ${entry.title}$location$tagList\n  $body"
        }
        return ToolResult.ok("${results.size} entr(ies):\n$rows")
    }
}
