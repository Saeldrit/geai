package com.github.saeldrit.geai.tools

import com.github.saeldrit.geai.llm.ToolSpec
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
import com.github.saeldrit.geai.tools.grace.EscalateAuthorTool
import com.github.saeldrit.geai.tools.grace.GraphQueryTool
import com.github.saeldrit.geai.tools.grace.ResolveRefTool
import com.github.saeldrit.geai.tools.psi.FindSymbolTool
import com.github.saeldrit.geai.tools.psi.FindUsagesTool
import com.github.saeldrit.geai.tools.knowledge.KbForgetTool
import com.github.saeldrit.geai.tools.knowledge.KbLookupTool
import com.github.saeldrit.geai.tools.knowledge.KbRecordTool
import com.github.saeldrit.geai.tools.project.ProjectOverviewTool
import com.github.saeldrit.geai.tools.selfmod.SelfInfoTool
import com.github.saeldrit.geai.tools.selfmod.SelfPatchTool
import com.github.saeldrit.geai.tools.interaction.AskUserTool
import com.github.saeldrit.geai.tools.system.RunCommandTool
import com.github.saeldrit.geai.settings.GeaiSettings

/**
 * Central catalog of tools. Tools split into an always-on CORE (navigation/reading/editing/
 * knowledge) and heavier ON_DEMAND groups (debug/run/selfmod). The agent loop advertises only
 * CORE plus a tiny `load_tools` meta-tool, and the model pulls a group in when it actually needs
 * it — so large, situational schemas are not re-sent every iteration (the dominant per-turn cost
 * on non-caching providers). [all]/[registry] still expose the full set for execution and for the
 * Claude Code engine, which advertises everything to the subscription CLI.
 */
object GeaiToolset {

    const val LOAD_TOOLS = "load_tools"
    const val DELEGATE = "delegate"
    const val NOTE = "note"

    /** Always advertised: knowledge axes, interaction, navigation, reading, editing. */
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
    )

    /**
     * Lean GRACE / semantic surface (small, central): live Category-B truth (resolve_ref), graph dig
     * (graph_query), and PSI semantic search (find_symbol, find_usages) — the IDE-native alternative
     * to grepping a name. Advertised alongside CORE whenever GRACE is enabled.
     */
    private val GRACE: List<AgentTool> = listOf(
        ResolveRefTool,
        GraphQueryTool,
        FindSymbolTool,
        FindUsagesTool,
    )

    /**
     * Heavier, situational tools advertised ONLY after the model loads the group via `load_tools`.
     * Their schemas are large and seldom needed on a given turn; shipping them every iteration is
     * pure cost (paid N times where there is no prompt caching). Insertion order is the catalog order.
     */
    private val ON_DEMAND: Map<String, List<AgentTool>> = linkedMapOf(
        "debug" to listOf(
            SetBreakpointTool,
            RemoveBreakpointTool,
            ListBreakpointsTool,
            DebugStateTool,
            StartDebugTool,
            AwaitPauseTool,
            DebugVariablesTool,
            DebugEvaluateTool,
        ),
        "run" to listOf(RunCommandTool),
        "selfmod" to listOf(SelfInfoTool, SelfPatchTool),
    )

    /** One-line purpose per group, shown to the model in the `load_tools` description. */
    private val GROUP_SUMMARY: Map<String, String> = linkedMapOf(
        "debug" to "set/remove/list breakpoints, start a debug session, inspect state & variables, evaluate expressions, await a pause",
        "run" to "run_command — run shell/build/test/git commands in the project",
        "selfmod" to "self_info, self_patch — inspect and modify geai's own source",
    )

    /**
     * Read-only navigation/analysis tools handed to a DELEGATED sub-agent. No mutation, no `delegate`
     * (no recursion), no `load_tools`. The sub-agent explores in its own clean context and returns a
     * compact finding, so the orchestrator's transcript never fills with raw file contents.
     */
    private val DELEGATE_TOOLS: List<AgentTool> = listOf(
        KbLookupTool,
        ProjectOverviewTool,
        FindFilesTool,
        ListFilesTool,
        ReadFileTool,
        SearchTextTool,
        ResolveRefTool,
        GraphQueryTool,
        FindSymbolTool,
        FindUsagesTool,
    )

    private fun base(graceEnabled: Boolean): List<AgentTool> = if (graceEnabled) GRACE + CORE else CORE

    /** Full catalog (every tool) — used by the registry for execution and by the Claude Code engine.
     *  escalate_author is always present for EXECUTION; it is only ADVERTISED under tiered routing. */
    fun all(): List<AgentTool> =
        base(GeaiSettings.getInstance().state.graceEnabled) + ON_DEMAND.values.flatten() + EscalateAuthorTool

    fun registry(): ToolRegistry = ToolRegistry(all())

    /**
     * Tools advertised to the model right now: CORE (+GRACE) plus any on-demand groups already loaded.
     * Under [tieredRouting] (and GRACE enabled) the navigator can hand authoring to the strong tier, so
     * `escalate_author` is advertised; with a single model (author == navigator) it would be dead weight.
     */
    fun advertisedTools(graceEnabled: Boolean, activeGroups: Set<String>, tieredRouting: Boolean = false): List<AgentTool> {
        val tools = base(graceEnabled) + activeGroups.flatMap { ON_DEMAND[it] ?: emptyList() }
        return if (tieredRouting && graceEnabled) tools + EscalateAuthorTool else tools
    }

    fun isGroup(name: String): Boolean = ON_DEMAND.containsKey(name)

    fun groupTools(name: String): List<AgentTool> = ON_DEMAND[name] ?: emptyList()

    /** Names of the on-demand groups, in catalog order (e.g. for doctrine text and error messages). */
    fun groupNames(): Set<String> = ON_DEMAND.keys

    /** Read-only toolset for a delegated sub-agent (see [DELEGATE_TOOLS]). */
    fun delegateTools(): List<AgentTool> = DELEGATE_TOOLS

    /** The `delegate` meta-tool spec, advertised by the main loop so it can fan work out to sub-agents. */
    fun delegateSpec(): ToolSpec {
        val description =
            "Delegate a focused, self-contained sub-task to a fresh sub-agent that has its OWN clean " +
                "context. Use it for anything that would otherwise flood your context with file contents — " +
                "reviewing/auditing/tracing a flow across many files. The sub-agent navigates and reads on " +
                "its own (read-only) and returns ONLY a compact result. Spawn ONE per independent unit (a " +
                "file, a module, a question), then synthesize their results. State exactly what to investigate " +
                "and what to return (findings with file:line — not raw code)."
        val schema =
            """{"type":"object","properties":{"task":{"type":"string","description":"The focused, self-contained instruction for the sub-agent, including exactly what it should return."},"hint":{"type":"string","description":"Optional leads — file paths, anchors, symbols — to save the sub-agent discovery time."}},"required":["task"]}"""
        return ToolSpec(DELEGATE, description, schema)
    }

    /** The `note` meta-tool spec: the model's external working memory (findings/decisions/next steps). */
    fun noteSpec(): ToolSpec {
        val description =
            "Record a concise finding, decision or next step to your persistent NOTES. Notes are always " +
                "visible to you and survive context compaction, so use this as you work — never lose what " +
                "you found. Include file:line. One item per call. Build your final answer FROM your notes; " +
                "do not keep raw file contents in context (older ones are dropped to save tokens) — note the " +
                "finding and move on, re-reading specific lines later only if needed."
        val schema =
            """{"type":"object","properties":{"text":{"type":"string","description":"A concise finding/decision/next-step, with file:line where relevant."}},"required":["text"]}"""
        return ToolSpec(NOTE, description, schema)
    }

    /** The `load_tools` meta-tool spec, advertised by the agent loop so the model can pull groups in. */
    fun loaderSpec(): ToolSpec {
        val catalog = GROUP_SUMMARY.entries.joinToString("\n") { "  - ${it.key}: ${it.value}" }
        val description =
            "Load an extra tool group into this session when (and only when) you need it. Heavier tools " +
                "are not advertised upfront to save context; call this first, then use the group's tools. " +
                "Available groups:\n$catalog"
        val enum = ON_DEMAND.keys.joinToString(",") { "\"$it\"" }
        val schema =
            """{"type":"object","properties":{"group":{"type":"string","enum":[$enum],"description":"Which tool group to load"}},"required":["group"]}"""
        return ToolSpec(LOAD_TOOLS, description, schema)
    }
}
