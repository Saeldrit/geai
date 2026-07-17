package com.github.saeldrit.geai.tools

import com.github.saeldrit.geai.llm.ToolSpec
import com.github.saeldrit.geai.tools.debug.AwaitPauseTool
import com.github.saeldrit.geai.tools.debug.BreakOnExceptionTool
import com.github.saeldrit.geai.tools.debug.DebugDumpObjectTool
import com.github.saeldrit.geai.tools.debug.FailureAutopsyTool
import com.github.saeldrit.geai.tools.debug.DebugEvaluateTool
import com.github.saeldrit.geai.tools.debug.DebugStateTool
import com.github.saeldrit.geai.tools.debug.DebugStepTool
import com.github.saeldrit.geai.tools.debug.DebugTraceTool
import com.github.saeldrit.geai.tools.debug.DebugVariablesTool
import com.github.saeldrit.geai.tools.debug.ListBreakpointsTool
import com.github.saeldrit.geai.tools.debug.RemoveBreakpointTool
import com.github.saeldrit.geai.tools.debug.SetBreakpointTool
import com.github.saeldrit.geai.tools.debug.SetTracepointTool
import com.github.saeldrit.geai.tools.debug.StartDebugTool
import com.github.saeldrit.geai.tools.debug.TraceLogTool
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
import com.github.saeldrit.geai.tools.grace.ResolveRefTool
import com.github.saeldrit.geai.tools.grace.SpecListTool
import com.github.saeldrit.geai.tools.grace.SpecLookupTool
import com.github.saeldrit.geai.tools.grace.SpecRecordTool
import com.github.saeldrit.geai.tools.grace.SpecValidateTool
import com.github.saeldrit.geai.tools.psi.DiagnosticsTool
import com.github.saeldrit.geai.tools.psi.FindImplementationsTool
import com.github.saeldrit.geai.tools.psi.FindSymbolTool
import com.github.saeldrit.geai.tools.psi.FindUsagesTool
import com.github.saeldrit.geai.tools.knowledge.KbForgetTool
import com.github.saeldrit.geai.tools.knowledge.KbLookupTool
import com.github.saeldrit.geai.tools.knowledge.KbRecordTool
import com.github.saeldrit.geai.tools.knowledge.SkillTool
import com.github.saeldrit.geai.tools.project.ProjectOverviewTool
import com.github.saeldrit.geai.tools.selfmod.SelfInfoTool
import com.github.saeldrit.geai.tools.selfmod.SelfPatchTool
import com.github.saeldrit.geai.tools.interaction.AskUserTool
import com.github.saeldrit.geai.tools.system.ReadRunOutputTool
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
    const val REQUEST_CONTEXT = "request_context"
    const val CONTEXT_STATUS = "context_status"
    const val ESCALATE = "escalate_author"

    private val CORE: List<AgentTool> = listOf(
        KbLookupTool,
        KbRecordTool,
        KbForgetTool,
        SkillTool,
        AskUserTool,
        ProjectOverviewTool,
        FindFilesTool,
        ListFilesTool,
        ReadFileTool,
        SearchTextTool,
        WriteFileTool,
        EditFileTool,
    )

    private val GRACE: List<AgentTool> = listOf(
        ContextBundleTool,
        ResolveRefTool,
        GraphQueryTool,
        GraphNeighborsTool,
        FindSymbolTool,
        FindUsagesTool,
        FindImplementationsTool,
        DiagnosticsTool,
    )

    private val ON_DEMAND: Map<String, List<AgentTool>> = linkedMapOf(
        "debug" to listOf(
            SetBreakpointTool,
            SetTracepointTool,
            TraceLogTool,
            BreakOnExceptionTool,
            FailureAutopsyTool,
            RemoveBreakpointTool,
            ListBreakpointsTool,
            DebugStateTool,
            StartDebugTool,
            AwaitPauseTool,
            DebugStepTool,
            DebugTraceTool,
            DebugVariablesTool,
            DebugEvaluateTool,
            DebugDumpObjectTool,
            ReadRunOutputTool,
        ),
        "run" to listOf(RunCommandTool, ReadRunOutputTool),
        "specs" to listOf(SpecListTool, SpecLookupTool, SpecValidateTool, SpecRecordTool),
        "selfmod" to listOf(SelfInfoTool, SelfPatchTool),
    )

    private val GROUP_SUMMARY: Map<String, String> = linkedMapOf(
        "debug" to "set/remove/list breakpoints (with conditions, temporary), set TRACEPOINTS that auto-record an expression on every hit without stopping (trace_log reads the captured flow), break_on_exception to pause at a throw, failure_autopsy for a one-call stack+locals post-mortem of the current pause, start a debug session, await a pause (returns locals), STEP (over/into/out/resume) yourself, inspect variables, evaluate expressions (batch), dump a whole object tree, read_run_output for the app's console",
        "run" to "run_command — run shell/build/test/git commands in the project; read_run_output — read the console output (stdout/stderr) of Run/Debug configurations launched in the IDE",
        "specs" to "spec_list/spec_lookup (read the Category-A rules that govern code), spec_validate (drift-check specs against live code), spec_record (author/update a rule as an anchor, never a copy)",
        "selfmod" to "self_info, self_patch — inspect and modify geai's own source",
    )

    private val DELEGATE_TOOLS: List<AgentTool> = listOf(
        KbLookupTool,
        ProjectOverviewTool,
        FindFilesTool,
        ListFilesTool,
        ReadFileTool,
        SearchTextTool,
        ResolveRefTool,
        ContextBundleTool,
        GraphQueryTool,
        GraphNeighborsTool,
        FindSymbolTool,
        FindUsagesTool,
        FindImplementationsTool,
        DiagnosticsTool,
    )

    private fun base(graceEnabled: Boolean): List<AgentTool> = if (graceEnabled) GRACE + CORE else CORE

    /** Full catalog (every tool) — used by the registry for execution and by the Claude Code engine.
     *  escalate_author is always present for EXECUTION; it is only ADVERTISED under tiered routing.
     *  distinctBy: a tool may live in several groups (read_run_output is in both debug and run). */
    fun all(): List<AgentTool> =
        (base(GeaiSettings.getInstance().state.graceEnabled) + ON_DEMAND.values.flatten() + EscalateAuthorTool)
            .distinctBy { it.name }

    fun registry(): ToolRegistry = ToolRegistry(all())

    /**
     * Tools advertised to the model right now: CORE (+GRACE) plus any on-demand groups already loaded.
     * Under [tieredRouting] (and GRACE enabled) the navigator can hand authoring to the strong tier, so
     * `escalate_author` is advertised; with a single model (author == navigator) it would be dead weight.
     * distinctBy: loading both debug and run must not advertise shared tools twice.
     */
    fun advertisedTools(graceEnabled: Boolean, activeGroups: Set<String>, tieredRouting: Boolean = false): List<AgentTool> {
        val tools = base(graceEnabled) + activeGroups.flatMap { ON_DEMAND[it] ?: emptyList() }
        return (if (tieredRouting && graceEnabled) tools + EscalateAuthorTool else tools).distinctBy { it.name }
    }

    fun isGroup(name: String): Boolean = ON_DEMAND.containsKey(name)

    fun groupTools(name: String): List<AgentTool> = ON_DEMAND[name] ?: emptyList()

    /** Names of the on-demand groups, in catalog order (e.g. for doctrine text and error messages). */
    fun groupNames(): Set<String> = ON_DEMAND.keys

    fun delegateTools(): List<AgentTool> = DELEGATE_TOOLS

    /** The `delegate` meta-tool spec, advertised by the main loop so it can fan work out to sub-agents. */
    fun delegateSpec(): ToolSpec {
        val description =
            "Delegate a focused, self-contained sub-task to a fresh sub-agent that has its OWN clean " +
                "context. Use it for anything that would otherwise flood your context with file contents — " +
                "reviewing/auditing/tracing a flow across many files. The sub-agent navigates and reads on " +
                "its own (read-only) and returns ONLY a compact result. It CANNOT edit, run, or change " +
                "anything — never delegate a task that must modify code; do those yourself with " +
                "edit_file/write_file. Spawn ONE per independent unit (a " +
                "file, a module, a question), then synthesize their results. State exactly what to investigate " +
                "and what to return (findings with file:line — not raw code). The report is hard-capped at " +
                "~6000 chars: ask for aggregates and the top findings, never exhaustive per-occurrence listings. " +
                "Not for mechanical enumeration a script could do — script those via run_command instead."
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
            """{"type":"object","properties":{"text":{"type":"string","description":"A concise finding/decision/next-step, with file:line where relevant."},"priority":{"type":"string","enum":["CRITICAL","NORMAL","LOW"],"description":"CRITICAL = must survive all compression, NORMAL = default, LOW = ephemeral observation"},"anchor":{"type":"string","description":"Optional file:line reference, e.g. 'src/main/Foo.kt:42'"}},"required":["text"]}"""
        return ToolSpec(NOTE, description, schema)
    }

    /** The `request_context` meta-tool spec: let the model pull a fresh context bundle for a code area. */
    fun requestContextSpec(): ToolSpec {
        val description = "Request a fresh context bundle for a specific code area. Use when your investigation leads to a module not covered by the current bundle."
        val schema = """{"type":"object","properties":{"query":{"type":"string","description":"Code area to explore (class name, module, feature)"}},"required":["query"]}"""
        return ToolSpec(REQUEST_CONTEXT, description, schema)
    }

    fun contextStatusSpec(): ToolSpec {
        val description = "Show current context state: token usage, note counts, active task, compression stats."
        val schema = """{"type":"object","properties":{}}"""
        return ToolSpec(CONTEXT_STATUS, description, schema)
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
