package com.github.saeldrit.geai.tools.grace

import com.github.saeldrit.geai.spec.SpecItemKind
import com.github.saeldrit.geai.spec.SpecStore
import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult

/**
 * Authors or updates one GRACE spec item under `spec/<spec_id>.spec.xml`. Content kinds carry a
 * body (the rule, verbatim); reference kinds carry an anchor `ref` instead of a copy — the tool
 * resolves it once to stamp a drift baseline. This is how the agent builds the project's rule set.
 */
object SpecRecordTool : AgentTool {
    override val name = "spec_record"
    override val mutating = true
    override val description =
        "Create/update a GRACE spec item. Content kinds (INVARIANT/FORMULA/STATE_MACHINE/INTENT/" +
            "POLICY) require 'body'. Reference kinds (CONTRACT/GOVERNED_BY) require an anchor 'ref' " +
            "(e.g. openapi:main#/paths/... or psi:com.app.X#m) — NEVER paste a contract copy. Pass " +
            "'item_id' to update an existing item in place."
    override val parametersJsonSchema = """
        {"type":"object","properties":{
          "spec_id":{"type":"string","description":"Spec file id → spec/<spec_id>.spec.xml"},
          "kind":{"type":"string","enum":["INVARIANT","FORMULA","STATE_MACHINE","INTENT","POLICY","CONTRACT","GOVERNED_BY"]},
          "item_id":{"type":"string","description":"Stable id of the item; updates it in place if it exists (optional)"},
          "body":{"type":"string","description":"Rule text for content kinds"},
          "ref":{"type":"string","description":"Anchor for reference kinds"},
          "tags":{"type":"array","items":{"type":"string"},"description":"Optional tags"},
          "title":{"type":"string","description":"Spec title (set on first write, optional)"},
          "domain":{"type":"string","description":"Spec domain (optional)"}
        },"required":["spec_id","kind"]}
    """.trimIndent()

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val specId = args.string("spec_id").trim()
        val kind = SpecItemKind.fromName(args.string("kind"))
            ?: return ToolResult.error("Unknown kind. Use one of: ${SpecItemKind.entries.joinToString(", ") { it.name }}")
        val ref = args.stringOrNull("ref")?.trim()
        val body = args.stringOrNull("body")?.trim().orEmpty()

        if (kind.isReference && ref.isNullOrBlank()) {
            return ToolResult.error("Kind ${kind.name} requires 'ref' (an anchor), not a copied contract.")
        }
        if (!kind.isReference && body.isBlank()) {
            return ToolResult.error("Kind ${kind.name} requires 'body' (the rule text).")
        }

        val spec = SpecStore.getInstance(context.project).upsertItem(
            specId = specId,
            title = args.stringOrNull("title"),
            domain = args.stringOrNull("domain"),
            kind = kind,
            itemId = args.stringOrNull("item_id")?.trim()?.takeIf { it.isNotBlank() },
            tags = args.stringList("tags"),
            ref = ref,
            body = body,
        )
        val baseline = if (kind.isReference) {
            val item = spec.items.lastOrNull { it.kind == kind && it.ref == ref }
            if (item?.refHash != null) " (anchor resolved, drift baseline stamped)" else " (warning: anchor did not resolve — no baseline; check the ref)"
        } else {
            ""
        }
        return ToolResult.ok("Recorded ${kind.name} in ${spec.relativePath}$baseline. Spec now has ${spec.items.size} item(s).")
    }
}
