package com.github.saeldrit.geai.tools.debug

import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult

object ListBreakpointsTool : AgentTool {
    override val name = "list_breakpoints"
    override val description = "List all line breakpoints currently set in the project."
    override val parametersJsonSchema = """{"type":"object","properties":{}}"""

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult =
        DebuggerSupport.listBreakpoints(context.project)
}
