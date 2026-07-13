package com.github.saeldrit.geai.tools.knowledge

import com.github.saeldrit.geai.knowledge.GeaiKnowledgeStore
import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult

object KbForgetTool : AgentTool {
    override val name = "kb_forget"
    override val description = "Delete a knowledge entry by id (use when a recorded fact became wrong or stale)."
    override val parametersJsonSchema = """
        {"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}
    """.trimIndent()

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val id = args.string("id")
        val removed = GeaiKnowledgeStore.getInstance(context.project).remove(id)
        return if (removed) ToolResult.ok("Forgot '$id'.") else ToolResult.ok("No entry '$id' to forget.")
    }
}
