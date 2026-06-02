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

    /** Cached ToolSpec — built once, re-used across all turns. */
    fun spec(): ToolSpec = SpecCache.getOrPut(name) { ToolSpec(name, description, parametersJsonSchema) }
}

/** Per-tool cache of [ToolSpec] (Kotlin interfaces can't hold mutable state, so this lives here). */
private val SpecCache = java.util.concurrent.ConcurrentHashMap<String, ToolSpec>()
