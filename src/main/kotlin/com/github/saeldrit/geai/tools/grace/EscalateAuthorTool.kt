package com.github.saeldrit.geai.tools.grace

import com.github.saeldrit.geai.agent.SystemPrompt
import com.github.saeldrit.geai.cost.UsageFormat
import com.github.saeldrit.geai.llm.ChatMessage
import com.github.saeldrit.geai.llm.ChatRequest
import com.github.saeldrit.geai.llm.LlmClientFactory
import com.github.saeldrit.geai.llm.LlmException
import com.github.saeldrit.geai.settings.GeaiSettings
import com.github.saeldrit.geai.settings.authorModel
import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult

/**
 * GRACE tiered routing: hands a focused authoring task to the strong author-tier model in one shot.
 * The cheap navigator (the main loop) gathers context via context_bundle / resolve_ref, then calls
 * this to get a concrete code change, and applies it itself with edit_file/write_file (so mutation
 * stays gated in one place). Same provider/key as the main model — the author tier is just its
 * stronger model. Returns the proposed change as text; it does not touch files.
 */
object EscalateAuthorTool : AgentTool {
    override val name = "escalate_author"
    override val description =
        "Delegate writing a concrete code change to the strong author-tier model. Pass the task and the " +
            "gathered context (the <context_bundle> block plus any resolve_ref output). Returns target " +
            "file path(s) and the new content / precise edit — which YOU then apply with edit_file/" +
            "write_file. Use after gathering context, for the actual code authoring; navigation stays cheap."
    override val parametersJsonSchema = """
        {"type":"object","properties":{
          "task":{"type":"string","description":"Precise authoring task (what to implement/fix and where)"},
          "context":{"type":"string","description":"The context_bundle output (governing rules + live contracts + neighborhood)"}
        },"required":["task"]}
    """.trimIndent()

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val task = args.string("task").trim()
        val bundle = args.stringOrNull("context")?.trim().orEmpty()
        val settings = GeaiSettings.getInstance().state

        val client = try {
            LlmClientFactory.create()
        } catch (e: LlmException) {
            return ToolResult.error(e.message ?: "Author tier is not configured.")
        }

        val userContent = buildString {
            appendLine("Task:")
            appendLine(task)
            if (bundle.isNotBlank()) {
                appendLine()
                appendLine("Context bundle:")
                append(bundle)
            }
        }
        val request = ChatRequest(
            model = settings.authorModel(),
            system = SystemPrompt.authorDoctrine(),
            messages = listOf(ChatMessage.user(userContent)),
            tools = emptyList(),
            maxTokens = settings.maxTokens,
        )

        return try {
            val result = client.chat(request, context.indicator)
            val text = result.message.text.ifBlank { "(author tier returned no text)" }
            val usage = UsageFormat.line("author", settings.authorModel(), result.usage, settings.modelPrices)
            ToolResult.ok("$usage\n$text")
        } catch (e: LlmException) {
            ToolResult.error("escalate_author failed: ${e.message}")
        }
    }
}
