package com.github.saeldrit.geai.tools

import com.github.saeldrit.geai.tools.debug.AwaitPauseTool
import com.github.saeldrit.geai.tools.debug.DebugEvaluateTool
import com.github.saeldrit.geai.tools.debug.DebugStateTool
import com.github.saeldrit.geai.tools.debug.DebugVariablesTool
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
import com.github.saeldrit.geai.tools.grace.ContextBundleTool
import com.github.saeldrit.geai.tools.grace.EscalateAuthorTool
import com.github.saeldrit.geai.tools.grace.GraphNeighborsTool
import com.github.saeldrit.geai.tools.grace.GraphQueryTool
import com.github.saeldrit.geai.tools.grace.GraphReindexTool
import com.github.saeldrit.geai.tools.grace.ResolveRefTool
import com.github.saeldrit.geai.tools.grace.SpecListTool
import com.github.saeldrit.geai.tools.grace.SpecLookupTool
import com.github.saeldrit.geai.tools.grace.SpecRecordTool
import com.github.saeldrit.geai.tools.grace.SpecValidateTool
import com.github.saeldrit.geai.tools.knowledge.KbForgetTool
import com.github.saeldrit.geai.tools.knowledge.KbLookupTool
import com.github.saeldrit.geai.tools.knowledge.KbRecordTool
import com.github.saeldrit.geai.tools.project.ProjectOverviewTool
import com.github.saeldrit.geai.tools.selfmod.SelfInfoTool
import com.github.saeldrit.geai.tools.selfmod.SelfPatchTool
import com.github.saeldrit.geai.tools.interaction.AskUserTool
import com.github.saeldrit.geai.tools.system.RunCommandTool

/** Central catalog of tools advertised to the model. */
object GeaiToolset {

    fun all(): List<AgentTool> = listOf(
        // Knowledge axes (consult first — saves context)
        KbLookupTool,
        KbRecordTool,
        KbForgetTool,
        // GRACE anchors — resolve Category-B facts to live ground truth
        ResolveRefTool,
        // GRACE specs — Category-A intent/rules + drift validation
        SpecListTool,
        SpecLookupTool,
        SpecRecordTool,
        SpecValidateTool,
        // GRACE graph — navigable structure + governance edges
        GraphQueryTool,
        GraphNeighborsTool,
        GraphReindexTool,
        // GRACE context bundle — minimal precise context assembled from the graph
        ContextBundleTool,
        // GRACE tiered routing — delegate code authoring to the strong tier
        EscalateAuthorTool,
        // User interaction (clarifying questions, confirmations)
        AskUserTool,
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
        DebugVariablesTool,
        DebugEvaluateTool,
        // System & self-modification
        RunCommandTool,
        SelfInfoTool,
        SelfPatchTool,
    )

    fun registry(): ToolRegistry = ToolRegistry(all())
}
