package com.github.saeldrit.geai.agent

import com.github.saeldrit.geai.bundle.ContextBundler
import com.github.saeldrit.geai.context.ContextCompressor
import com.github.saeldrit.geai.cost.UsageFormat
import com.github.saeldrit.geai.graph.GeaiGraphStore
import com.github.saeldrit.geai.llm.ChatMessage
import com.github.saeldrit.geai.llm.ChatRequest
import com.github.saeldrit.geai.llm.ContentBlock
import com.github.saeldrit.geai.llm.LlmClient
import com.github.saeldrit.geai.llm.LlmClientFactory
import com.github.saeldrit.geai.llm.LlmException
import com.github.saeldrit.geai.llm.StopReason
import com.github.saeldrit.geai.llm.TokenUsage
import com.github.saeldrit.geai.llm.ToolSpec
import com.github.saeldrit.geai.settings.GeaiSettingsState
import com.github.saeldrit.geai.settings.GeaiSettings
import com.github.saeldrit.geai.settings.loopModel
import com.github.saeldrit.geai.settings.transcriptWindow
import com.github.saeldrit.geai.tools.GeaiToolset
import com.github.saeldrit.geai.tools.ToolArgException
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolRegistry
import com.github.saeldrit.geai.tools.ToolResult
import com.github.saeldrit.geai.tools.debug.DebuggerSupport
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project

/**
 * Tuning that differs between the user-facing main loop and a delegated sub-agent. A sub-agent runs
 * on a tight budget with a read-only toolset and no progressive groups / delegation, so a big task
 * can be fanned out into many bounded, ISOLATED sub-tasks without the orchestrator's context (or its
 * cost) growing without bound — each sub-task returns only a compact finding.
 */
data class LoopProfile(
    val isSubAgent: Boolean,
    val maxIterations: Int,
    val maxTurnTokens: Int,
) {
    companion object {
        /**
         * Pure anti-runaway backstop for the user-facing loop — NOT a work limit. The loop runs
         * CONTINUOUSLY until the model is done (no more tool calls), gets stuck (the duplicate-call
         * guard), or is cancelled; it compacts the context to keep going rather than stopping. This
         * high ceiling only caps a pathological non-repeating runaway. Internal — never a tunable knob.
         */
        const val DEFAULT_MAIN_ITERATIONS = 500

        /** Main loop: high anti-runaway backstop, no cumulative-cost terminal (maxTurnTokens is unused
         *  for the main loop — only sub-agents are token-bounded). It compacts context and works on. */
        val MAIN = LoopProfile(isSubAgent = false, maxIterations = DEFAULT_MAIN_ITERATIONS, maxTurnTokens = 0)

        /** Main loop with an explicit iteration cap — used by the benchmark to bound unattended runs. */
        fun main(maxIterations: Int) = LoopProfile(false, maxIterations, 0)

        fun sub(maxIterations: Int, maxTurnTokens: Int) = LoopProfile(true, maxIterations, maxTurnTokens)
    }
}

/**
 * The agentic control loop: send the transcript to the model, run any requested tools, append the
 * results, and repeat until the model answers with no further tool calls (or limits/cancellation).
 * Runs on a background worker; emits [AgentEvent]s and never touches Swing directly.
 */
class AgentLoop(
    private val project: Project,
    private val registry: ToolRegistry,
    private val profile: LoopProfile = LoopProfile.MAIN,
) {

    fun run(session: AgentSession, userText: String, listener: AgentListener, indicator: ProgressIndicator) {
        session.messages.add(ChatMessage.user(userText))
        listener.onEvent(AgentEvent.UserMessage(userText))

        val client = try {
            LlmClientFactory.create()
        } catch (e: LlmException) {
            listener.onEvent(AgentEvent.Error(e.message ?: "Geai is not configured."))
            return
        }

        val settings = GeaiSettings.getInstance().state
        val systemPrompt = SystemPrompt.build(project)
        // A leading /<cmd> selects a MODE: pre-load its tool group (no load_tools round-trip) and steer
        // this turn via a focused directive, folded into the volatile suffix below.
        val command = SlashCommands.parse(userText)
        // Per-turn volatile suffix: the GRACE context bundle plus any mode directive. Kept SEPARATE from
        // the (cacheable) doctrine above so the stable prompt keeps hitting the prompt cache across turns
        // — only this suffix is re-sent (the clients place it AFTER the cache breakpoint).
        val bundleSuffix: String = run {
            val bundle: String = if (settings.graceEnabled) {
                val store = GeaiGraphStore.getInstance(project)
                if (store.graph().nodes.isEmpty()) {
                    // Do NOT block the turn on a full-project PSI reindex (seconds-to-minutes on a large
                    // project — the old inline reindex was the dominant first-turn stall). Build it in the
                    // background so it's ready NEXT turn; this turn runs without the bundle and the model
                    // navigates with find_symbol/search, which it would do anyway.
                    store.ensureBuiltInBackground()
                    ""
                } else {
                    // Kept COMPACT: without prompt caching (e.g. qwen via OpenRouter) it is paid every turn.
                    val b = try {
                        ContextBundler.build(project, userText, emptyList(), maxNodes = 10, hops = 2, charBudget = 4_000)
                    } catch (e: Exception) {
                        null
                    }
                    if (b != null && b.text.isNotBlank()) "<context_bundle>\n${b.text}\n</context_bundle>" else ""
                }
            } else {
                ""
            }
            listOfNotNull(
                bundle.takeIf { it.isNotBlank() },
                command.directive?.let { "<mode>\n$it\n</mode>" },
            ).joinToString("\n\n")
        }
        val maxIterations = profile.maxIterations.coerceAtLeast(1)
        // Only a sub-agent carries a cumulative-cost terminal (it is a bounded, fanned-out unit). The
        // main loop runs continuously — it compacts the context and keeps working — so it has none.
        val subTokenBudget = if (profile.isSubAgent) profile.maxTurnTokens else 0

        try {
            var iteration = 0
            var turnUsage = TokenUsage.ZERO
            // Stuck-loop detection compares BOTH the call signature and the result signature of
            // consecutive steps: identical call + identical result = no progress (abort); identical call
            // with a NEW result = legitimate progress (e.g. debug_step walking the program) = keep going.
            var lastToolSignature: String? = null
            var lastResultSignature: String? = null
            // On-demand tool groups the model has pulled in this turn (progressive disclosure). Starts
            // empty: only CORE (+GRACE) + the load_tools meta-tool are advertised until the model asks —
            // EXCEPT we pre-seed the mode's group (e.g. /debug → debug) and, on a follow-up turn while a
            // debug session is live, the debug group automatically. Both save a load_tools round-trip.
            val activeGroups = linkedSetOf<String>()
            activeGroups.addAll(command.preloadGroups)
            if (!profile.isSubAgent && DebuggerSupport.hasActiveSession(project)) activeGroups.add("debug")
            var delegationCount = 0
            // Compaction summariser: folds the old transcript into a dense recap (keeps findings, not
            // a 400-char head). Its cost is billed to the turn. Created once; mutates the turn counters.
            val summarizer = summarizerFor(client, settings, indicator) { used ->
                turnUsage += used
                session.totalUsage += used
            }
            while (true) {
                if (indicator.isCanceled) {
                    listener.onEvent(AgentEvent.Cancelled())
                    return
                }
                // Safety backstop ONLY — not a work limit. The main loop runs until the model is done,
                // gets stuck (identical repeated calls, caught below), or the user cancels. This high
                // ceiling just stops a pathological non-repeating runaway; a real task never reaches it.
                if (iteration++ >= maxIterations) {
                    summarizeAndFinish(
                        "Safety stop: $maxIterations iterations without finishing — likely stuck. " +
                            "Summarizing progress so far; ask me to continue if it's genuinely unfinished.",
                        session, client, systemPrompt, bundleSuffix, settings, turnUsage, indicator, listener,
                    )
                    return
                }
                // A sub-agent is a bounded, fanned-out unit: it MUST stop at its token budget so the
                // orchestrator's cost stays predictable. The MAIN loop has NO such cumulative-cost
                // terminal — it compacts the context (below) and keeps working until the task is done.
                if (subTokenBudget > 0 && turnUsage.inputTokens + turnUsage.outputTokens >= subTokenBudget) {
                    summarizeAndFinish(
                        "Sub-agent reached its token budget ($subTokenBudget) — returning findings.",
                        session, client, systemPrompt, bundleSuffix, settings, turnUsage, indicator, listener,
                    )
                    return
                }

                // Continuous operation hinges on this: keep the STORED transcript within the working
                // window so a long turn never grows the context — or its per-iteration re-send cost —
                // without bound. Fold the old middle into a dense recap ONCE and drop stale raw tool
                // dumps, then write the result BACK into the session so the next iteration starts from
                // the compact form (the summariser does NOT re-run over the same middle every step).
                // The original task, the recent turns, and the separate scratchpad of findings are
                // always preserved. The volatile bundle stays in the system suffix (so the doctrine
                // keeps hitting the prompt cache) and the running notes go LAST as a trailing user
                // message — outside the persisted transcript — so neither invalidates the cached prefix.
                val compacted = ContextCompressor.compress(
                    session.messages,
                    settings.transcriptWindow(),
                    settings.maxTokens,
                    systemPrompt.length + bundleSuffix.length,
                    summarizer,
                )
                if (compacted !== session.messages) {
                    val foldedAway = session.messages.size - compacted.size
                    session.messages.clear()
                    session.messages.addAll(compacted)
                    if (foldedAway > 0) {
                        listener.onEvent(AgentEvent.Info("🗜 Folded $foldedAway earlier step(s) into a recap to keep the context lean — continuing."))
                    }
                }
                val outgoing = appendNotesAsTrailingUser(session.messages, session.scratchpad)
                val request = ChatRequest(
                    model = settings.loopModel(),
                    system = systemPrompt,
                    // Bundle is per-turn stable: lives in the system block AFTER the doctrine cache
                    // breakpoint, so the doctrine keeps caching across turns and the bundle caches
                    // across iterations within a turn (Anthropic) or via auto-prefix (OpenAI).
                    systemVolatileSuffix = bundleSuffix,
                    messages = outgoing,
                    tools = advertisedSpecs(settings, activeGroups),
                    maxTokens = settings.maxTokens,
                )

                listener.onEvent(AgentEvent.Thinking)
                val result = client.chat(request, indicator)
                session.totalUsage += result.usage
                turnUsage += result.usage
                session.messages.add(result.message)

                result.message.text.takeIf { it.isNotBlank() }?.let { listener.onEvent(AgentEvent.AssistantText(it)) }

                val toolUses = result.message.toolUses
                if (toolUses.isEmpty()) {
                    if (result.stopReason == StopReason.MAX_TOKENS) {
                        listener.onEvent(AgentEvent.Info("Response was truncated by the token limit; raise Max tokens in Settings | Tools | Geai."))
                    }
                    listener.onEvent(AgentEvent.Info(UsageFormat.summary(settings.loopModel(), turnUsage, session.totalUsage, settings.modelPrices)))
                    listener.onEvent(AgentEvent.Done(session.totalUsage))
                    return
                }

                // Every tool_use MUST get a matching tool_result, even on cancellation — otherwise the
                // persisted transcript is invalid and a resumed session is rejected by the provider.
                val toolResults = ArrayList<ContentBlock.ToolResult>(toolUses.size)
                var interrupted = false
                for (call in toolUses) {
                    if (interrupted || indicator.isCanceled) {
                        interrupted = true
                        toolResults.add(ContentBlock.ToolResult(call.id, "Skipped: turn was interrupted.", isError = true))
                        continue
                    }
                    listener.onEvent(AgentEvent.ToolStarted(call.name, call.inputJson))
                    val toolResult = when {
                        call.name == GeaiToolset.NOTE -> recordNote(call, session.scratchpad)
                        call.name == GeaiToolset.LOAD_TOOLS -> loadTools(call, activeGroups)
                        call.name == GeaiToolset.DELEGATE -> when {
                            profile.isSubAgent ->
                                ToolResult.error("Nested delegation is not allowed — do the analysis yourself and return your finding.")
                            delegationCount >= MAX_DELEGATIONS ->
                                ToolResult.error("Delegation limit ($MAX_DELEGATIONS) reached this turn — synthesize from the findings you have.")
                            else -> {
                                delegationCount++
                                val outcome = runDelegate(call, indicator, listener)
                                // Bill the sub-agent's spend to this turn so the budget reflects true cost.
                                turnUsage += outcome.usage
                                session.totalUsage += outcome.usage
                                outcome.result
                            }
                        }
                        else -> try {
                            executeTool(call, settings, indicator)
                        } catch (_: ProcessCanceledException) {
                            interrupted = true
                            ToolResult.error("Interrupted during '${call.name}'.")
                        }
                    }
                    listener.onEvent(AgentEvent.ToolFinished(call.name, toolResult))
                    toolResults.add(ContentBlock.ToolResult(call.id, toolResult.content, toolResult.isError))
                }
                session.messages.add(ChatMessage.toolResults(toolResults))
                if (interrupted) {
                    listener.onEvent(AgentEvent.Cancelled())
                    return
                }

                // Stuck-loop guard (checked AFTER execution so we can compare results). Abort only when
                // the model made the EXACT same call(s) AND got the EXACT same result(s) as the previous
                // step — genuine no-progress (broken round-trip / confused model). Identical calls with a
                // DIFFERENT result are real progress (debug_step advancing, await_pause polling) and pass.
                val callSignature = toolUses.joinToString("|") { "${it.name}(${it.inputJson})" }
                val resultSignature = toolResults.joinToString("|") { "${it.isError}:${it.content.take(400)}" }
                if (callSignature == lastToolSignature && resultSignature == lastResultSignature) {
                    listener.onEvent(AgentEvent.Error("Stopped: the model repeated the identical tool call(s) with no new result — likely stuck. Aborted to avoid wasting tokens."))
                    listener.onEvent(AgentEvent.Info(UsageFormat.summary(settings.loopModel(), turnUsage, session.totalUsage, settings.modelPrices)))
                    listener.onEvent(AgentEvent.Done(session.totalUsage))
                    return
                }
                lastToolSignature = callSignature
                lastResultSignature = resultSignature
            }
        } catch (_: ProcessCanceledException) {
            listener.onEvent(AgentEvent.Cancelled())
        } catch (e: LlmException) {
            listener.onEvent(AgentEvent.Error(e.message ?: "LLM request failed."))
        } catch (e: Exception) {
            listener.onEvent(AgentEvent.Error("Unexpected error: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    /**
     * A [ContextCompressor.Summarizer] backed by one cheap, tool-less LLM call — used to fold the old
     * transcript into a dense recap during compaction. [bill] records the call's token spend so it
     * counts toward the turn. Best-effort: on any failure it returns blank and compaction falls back
     * to truncation.
     */
    private fun summarizerFor(
        client: LlmClient,
        settings: GeaiSettingsState,
        indicator: ProgressIndicator,
        bill: (TokenUsage) -> Unit,
    ): ContextCompressor.Summarizer = ContextCompressor.Summarizer { segment ->
        runCatching {
            val request = ChatRequest(
                model = settings.loopModel(),
                system = SUMMARY_DOCTRINE,
                messages = listOf(ChatMessage.user(segment)),
                tools = emptyList(),
                maxTokens = SUMMARY_MAX_TOKENS,
            )
            val result = client.chat(request, indicator)
            bill(result.usage)
            result.message.text
        }.getOrDefault("")
    }

    /**
     * Turn a forced stop (iteration or token budget) into a usable result instead of nothing: one
     * final tool-LESS call asks the model to summarize findings and next steps. Cheap (no tools, a
     * single call) so the tokens already spent exploring are not wasted. The nudge is not persisted —
     * only the produced summary is — so a resumed session stays clean. Best-effort: any failure here
     * still ends the turn gracefully with the usage summary.
     */
    private fun summarizeAndFinish(
        reason: String,
        session: AgentSession,
        client: LlmClient,
        systemPrompt: String,
        bundleSuffix: String,
        settings: GeaiSettingsState,
        turnUsage: TokenUsage,
        indicator: ProgressIndicator,
        listener: AgentListener,
    ) {
        listener.onEvent(AgentEvent.Info(reason))
        var usage = turnUsage
        val summarizer = summarizerFor(client, settings, indicator) { used ->
            usage += used
            session.totalUsage += used
        }
        runCatching {
            val nudge = ChatMessage.user(
                "You've reached the work budget for this turn. Stop exploring and do NOT request tools. " +
                    "Summarize now, concisely: what you found (with file:line), your current conclusion, and the " +
                    "exact next steps still needed to finish.",
            )
            val outgoing = ContextCompressor.compress(
                session.messages, settings.transcriptWindow(), settings.maxTokens, systemPrompt.length + bundleSuffix.length, summarizer,
            ) + nudge
            val request = ChatRequest(
                model = settings.loopModel(),
                system = systemPrompt,
                systemVolatileSuffix = bundleSuffix,
                messages = outgoing,
                tools = emptyList(),
                maxTokens = settings.maxTokens,
            )
            val result = client.chat(request, indicator)
            session.totalUsage += result.usage
            usage += result.usage
            session.messages.add(result.message)
            result.message.text.takeIf { it.isNotBlank() }?.let { listener.onEvent(AgentEvent.AssistantText(it)) }
        }
        listener.onEvent(AgentEvent.Info(UsageFormat.summary(settings.loopModel(), usage, session.totalUsage, settings.modelPrices)))
        listener.onEvent(AgentEvent.Done(session.totalUsage))
    }

    /**
     * Tool schemas advertised this iteration. The main loop offers the lean progressive surface plus
     * the load_tools and delegate meta-tools; a sub-agent offers only its fixed read-only registry
     * (no progressive groups, no delegation — see [LoopProfile]).
     */
    private fun advertisedSpecs(settings: GeaiSettingsState, activeGroups: Set<String>): List<ToolSpec> =
        if (profile.isSubAgent) {
            registry.specs()
        } else {
            GeaiToolset.advertisedTools(settings.graceEnabled, activeGroups, settings.tieredRoutingEnabled).map { it.spec() } +
                GeaiToolset.loaderSpec() + GeaiToolset.delegateSpec() + GeaiToolset.noteSpec()
        }

    /** Append a finding to the session notes (the model's external working memory). */
    private fun recordNote(call: ContentBlock.ToolUse, scratchpad: MutableList<String>): ToolResult {
        val text = ToolArgs.parse(call.inputJson).stringOrNull("text")?.trim().orEmpty()
        if (text.isEmpty()) return ToolResult.error("note needs a non-empty 'text'.")
        scratchpad.add(text)
        return ToolResult.ok("Noted (${scratchpad.size} total). Build your final answer from your notes.")
    }

    /**
     * Render the running notes as a TRAILING user message appended to the outgoing list (not to the
     * persisted session.messages). Kept outside the cached system prefix so notes growing each
     * iteration do NOT invalidate the prompt cache — only this small tail is fresh input every turn.
     *
     * Soft cap: if the scratchpad exceeds [MAX_NOTES_RETAINED], only the most recent notes are shown
     * to the model with a one-line marker that older notes were folded away. Prevents unbounded growth
     * from inflating the trailing tail on very long multi-turn sessions.
     */
    private fun appendNotesAsTrailingUser(outgoing: List<ChatMessage>, scratchpad: List<String>): List<ChatMessage> {
        if (scratchpad.isEmpty()) return outgoing
        val visible = if (scratchpad.size <= MAX_NOTES_RETAINED) scratchpad else scratchpad.takeLast(MAX_NOTES_RETAINED)
        val dropped = scratchpad.size - visible.size
        val header = if (dropped > 0) "<your_notes> (showing the last ${visible.size} of ${scratchpad.size}; $dropped older notes folded)\n" else "<your_notes>\n"
        val notesText = header + visible.joinToString("\n") { "- $it" } + "\n</your_notes>"
        return outgoing + ChatMessage.user(notesText)
    }

    private data class SubOutcome(val result: ToolResult, val usage: TokenUsage)

    /** Collapse whitespace and cap — a compact one-line label for streamed sub-agent narration. */
    private fun oneLine(s: String): String = s.replace(Regex("\\s+"), " ").trim().take(140)

    /**
     * Run a delegated sub-task in a fresh, ISOLATED [AgentLoop] with a read-only toolset and a tight
     * budget. The sub-agent explores in its own throwaway session; only its final text comes back as
     * the tool result, so the orchestrator's transcript stays compact. Its token spend is returned so
     * the caller can bill it to the turn. Cancellation/errors inside the sub end it gracefully (its
     * own run() handles them) and we return whatever it produced.
     */
    private fun runDelegate(call: ContentBlock.ToolUse, indicator: ProgressIndicator, parentListener: AgentListener): SubOutcome {
        val args = ToolArgs.parse(call.inputJson)
        val task = args.stringOrNull("task")?.takeIf { it.isNotBlank() }
            ?: return SubOutcome(ToolResult.error("delegate needs a non-empty 'task'."), TokenUsage.ZERO)
        val hint = args.stringOrNull("hint")?.takeIf { it.isNotBlank() }
        val prompt = if (hint == null) task else "$task\n\nLeads/anchors to start from: $hint"

        val subSession = AgentSession()
        // The sub-agent's final answer is its last assistant text; keep only that for the RETURN — its
        // intermediate narration and tool churn never enter the orchestrator's context (session.messages).
        // Its progress IS streamed to the UI (parentListener) as info lines — UI-only, not context.
        val captured = StringBuilder()
        val subListener = AgentListener { event ->
            when (event) {
                is AgentEvent.AssistantText -> {
                    captured.setLength(0)
                    captured.append(event.text)
                    parentListener.onEvent(AgentEvent.Info("↳ [sub-agent] ${oneLine(event.text)}"))
                }

                is AgentEvent.ToolStarted ->
                    parentListener.onEvent(AgentEvent.Info("↳ [sub-agent] ${event.tool} ${oneLine(event.argsJson)}"))

                else -> Unit // suppress sub lifecycle (Done/UserMessage/Thinking/Cancelled/Error/…)
            }
        }
        runCatching {
            AgentLoop(project, ToolRegistry(GeaiToolset.delegateTools()), LoopProfile.sub(SUB_MAX_ITERATIONS, SUB_MAX_TURN_TOKENS))
                .run(subSession, prompt, subListener, indicator)
        }
        val text = captured.toString().ifBlank { "(the sub-agent returned no result)" }
        return SubOutcome(ToolResult.ok(text), subSession.totalUsage)
    }

    /**
     * Handle the `load_tools` meta-tool: pull an on-demand group into [activeGroups] so its schemas
     * are advertised from the next iteration onward. Handled here (not via the registry) because it
     * mutates loop state. Idempotent — re-loading a group is a no-op with a confirming message.
     */
    private fun loadTools(call: ContentBlock.ToolUse, activeGroups: MutableSet<String>): ToolResult {
        val group = ToolArgs.parse(call.inputJson).stringOrNull("group")
            ?: return ToolResult.error("load_tools needs a 'group' argument. Valid groups: ${GeaiToolset.groupNames().joinToString(", ")}.")
        if (!GeaiToolset.isGroup(group)) {
            return ToolResult.error("Unknown tool group '$group'. Valid groups: ${GeaiToolset.groupNames().joinToString(", ")}.")
        }
        val names = GeaiToolset.groupTools(group).joinToString(", ") { it.name }
        val fresh = activeGroups.add(group)
        return ToolResult.ok(
            if (fresh) "Loaded the '$group' group. Now available: $names. Call them directly."
            else "The '$group' group is already loaded. Available: $names.",
        )
    }

    private fun executeTool(
        call: ContentBlock.ToolUse,
        settings: GeaiSettingsState,
        indicator: ProgressIndicator,
    ): ToolResult {
        val tool = registry.find(call.name)
            ?: return ToolResult.error(
                "Unknown tool '${call.name}'. Available: ${registry.tools.joinToString(", ") { it.name }}",
            )
        if (tool.mutating) {
            if (!ApprovalPolicy.confirm(project, tool, call.inputJson)) {
                return ToolResult.error("User denied permission to run '${tool.name}'.")
            }
        }
        return try {
            tool.execute(ToolArgs.parse(call.inputJson), ToolContext(project, indicator))
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: ToolArgException) {
            ToolResult.error("Invalid arguments for '${tool.name}': ${e.message}")
        } catch (e: Exception) {
            ToolResult.error("Tool '${tool.name}' threw ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private companion object {
        /** Hard ceiling on delegations per turn — a backstop against a model that fans out endlessly. */
        const val MAX_DELEGATIONS = 16

        /** Soft cap on notes shown to the model. Older notes are folded with a one-line marker so the
         *  trailing tail stays compact on very long multi-turn sessions (the agent already has its
         *  final answer built from earlier notes; the cap protects the per-iteration payload). */
        const val MAX_NOTES_RETAINED = 50

        /** A delegated sub-agent is focused: a tight iteration cap and token budget keep each unit cheap.
         *  Kept small so several delegations in one step can't overshoot the parent's turn budget. */
        const val SUB_MAX_ITERATIONS = 8
        const val SUB_MAX_TURN_TOKENS = 25_000

        private const val SUMMARY_MAX_TOKENS = 1500
        private const val SUMMARY_DOCTRINE =
            "You compress an AI coding agent's transcript. Output a DENSE recap that preserves everything " +
                "needed to keep working: the original task; files, symbols and APIs examined WITH their " +
                "file:line locations; concrete findings and conclusions; decisions and edits already made; and " +
                "open questions / next steps. Drop raw file contents and chatter. Terse bullet points, no preamble."
    }
}
