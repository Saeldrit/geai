package com.github.saeldrit.geai.tools.grace

import com.github.saeldrit.geai.spec.Spec
import com.github.saeldrit.geai.spec.SpecItem
import com.github.saeldrit.geai.spec.SpecItemKind
import com.github.saeldrit.geai.spec.SpecStore
import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult

/**
 * Reads GRACE spec items. Content items (invariant/formula/policy/intent/state-machine) come back
 * verbatim — apply them as constraints. Reference items (contract/governed-by) come back as anchors;
 * resolve them with `resolve_ref` to see the live contract, never assume it.
 */
object SpecLookupTool : AgentTool {
    override val name = "spec_lookup"
    override val description =
        "Read GRACE spec items by spec id, kind (INVARIANT/FORMULA/STATE_MACHINE/INTENT/POLICY/" +
            "CONTRACT/GOVERNED_BY), tag, or free text. Content items are authoritative rules to obey; " +
            "reference items are anchors to resolve with resolve_ref."
    override val parametersJsonSchema = """
        {"type":"object","properties":{
          "spec_id":{"type":"string","description":"Restrict to one spec (optional)"},
          "kind":{"type":"string","description":"Restrict to one item kind (optional)"},
          "tag":{"type":"string","description":"Match items carrying this tag (optional)"},
          "query":{"type":"string","description":"Free-text match over body/ref/id (optional)"}
        }}
    """.trimIndent()

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val store = SpecStore.getInstance(context.project)
        val specId = args.stringOrNull("spec_id")
        val kind = args.stringOrNull("kind")?.let { SpecItemKind.fromName(it) }
        val tag = args.stringOrNull("tag")
        val query = args.stringOrNull("query")?.lowercase()?.takeIf { it.isNotBlank() }

        val specs = specId?.let { listOfNotNull(store.find(it)) } ?: store.list()
        if (specs.isEmpty()) return ToolResult.ok("No matching specs. Use spec_list to see what exists.")

        val rendered = specs.mapNotNull { spec ->
            val items = spec.items.filter { item ->
                (kind == null || item.kind == kind) &&
                    (tag == null || item.tags.any { it.equals(tag, ignoreCase = true) }) &&
                    (query == null || item.matches(query))
            }
            if (items.isEmpty()) null else renderSpec(spec, items)
        }
        if (rendered.isEmpty()) return ToolResult.ok("No spec items matched the filter.")
        return ToolResult.ok(rendered.joinToString("\n\n"))
    }

    private fun SpecItem.matches(needle: String): Boolean =
        body.lowercase().contains(needle) ||
            ref?.lowercase()?.contains(needle) == true ||
            id?.lowercase()?.contains(needle) == true

    private fun renderSpec(spec: Spec, items: List<SpecItem>): String {
        val header = "# ${spec.id} — ${spec.title}${spec.domain?.let { " [$it]" } ?: ""}"
        val body = items.joinToString("\n") { item ->
            val idTag = item.id?.let { " ($it)" } ?: ""
            val tags = if (item.tags.isEmpty()) "" else " #${item.tags.joinToString(",")}"
            if (item.kind.isReference) {
                "  [${item.kind.name}$idTag] ref=${item.ref}$tags  → resolve_ref to read live"
            } else {
                "  [${item.kind.name}$idTag]$tags ${item.body}"
            }
        }
        return "$header\n$body"
    }
}
