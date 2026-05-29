package com.github.saeldrit.geai.tools.debug

import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult

/** Reports active debug sessions and, when paused, the current source position. */
object DebugStateTool : AgentTool {
    override val name = "debug_state"
    override val description =
        "Report active debug sessions: running/paused/stopped, and the paused source location " +
            "(file:line) when applicable."
    override val parametersJsonSchema = """{"type":"object","properties":{}}"""

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult =
        DebuggerSupport.describeState(context.project)
}
