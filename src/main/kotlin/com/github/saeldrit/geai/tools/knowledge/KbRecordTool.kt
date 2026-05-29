package com.github.saeldrit.geai.tools.knowledge

import com.github.saeldrit.geai.knowledge.Axis
import com.github.saeldrit.geai.knowledge.GeaiKnowledgeStore
import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult

/** Records or updates a durable fact so future turns skip re-discovery. Updates use CAS. */
object KbRecordTool : AgentTool {
    override val name = "kb_record"
    override val description =
        "Store a durable fact in the knowledge index. Axes: NAV (symbol -> file:line), STYLE (code " +
            "conventions), TECH (stack/invariants), LESSON (what must NOT be done). To update an entry " +
            "safely pass expected_version equal to its current version (compare-and-swap); omit to create."
    override val parametersJsonSchema = """
        {"type":"object","properties":{
          "id":{"type":"string","description":"Stable id, e.g. nav:ClassifyService.classify"},
          "axis":{"type":"string","enum":["NAV","STYLE","TECH","LESSON"]},
          "title":{"type":"string"},
          "tags":{"type":"array","items":{"type":"string"}},
          "location":{"type":"string","description":"file:line, for NAV entries"},
          "body":{"type":"string","description":"Concise fact/summary"},
          "expected_version":{"type":"integer","description":"Current version when updating (CAS); omit to create"}
        },"required":["id","axis","title","body"]}
    """.trimIndent()

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val id = args.string("id")
        val axis = runCatching { Axis.valueOf(args.string("axis").uppercase()) }.getOrNull()
            ?: return ToolResult.error("axis must be one of NAV, STYLE, TECH, LESSON.")
        val title = args.string("title")
        val body = args.string("body")
        val tags = args.stringList("tags")
        val location = args.stringOrNull("location")
        val expectedVersion = args.intOrNull("expected_version")

        return when (val result = GeaiKnowledgeStore.getInstance(context.project)
            .put(id, axis, tags, title, location, body, expectedVersion)) {
            is GeaiKnowledgeStore.PutResult.Ok ->
                ToolResult.ok("Recorded '$id' (${axis.name}) as v${result.version}.")

            is GeaiKnowledgeStore.PutResult.Conflict ->
                ToolResult.error(
                    "Version conflict: '$id' is at v${result.currentVersion}. kb_lookup it, then retry " +
                        "with expected_version=${result.currentVersion}.",
                )
        }
    }
}
