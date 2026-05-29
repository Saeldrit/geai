package com.github.saeldrit.geai.tools.grace

import com.github.saeldrit.geai.spec.SpecStore
import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult

/** Lists GRACE specs (Category-A intent) with their item counts — the map of project rules. */
object SpecListTool : AgentTool {
    override val name = "spec_list"
    override val description =
        "List the project's GRACE specs (invariants, formulas, policies, contracts) under spec/. " +
            "Consult before implementing a feature to learn the rules that govern it."
    override val parametersJsonSchema = """{"type":"object","properties":{}}"""

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val specs = SpecStore.getInstance(context.project).list()
        if (specs.isEmpty()) {
            return ToolResult.ok("No specs yet. Author Category-A rules with spec_record (spec/<id>.spec.xml).")
        }
        val rows = specs.joinToString("\n") { spec ->
            val counts = spec.items.groupingBy { it.kind.name }.eachCount()
                .entries.joinToString(", ") { "${it.key}=${it.value}" }
            val domain = spec.domain?.let { " [$it]" } ?: ""
            "• ${spec.id}$domain — ${spec.title}  ($counts)"
        }
        return ToolResult.ok("${specs.size} spec(s):\n$rows")
    }
}
