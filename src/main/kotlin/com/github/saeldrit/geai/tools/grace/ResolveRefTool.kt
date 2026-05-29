package com.github.saeldrit.geai.tools.grace

import com.github.saeldrit.geai.anchor.AnchorException
import com.github.saeldrit.geai.anchor.AnchorResolvers
import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult

/**
 * GRACE anchor resolver tool. Turns a `scheme:locator` anchor into live ground truth (current
 * signature / contract / file slice). This is the Category-B discipline in action: instead of
 * recalling or copying an API shape, the agent resolves the anchor and reads what the code says
 * right now. Read-only.
 */
object ResolveRefTool : AgentTool {
    override val name = "resolve_ref"
    override val description =
        "Resolve a GRACE anchor to its live source of truth. Use for any API signature, DTO, " +
            "endpoint, or symbol — NEVER recall or copy these from memory. Schemes: " +
            "psi:<fqClass>[#member] (live JVM symbol), file:<path>[:start-end] (file slice), " +
            "openapi:<doc>#<json-pointer> (generated OpenAPI contract). Returns kind, location, " +
            "signature, a content hash (for drift), and the resolved content."
    override val parametersJsonSchema = """
        {"type":"object","properties":{
          "ref":{"type":"string","description":"Anchor as scheme:locator, e.g. psi:com.app.GoalService#create or openapi:main#/paths/~1api~1v1~1goals/post"}
        },"required":["ref"]}
    """.trimIndent()

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val ref = args.string("ref").trim()
        return try {
            val anchor = AnchorResolvers.resolve(ref, context.project)
            val header = buildString {
                appendLine("anchor: ${anchor.ref}")
                appendLine("kind: ${anchor.kind}")
                anchor.location?.let { appendLine("location: $it") }
                anchor.signature?.let { appendLine("signature: $it") }
                append("hash: ${anchor.contentHash.take(12)}")
            }
            ToolResult.ok("$header\n--- content ---\n${anchor.content}")
        } catch (e: AnchorException) {
            ToolResult.error(e.message ?: "Could not resolve anchor '$ref'.")
        }
    }
}
