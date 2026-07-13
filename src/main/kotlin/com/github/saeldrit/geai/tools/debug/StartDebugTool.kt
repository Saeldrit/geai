package com.github.saeldrit.geai.tools.debug

import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.openapi.application.ApplicationManager

object StartDebugTool : AgentTool {
    override val name = "start_debug"
    override val mutating = true
    override val description =
        "Start debugging the currently selected Run/Debug configuration. Previously set breakpoints " +
            "will be honored. After calling this, use await_pause to wait for a breakpoint hit."
    override val parametersJsonSchema = """{"type":"object","properties":{}}"""

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val settings = RunManager.getInstance(context.project).selectedConfiguration
            ?: return ToolResult.error(
                "No Run/Debug configuration is selected. Ask the user to pick one in the Run widget, then retry.",
            )
        ApplicationManager.getApplication().invokeLater {
            ProgramRunnerUtil.executeConfiguration(settings, DefaultDebugExecutor.getDebugExecutorInstance())
        }
        return ToolResult.ok(
            "Launching debug for '${settings.name}'. Call await_pause to wait for a breakpoint, " +
                "or debug_state to poll the session.",
        )
    }
}
