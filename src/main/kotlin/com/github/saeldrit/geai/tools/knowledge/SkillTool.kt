package com.github.saeldrit.geai.tools.knowledge

import com.github.saeldrit.geai.context.SkillStore
import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult

/**
 * Manage user preferences / skills that persist across sessions and are injected into the system
 * prompt. Actions: `save` (create or update), `list` (all skills), `remove` (by id).
 *
 * This tool writes to `.geai/` — NOT project source — so it is non-mutating by convention.
 */
object SkillTool : AgentTool {
    override val name = "skill"
    override val description =
        "Manage durable user preferences (skills) that survive across sessions and are injected " +
            "into the system prompt as <user_preferences>. Actions: save (create or update a skill " +
            "from a free-text description), list (show all saved skills), remove (delete by id). " +
            "Use this when the user expresses a lasting preference — naming style, tech choices, " +
            "interaction expectations — that should always influence your behavior."
    override val parametersJsonSchema = """
        {"type":"object","properties":{
          "action":{"type":"string","enum":["save","list","remove"],"description":"What to do"},
          "description":{"type":"string","description":"Free-text description of the preference (required for save)"},
          "id":{"type":"string","description":"Skill id to remove (required for remove; use the id from list)"},
          "source":{"type":"string","description":"Optional origin hint, e.g. 'user' or 'inferred'"}
        },"required":["action"]}
    """.trimIndent()

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val action = args.string("action")
        val store = SkillStore.getInstance(context.project)
        return when (action) {
            "save" -> {
                val desc = args.string("description")
                val source = args.stringOrNull("source") ?: "user"
                val result = store.save(desc, source)
                if (!result.saved) return ToolResult.error("Invalid skill: description is empty or invalid.")
                val skill = result.skill!!
                val conflictMsg = if (result.hasConflicts) {
                    val conflictIds = result.conflicts.joinToString(", ") { it.id }
                    "\n⚠ Conflicts with: $conflictIds (domain: ${result.conflictDomain}). Consider removing the old preference."
                } else ""
                ToolResult.ok("Saved skill '${skill.id}': ${skill.description}$conflictMsg")
            }
            "list" -> {
                val skills = store.loadAll()
                if (skills.isEmpty()) return ToolResult.ok("No skills saved yet.")
                val rows = skills.joinToString("\n") { s ->
                    "- [${s.id}] ${s.description} (source: ${s.source})"
                }
                ToolResult.ok("${skills.size} skill(s):\n$rows")
            }
            "remove" -> {
                val id = args.stringOrNull("id")
                    ?: return ToolResult.error("Pass the skill id (from 'list') in the 'id' parameter to remove it.")
                val removed = store.delete(id)
                if (removed) ToolResult.ok("Removed skill '$id'.")
                else ToolResult.error("No skill found with id '$id'.")
            }
            else -> ToolResult.error("Unknown action '$action'. Use save, list, or remove.")
        }
    }
}
