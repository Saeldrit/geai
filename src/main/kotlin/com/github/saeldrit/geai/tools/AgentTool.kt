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

    val parametersJsonSchema: String

    val mutating: Boolean
        get() = false

    /** Interactive tools block on a user dialog and MUST run one at a time (never on the parallel
     *  pool — two at once would stack modal dialogs). Separate from [mutating]: no approval gate. */
    val interactive: Boolean
        get() = false

    /** A poll/navigation tool that legitimately returns the SAME result while underlying state advances
     *  (debugger wait/step/state). Steps made up ONLY of these are exempt from the stuck-loop guard, so
     *  the doctrine-ordered debug wait/step loop is never mistaken for thrashing. */
    val idempotentPoll: Boolean
        get() = false

    fun execute(args: ToolArgs, context: ToolContext): ToolResult

    /** Cached ToolSpec — built once, re-used across all turns. */
    fun spec(): ToolSpec = SpecCache.getOrPut(name) { ToolSpec(name, description, parametersJsonSchema) }
}

private val SpecCache = java.util.concurrent.ConcurrentHashMap<String, ToolSpec>()
