package com.github.saeldrit.geai.tools

import com.github.saeldrit.geai.tools.debug.AwaitPauseTool
import com.github.saeldrit.geai.tools.debug.DebugStateTool
import com.github.saeldrit.geai.tools.debug.ListBreakpointsTool
import com.github.saeldrit.geai.tools.debug.RemoveBreakpointTool
import com.github.saeldrit.geai.tools.debug.SetBreakpointTool
import com.github.saeldrit.geai.tools.debug.StartDebugTool
import com.github.saeldrit.geai.tools.fs.EditFileTool
import com.github.saeldrit.geai.tools.fs.FindFilesTool
import com.github.saeldrit.geai.tools.fs.ListFilesTool
import com.github.saeldrit.geai.tools.fs.ReadFileTool
import com.github.saeldrit.geai.tools.fs.SearchTextTool
import com.github.saeldrit.geai.tools.fs.WriteFileTool
import com.github.saeldrit.geai.tools.project.ProjectOverviewTool
import com.github.saeldrit.geai.tools.selfmod.SelfInfoTool
import com.github.saeldrit.geai.tools.selfmod.SelfPatchTool
import com.github.saeldrit.geai.tools.system.RunCommandTool

/** Central catalog of tools advertised to the model. */
object GeaiToolset {

    fun all(): List<AgentTool> = listOf(
        // Navigation & reading
        ProjectOverviewTool,
        FindFilesTool,
        ListFilesTool,
        ReadFileTool,
        SearchTextTool,
        // Editing
        WriteFileTool,
        EditFileTool,
        // Debugging
        SetBreakpointTool,
        RemoveBreakpointTool,
        ListBreakpointsTool,
        DebugStateTool,
        StartDebugTool,
        AwaitPauseTool,
        // System & self-modification
        RunCommandTool,
        SelfInfoTool,
        SelfPatchTool,
    )

    fun registry(): ToolRegistry = ToolRegistry(all())
}
