package com.github.saeldrit.geai.tools

import com.github.saeldrit.geai.llm.ToolSpec

/**
 * A capability the agent can invoke. Tools are stateless singletons; everything they need
 * at runtime (project, progress indicator) arrives via [ToolContext], so they are safe to
 * share across sessions and threads.
 */
interface AgentTool {
    val name: String
    val description: String

    /** JSON Schema *object* (as a string) describing the arguments. */
    val parametersJsonSchema: String

    /** Read-only tools may be auto-approved; mutating tools require user opt-in. */
    val mutating: Boolean
        get() = false

    fun execute(args: ToolArgs, context: ToolContext): ToolResult

    fun spec(): ToolSpec = ToolSpec(name, description, parametersJsonSchema)
}
