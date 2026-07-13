package com.github.saeldrit.geai.tools.grace

import com.github.saeldrit.geai.anchor.AnchorResolvers
import com.intellij.openapi.progress.ProcessCanceledException
import com.github.saeldrit.geai.spec.SpecStore
import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult

/**
 * Drift detector: re-resolves every reference anchor in the specs and compares its live content
 * hash to the baseline stamped at write time. This is the mechanical guarantee that lets a cheap
 * model be trusted — spec↔code desync surfaces as DRIFT/BROKEN instead of silently rotting.
 */
object SpecValidateTool : AgentTool {
    override val name = "spec_validate"
    override val description =
        "Validate GRACE spec reference anchors against live code: OK (matches baseline), DRIFT " +
            "(contract changed — review/re-record), BROKEN (anchor no longer resolves), NO-BASELINE " +
            "(never resolved). Run after edits and before finishing a feature."
    override val parametersJsonSchema = """
        {"type":"object","properties":{
          "spec_id":{"type":"string","description":"Validate one spec only (optional; default all)"}
        }}
    """.trimIndent()

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val store = SpecStore.getInstance(context.project)
        val specId = args.stringOrNull("spec_id")
        val specs = specId?.let { listOfNotNull(store.find(it)) } ?: store.list()
        if (specs.isEmpty()) return ToolResult.ok("No specs to validate.")

        val lines = mutableListOf<String>()
        var ok = 0
        var problems = 0
        specs.forEach { spec ->
            spec.items.filter { it.kind.isReference && it.ref != null }.forEach { item ->
                val ref = item.ref ?: return@forEach
                val status = try {
                    val live = AnchorResolvers.resolve(ref, context.project)
                    when {
                        item.refHash == null -> "NO-BASELINE"
                        item.refHash == live.contentHash -> "OK"
                        else -> "DRIFT"
                    }
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    "BROKEN: ${e.message}"
                }
                if (status == "OK") ok++ else problems++
                if (status != "OK") lines.add("  [$status] ${spec.id}/${item.kind.name} ref=$ref")
            }
        }
        val summary = "Validated specs: $ok OK, $problems need attention."
        return ToolResult(
            if (lines.isEmpty()) summary else "$summary\n${lines.joinToString("\n")}",
            isError = problems > 0,
        )
    }
}
