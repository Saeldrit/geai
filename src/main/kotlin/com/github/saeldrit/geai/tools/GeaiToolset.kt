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
import com.github.saeldrit.geai.tools.grace.GraphQueryTool
import com.github.saeldrit.geai.tools.grace.ResolveRefTool
import com.github.saeldrit.geai.tools.knowledge.KbForgetTool
import com.github.saeldrit.geai.tools.knowledge.KbLookupTool
import com.github.saeldrit.geai.tools.knowledge.KbRecordTool
import com.github.saeldrit.geai.tools.project.ProjectOverviewTool
import com.github.saeldrit.geai.tools.selfmod.SelfInfoTool
import com.github.saeldrit.geai.tools.selfmod.SelfPatchTool
import com.github.saeldrit.geai.tools.interaction.AskUserTool
import com.github.saeldrit.geai.tools.system.RunCommandTool
import com.github.saeldrit.geai.settings.GeaiSettings

/** Central catalog of tools advertised to the model. */
object GeaiToolset {

    /** Always-on core: knowledge axes, interaction, navigation, editing, debugging, system. */
    private val CORE: List<AgentTool> = listOf(
        // Knowledge axes (consult first — saves context)
        KbLookupTool,
        KbRecordTool,
        KbForgetTool,
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

    /**
     * Lean GRACE surface advertised to the model. Only the two tools it actually needs at runtime:
     * resolve_ref (live Category-B truth) and graph_query (dig deeper if the injected bundle is thin).
     * The heavier tools (spec_*, graph_neighbors/reindex, context_bundle, escalate_author) are PARKED
     * — their classes remain, but they are not advertised: the bundle is auto-injected, the graph
     * auto-reindexes, and every advertised schema is re-sent each turn (pure cost on non-caching
     * providers). Re-add here if interactive use is needed again.
     */
    private val GRACE: List<AgentTool> = listOf(
        ResolveRefTool,
        GraphQueryTool,
    )

    fun all(): List<AgentTool> =
        if (GeaiSettings.getInstance().state.graceEnabled) GRACE + CORE else CORE

    fun registry(): ToolRegistry = ToolRegistry(all())
}
