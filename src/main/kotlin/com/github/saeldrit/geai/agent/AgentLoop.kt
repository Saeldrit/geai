package com.github.saeldrit.geai.agent

import com.github.saeldrit.geai.bundle.ContextBundler
import com.github.saeldrit.geai.context.ContextCompressor
import com.github.saeldrit.geai.cost.UsageFormat
import com.github.saeldrit.geai.llm.ChatMessage
import com.github.saeldrit.geai.llm.ChatRequest
import com.github.saeldrit.geai.llm.ContentBlock
import com.github.saeldrit.geai.llm.LlmClient
import com.github.saeldrit.geai.llm.LlmClientFactory
import com.github.saeldrit.geai.llm.LlmException
import com.github.saeldrit.geai.llm.Role
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
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
        const val DEFAULT_MAIN_ITERATIONS = 50

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
    /** Test seam: inject a scripted client. Production leaves this null → the real provider client. */
    private val clientOverride: LlmClient? = null,
) {

    // [PERF] Cache advertised tool specs — rebuilt only when activeGroups/grace/tiered change.
    private var cachedSpecs: List<ToolSpec>? = null
    private var cachedSpecsKey: Int = 0

    fun run(session: AgentSession, userText: String, listener: AgentListener, indicator: ProgressIndicator, attachments: List<Attachment> = emptyList()) {
        val userContent = mutableListOf<ContentBlock>(ContentBlock.Text(userText))
        attachments.filter { it.isImage }.forEach { att ->
            userContent.add(ContentBlock.Image(att.base64Data, att.mediaType))
        }
        attachments.filter { !it.isImage }.forEach { att ->
            userContent.add(ContentBlock.Text("[Attached file: ${att.name}]\n${String(java.util.Base64.getDecoder().decode(att.base64Data))}"))
        }
        session.messages.add(ChatMessage(Role.USER, userContent))
        listener.onEvent(AgentEvent.UserMessage(userText, attachments))

        val client = clientOverride ?: try {
            LlmClientFactory.create()
        } catch (e: LlmException) {
            listener.onEvent(AgentEvent.Error(e.message ?: "Geai is not configured."))
            return
        }

        val settings = GeaiSettings.getInstance().state
        val systemPrompt = SystemPrompt.build(project)
        // A leading /<cmd> selects a MODE: pre-load its tool group and steer via a focused directive.
        val command = SlashCommands.parse(userText)
        // Per-turn volatile suffix (bundle + mode directive) — kept after the cache breakpoint so the
        // doctrine stays cacheable across turns.
        val rawBundleSuffix: String = run {
            val bundle: String = if (settings.graceEnabled) {
                // Built LIVE from PSI (no materialized graph, no cold-start wait). Inject only when it
                // actually found seeds — skip the "No seeds matched" stub.
                val b = try {
                    ContextBundler.build(project, userText, emptyList(), maxNodes = 10, hops = 2, charBudget = 4_000)
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (b != null && b.nodeIds.isNotEmpty()) "<context_bundle>\n${b.text}\n</context_bundle>" else ""
            } else {
                ""
            }
            listOfNotNull(
                bundle.takeIf { it.isNotBlank() },
                command.directive?.let { "<mode>\n$it\n</mode>" },
            ).joinToString("\n\n")
        }
        val maxIterations = profile.maxIterations.coerceAtLeast(1)
        // Sub-agents have a cumulative-cost terminal; the main loop compacts context and runs until done.
        val subTokenBudget = if (profile.isSubAgent) profile.maxTurnTokens else 0

        val metrics = AgentMetrics()
        try {
            var iteration = 0

            // Stuck-loop detection: fingerprint each step (calls + their FULL results) and keep a small
            // ring of recent fingerprints. A recurring fingerprint = no progress (a consecutive repeat OR
            // an A/B/A/B cycle) → nudge once, then abort if it persists.
            val recentStepSignatures = ArrayDeque<Long>()
            var noProgressHits = 0
            // On-demand tool groups (progressive disclosure). Pre-seeded with the mode's group and,
            // while a debug session is live, the debug group — saving a load_tools round-trip.
            val activeGroups = linkedSetOf<String>()
            activeGroups.addAll(command.preloadGroups)
            if (!profile.isSubAgent && DebuggerSupport.hasActiveSession(project)) activeGroups.add("debug")
            var delegationCount = 0
            var turnUsage = TokenUsage.ZERO
            // Adaptive kill-switch: drop the escalate_author routing hint if unused after 3 iterations.
            var escalationUsedThisTurn = false
            var iterationsWithoutEscalation = 0
            // Adaptive kill-switch: suppress kb_lookup after 3 empty results.
            var emptyKbLookups = 0
            var kbSuppressed = false
            var bundleSuffix = rawBundleSuffix
            // LLM summariser for context compaction — bills tokens to the turn.
            val summarizer = summarizerFor(client, settings, indicator) { used ->
                turnUsage += used
                session.totalUsage += used
            }
            while (true) {
                if (indicator.isCanceled) {
                    listener.onEvent(AgentEvent.Cancelled())
                    return
                }
                if (iteration++ >= maxIterations) {
                    summarizeAndFinish(
                        "Safety stop: $maxIterations iterations without finishing — likely stuck. " +
                            "Summarizing progress so far; ask me to continue if it's genuinely unfinished.",
                        session, client, systemPrompt, bundleSuffix, settings, turnUsage, indicator, listener,
                    )
                    return
                }
                // A sub-agent is a bounded, fanned-out unit: it MUST stop at its token budget.
                if (subTokenBudget > 0 && turnUsage.inputTokens + turnUsage.outputTokens >= subTokenBudget) {
                    summarizeAndFinish(
                        "Sub-agent reached its token budget ($subTokenBudget) — returning findings.",
                        session, client, systemPrompt, bundleSuffix, settings, turnUsage, indicator, listener,
                    )
                    return
                }

                // Skip LLM-based context compression during debugging: the transcript stays short.
                val skipCompression = activeGroups.contains("debug")
                val compStart = System.currentTimeMillis()
                val messagesBeforeComp = session.messages.size
                val compacted = if (skipCompression) {
                    session.messages
                } else {
                    ContextCompressor.compress(
                        session.messages,
                        settings.transcriptWindow(),
                        settings.maxTokens,
                        systemPrompt.length + bundleSuffix.length,
                        summarizer,
                    )
                }
                var compressionUsed = false
                if (!skipCompression && compacted !== session.messages) {
                    val foldedAway = session.messages.size - compacted.size
                    compressionUsed = foldedAway > 0
                    session.messages.clear()
                    session.messages.addAll(compacted)
                    if (foldedAway > 0) {
                        listener.onEvent(AgentEvent.Info("🗜 Folded $foldedAway earlier step(s) into a recap to keep the context lean — continuing."))
                    }
                }
                val compressionMs = System.currentTimeMillis() - compStart
                val outgoing = appendNotesAsTrailingUser(session.messages, session.scratchpad)
                val request = ChatRequest(
                    model = settings.loopModel(),
                    system = systemPrompt,
                    // Bundle is per-turn stable — lives after the doctrine cache breakpoint.
                    systemVolatileSuffix = bundleSuffix,
                    messages = outgoing,
                    tools = advertisedSpecs(settings, activeGroups),
                    maxTokens = settings.maxTokens,
                )

                listener.onEvent(AgentEvent.Thinking)
                val llmStart = System.currentTimeMillis()
                val contextChars = outgoing.sumOf { m: ChatMessage -> m.text.length + m.content.sumOf { b -> b.toString().length } }
                val result = client.chatStream(request, indicator) { event ->
                    when (event) {
                        // Stream text/thinking as deltas to the UI; full message emitted once as AssistantText.
                        is com.github.saeldrit.geai.llm.StreamEvent.TextDelta ->
                            listener.onEvent(AgentEvent.AssistantTextDelta(event.text))
                        is com.github.saeldrit.geai.llm.StreamEvent.ThinkingDelta ->
                            listener.onEvent(AgentEvent.ReasoningDelta(event.text))
                        is com.github.saeldrit.geai.llm.StreamEvent.ToolUseStarted,
                        is com.github.saeldrit.geai.llm.StreamEvent.ToolUseInputDelta,
                        is com.github.saeldrit.geai.llm.StreamEvent.Done -> Unit
                    }
                }
                val llmMs = System.currentTimeMillis() - llmStart
                session.totalUsage += result.usage
                turnUsage += result.usage
                session.messages.add(result.message)
                // Finalise the streamed message: emit complete text so the UI re-renders as markdown.
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

                // Every tool_use MUST get a matching tool_result — otherwise transcripts break on resume.
                val toolResults = ArrayList<ContentBlock.ToolResult>(toolUses.size)
                var interrupted = false
                // Parallel tool execution
                // note/load_tools mutate loop state and run sequentially; delegate fans out in parallel
                // (handled inside executeMetaTools). Regular tools are independent and run in parallel.
                val metaCalls = toolUses.filter { it.name in META_TOOL_NAMES }
                val regularCalls = toolUses.filter { it.name !in META_TOOL_NAMES }

                // Emit ToolStarted for all calls upfront so the UI shows everything
                toolUses.forEach { listener.onEvent(AgentEvent.ToolStarted(it.name, it.inputJson, it.id)) }

                // 1. Meta tools: sequential, must finish before regular tools
                val toolStart = System.currentTimeMillis()
                val metaResults = executeMetaTools(
                    metaCalls, session, activeGroups, indicator, listener,
                    delegationCount, turnUsage, interrupted,
                )
                interrupted = metaResults.interrupted
                delegationCount = metaResults.delegationCount
                turnUsage = metaResults.turnUsage
                toolResults.addAll(metaResults.results)

                // 2. Regular tools. Independent read-only tools run in PARALLEL. Mutating tools (one
                // approval at a time, deterministic write order, no same-file clobber) AND interactive
                // tools (ask_user — two at once would stack modal dialogs) run SEQUENTIALLY.
                if (regularCalls.isNotEmpty() && !interrupted) {
                    val (serialCalls, parallelCalls) = regularCalls.partition {
                        val t = registry.find(it.name); t?.mutating == true || t?.interactive == true
                    }
                    toolResults.addAll(executeToolsParallel(parallelCalls, settings, indicator, listener))
                    toolResults.addAll(executeToolsSequential(serialCalls, settings, indicator, listener))
                }

                session.messages.add(ChatMessage.toolResults(toolResults))
                val toolMs = System.currentTimeMillis() - toolStart
                // Record per-turn metrics
                metrics.record(AgentMetrics.TurnMetrics(
                    turnIndex = iteration,
                    llmCallMs = llmMs,
                    toolExecutionMs = toolMs,
                    compressionMs = compressionMs,
                    turnTotalMs = System.currentTimeMillis() - compStart,
                    messagesBefore = messagesBeforeComp,
                    messagesAfter = session.messages.size,
                    toolCallCount = toolUses.size,
                    parallelToolCalls = regularCalls.count { t -> val tt = registry.find(t.name); tt?.mutating != true && tt?.interactive != true },
                    sequentialToolCalls = regularCalls.size - regularCalls.count { t -> val tt = registry.find(t.name); tt?.mutating != true && tt?.interactive != true },
                    contextChars = contextChars,
                    inputTokens = result.usage.inputTokens,
                    outputTokens = result.usage.outputTokens,
                    compressed = compressionUsed,
                    summarized = false,
                ))
                thisLogger().info("[metrics] turn=${iteration} llm=${llmMs}ms tools=${toolMs}ms comp=${compressionMs}ms total=${System.currentTimeMillis() - compStart}ms ctx=${contextChars}ch inTok=${result.usage.inputTokens} outTok=${result.usage.outputTokens} toolCalls=${toolUses.size} compressed=$compressionUsed")

                if (interrupted) {
                    listener.onEvent(AgentEvent.Cancelled())
                    return
                }

                // Adaptive kill-switch: drop the escalate_author routing hint if unused after 3 iterations.
                for (call in toolUses) {
                    if (call.name == GeaiToolset.ESCALATE) escalationUsedThisTurn = true
                }
                if (!escalationUsedThisTurn && settings.tieredRoutingEnabled) {
                    iterationsWithoutEscalation++
                    // Only fire when there is actually a <mode> directive to drop — otherwise the no-op
                    // replace leaves bundleSuffix == rawBundleSuffix and this would re-fire every iteration.
                    if (iterationsWithoutEscalation >= 3 && bundleSuffix == rawBundleSuffix && rawBundleSuffix.contains("<mode>")) {
                        bundleSuffix = rawBundleSuffix.replace(Regex("<mode>.*?</mode>"), "").trim()
                        listener.onEvent(AgentEvent.Info("🛣 Dropped the mode directive (model isn't escalating) to save context."))
                    }
                } else if (escalationUsedThisTurn) {
                    iterationsWithoutEscalation = 0
                }
                // Track kb_lookup: after 3 empty returns, suppress it. Look up the result by tool_use id —
                // NOT by positional index: toolResults is [meta]+[regular] while toolUses is interleaved,
                // so indexing by position reads the wrong block once any meta tool shares the turn.
                val resultById = toolResults.associateBy { it.toolUseId }
                for (call in toolUses) {
                    if (call.name == "kb_lookup") {
                        val result = resultById[call.id]
                        if (result != null && result.content.contains("No matching knowledge yet", ignoreCase = true)) {
                            emptyKbLookups++
                        }
                    }
                }
                if (emptyKbLookups >= 3 && !kbSuppressed) {
                    kbSuppressed = true
                    session.scratchpad.add("kb_lookup consistently returns nothing (empty knowledge store). Skip kb_lookup this session — just use find_files/search_text/resolve_ref directly.")
                }

                // Stuck-loop guard: a step whose fingerprint already appears in the recent ring produced
                // nothing the model didn't already have (a consecutive repeat OR an A/B/A/B cycle). Hash the
                // FULL result — a 400-char head misses thrashing that differs only deep in a big result.
                // EXEMPT all-poll steps: debugger wait/step/state legitimately return the SAME result while
                // runtime state advances, so the doctrine-ordered debug loop must not self-abort as "stuck".
                val pollOnly = toolUses.isNotEmpty() && toolUses.all { registry.find(it.name)?.idempotentPoll == true }
                if (!pollOnly) {
                    val stepSignature = stepSignature(toolUses, toolResults)
                    if (recentStepSignatures.contains(stepSignature)) {
                        noProgressHits++
                        if (noProgressHits >= 2) {
                            listener.onEvent(AgentEvent.Error("Stopped: the model kept repeating tool call(s) with no new result even after a nudge — likely stuck. Aborted to avoid wasting tokens."))
                            listener.onEvent(AgentEvent.Info(UsageFormat.summary(settings.loopModel(), turnUsage, session.totalUsage, settings.modelPrices)))
                            listener.onEvent(AgentEvent.Done(session.totalUsage))
                            return
                        }
                        session.scratchpad.add(
                            "Loop guard: you repeated a step whose result you ALREADY have. Do NOT issue that call " +
                                "again — use the output you have and take a DIFFERENT next step (read specific NEW lines, " +
                                "or make an edit_file change). Re-listing or re-reading the same thing is not progress.",
                        )
                        listener.onEvent(AgentEvent.Info("↻ Repeated step with no new result — nudging the model to move on (aborts if it persists)."))
                    } else {
                        noProgressHits = 0
                    }
                    recentStepSignatures.addLast(stepSignature)
                    while (recentStepSignatures.size > STUCK_RING_SIZE) recentStepSignatures.removeFirst()
                }
            }
        } catch (_: ProcessCanceledException) {
            listener.onEvent(AgentEvent.Cancelled())
        } catch (e: LlmException) {
            listener.onEvent(AgentEvent.Error(e.message ?: "LLM request failed."))
        } catch (e: Exception) {
            listener.onEvent(AgentEvent.Error("Unexpected error: ${e.message ?: e.javaClass.simpleName}"))
        } finally {
            thisLogger().info("[metrics]\n${metrics.summary()}")
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
     * Turn a forced stop (iteration or token budget) into a usable result: one final tool-LESS call
     * asks the model to summarize findings and next steps. Best-effort.
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

    /** [PERF] Cached tool specs — rebuilt only when activeGroups/grace/tiered change. */
    private fun advertisedSpecs(settings: GeaiSettingsState, activeGroups: Set<String>): List<ToolSpec> {
        val key = activeGroups.hashCode() xor (if (settings.graceEnabled) 1 else 0) xor (if (settings.tieredRoutingEnabled) 2 else 0)
        if (cachedSpecs != null && key == cachedSpecsKey) return cachedSpecs!!
        val specs = if (profile.isSubAgent) {
            registry.specs()
        } else {
            GeaiToolset.advertisedTools(settings.graceEnabled, activeGroups, settings.tieredRoutingEnabled).map { it.spec() } +
                GeaiToolset.loaderSpec() + GeaiToolset.delegateSpec() + GeaiToolset.noteSpec()
        }
        cachedSpecs = specs
        cachedSpecsKey = key
        return specs
    }

    private fun recordNote(call: ContentBlock.ToolUse, scratchpad: MutableList<String>): ToolResult {
        val text = ToolArgs.parse(call.inputJson).stringOrNull("text")?.trim().orEmpty()
        if (text.isEmpty()) return ToolResult.error("note needs a non-empty 'text'.")
        scratchpad.add(text)
        return ToolResult.ok("Noted (${scratchpad.size} total). Build your final answer from your notes.")
    }

    /**
     * A fast fingerprint of one step: the tool calls (name + args) plus their FULL results. The stuck-loop
     * guard treats a recurring fingerprint as "no progress" — the step gave back something already seen.
     * Long (not Int) to keep collisions negligible across a turn.
     */
    private fun stepSignature(calls: List<ContentBlock.ToolUse>, results: List<ContentBlock.ToolResult>): Long {
        var h = 1125899906842597L
        for (c in calls) {
            h = h * 31 + c.name.hashCode()
            h = h * 31 + c.inputJson.hashCode()
        }
        for (r in results) {
            h = h * 31 + r.isError.hashCode()
            h = h * 31 + r.content.hashCode()
        }
        return h
    }

    /**
     * Render running notes as a trailing user message, kept outside the cached system prefix so
     * note growth doesn't invalidate the prompt cache. Soft-caps at [MAX_NOTES_RETAINED].
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

    /** Compact one-line label for streamed sub-agent narration. */
    private fun oneLine(s: String): String = s.replace(Regex("\\s+"), " ").trim().take(140)

    /**
     * Run a delegated sub-task in a fresh, isolated [AgentLoop] with a read-only toolset and a
     * tight budget. Only the final text comes back as the tool result, keeping the orchestrator's
     * transcript compact. Token spend is returned so the caller can bill it to the turn.
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

    /** Handle `load_tools`: activate an on-demand tool group. Idempotent. */
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

    /**
     * Meta-tools mutate loop state, so `note`/`load_tools` run sequentially. `delegate` is the
     * exception: each spawns an isolated, read-only sub-agent and is the slowest meta-tool by far, so
     * delegates run in PARALLEL (see [executeMetaTools]).
     */
    private val META_TOOL_NAMES = setOf(GeaiToolset.NOTE, GeaiToolset.LOAD_TOOLS, GeaiToolset.DELEGATE)

    /**
     * `note`/`load_tools` run in PARALLEL: they mutate independent state (scratchpad vs activeGroups),
     * so there is no race. Events are emitted in original call order to keep the UI sequence correct.
     * `delegate` calls are deferred and run CONCURRENTLY — each is an isolated read-only sub-agent
     * that can't touch loop state or the others, so fanning N audits out costs the slowest sub-agent,
     * not the sum. Usage is summed after the join, single-threaded — no locks needed.
     */
    private fun executeMetaTools(
        calls: List<ContentBlock.ToolUse>,
        session: AgentSession,
        activeGroups: MutableSet<String>,
        indicator: ProgressIndicator,
        listener: AgentListener,
        delegationCount: Int,
        turnUsage: TokenUsage,
        interrupted: Boolean,
    ): MetaResult {
        var metaInterrupted = interrupted
        var metaDelegationCount = delegationCount
        var metaTurnUsage = turnUsage
        val results = arrayOfNulls<ContentBlock.ToolResult>(calls.size)

        // Pass 1 (sequential): run note/load_tools inline. They mutate the SHARED scratchpad / activeGroups
        // (two note calls in one turn both append to the same list), so they must not race — and they are
        // instant, so there is nothing to gain from parallelism. Only RESERVE delegate slots here
        // (honouring MAX_DELEGATIONS in call order); the reserved delegates run together in pass 2.
        val delegateIndices = mutableListOf<Int>()
        for ((i, call) in calls.withIndex()) {
            if (metaInterrupted || indicator.isCanceled) {
                metaInterrupted = true
                results[i] = ContentBlock.ToolResult(call.id, "Skipped: turn was interrupted.", isError = true)
                continue
            }
            when (call.name) {
                GeaiToolset.NOTE -> results[i] = emitMeta(call, recordNote(call, session.scratchpad), listener)
                GeaiToolset.LOAD_TOOLS -> results[i] = emitMeta(call, loadTools(call, activeGroups), listener)
                GeaiToolset.DELEGATE ->
                    if (metaDelegationCount >= MAX_DELEGATIONS) {
                        results[i] = emitMeta(
                            call,
                            ToolResult.error("Delegation limit ($MAX_DELEGATIONS) reached this turn — synthesize from the findings you have."),
                            listener,
                        )
                    } else {
                        metaDelegationCount++
                        delegateIndices.add(i)
                    }
                else -> error("unreachable: $call is not a meta-tool")
            }
        }

        // Pass 2 (parallel): run the reserved delegates concurrently. Cap threads so a big fan-out does
        // not slam the LLM endpoint with too many simultaneous requests.
        if (delegateIndices.isNotEmpty()) {
            val futures = delegateIndices.map { idx ->
                idx to SHARED_POOL.submit(Callable { runDelegateTimed(calls[idx], indicator, listener) })
            }
            for ((idx, future) in futures) {
                try {
                    val outcome = future.get()
                    metaTurnUsage += outcome.usage
                    session.totalUsage += outcome.usage
                    results[idx] = ContentBlock.ToolResult(calls[idx].id, outcome.result.content, outcome.result.isError)
                } catch (e: Exception) {
                    // Each delegate is independent — a failure in one must not break the others or
                    // leave a missing tool_result (which would corrupt the transcript on resume).
                    val msg = if (e is java.util.concurrent.ExecutionException) {
                        e.cause?.message ?: e.message
                    } else e.message
                    thisLogger().warn("Delegate failed: $msg", e)
                    results[idx] = ContentBlock.ToolResult(calls[idx].id, "Delegate failed: $msg", isError = true)
                }
            }
        }
        return MetaResult(metaInterrupted, metaDelegationCount, metaTurnUsage, results.filterNotNull())
    }

    /** Emit ToolFinished for an instant meta-tool (note/load_tools) and wrap its result for the transcript. */
    private fun emitMeta(call: ContentBlock.ToolUse, result: ToolResult, listener: AgentListener): ContentBlock.ToolResult {
        listener.onEvent(AgentEvent.ToolFinished(call.name, result, call.id))
        return ContentBlock.ToolResult(call.id, result.content, result.isError)
    }

    /** Run one delegate on a worker thread, timing it and emitting ToolFinished (+ slow-call note). */
    private fun runDelegateTimed(call: ContentBlock.ToolUse, indicator: ProgressIndicator, listener: AgentListener): SubOutcome {
        val t0 = System.nanoTime()
        val outcome = if (indicator.isCanceled) {
            SubOutcome(ToolResult.error("Skipped: turn was interrupted."), TokenUsage.ZERO)
        } else {
            runDelegate(call, indicator, listener)
        }
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        listener.onEvent(AgentEvent.ToolFinished(call.name, outcome.result, call.id))
        if (elapsedMs > 500) listener.onEvent(AgentEvent.Info("⚡ ${call.name} completed in ${elapsedMs}ms"))
        return outcome
    }

    /** Execute mutating tools one at a time — serializes approvals and writes; no race, no stacked dialogs. */
    private fun executeToolsSequential(
        calls: List<ContentBlock.ToolUse>,
        settings: GeaiSettingsState,
        indicator: ProgressIndicator,
        listener: AgentListener,
    ): List<ContentBlock.ToolResult> = calls.map { call ->
        if (indicator.isCanceled) {
            ContentBlock.ToolResult(call.id, "Skipped: turn was interrupted.", isError = true)
        } else {
            val t0 = System.nanoTime()
            val toolResult = try {
                executeTool(call, settings, indicator)
            } catch (_: ProcessCanceledException) {
                ToolResult.error("Interrupted during '${call.name}'.")
            } catch (e: Exception) {
                thisLogger().warn("Tool '${call.name}' threw unexpectedly: ${e.message}", e)
                ToolResult.error("Tool '${call.name}' failed: ${e.message}")
            }
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000
            listener.onEvent(AgentEvent.ToolFinished(call.name, toolResult, call.id))
            if (elapsedMs > 500) listener.onEvent(AgentEvent.Info("⚡ ${call.name} completed in ${elapsedMs}ms"))
            ContentBlock.ToolResult(call.id, toolResult.content, toolResult.isError)
        }
    }

    /** Execute regular tools in parallel using a shared fork-join pool. */
    private fun executeToolsParallel(
        calls: List<ContentBlock.ToolUse>,
        settings: GeaiSettingsState,
        indicator: ProgressIndicator,
        listener: AgentListener,
    ): List<ContentBlock.ToolResult> {
        if (calls.isEmpty()) return emptyList()
        val futures: List<Pair<ContentBlock.ToolUse, java.util.concurrent.Future<ContentBlock.ToolResult>>> =
            calls.map { call ->
                call to SHARED_POOL.submit(Callable {
                    if (indicator.isCanceled) {
                        ContentBlock.ToolResult(call.id, "Skipped: turn was interrupted.", isError = true)
                    } else {
                        val t0 = System.nanoTime()
                        val toolResult = try {
                            executeTool(call, settings, indicator)
                        } catch (_: ProcessCanceledException) {
                            ToolResult.error("Interrupted during '${call.name}'.")
                        } catch (e: Exception) {
                            thisLogger().warn("Tool '${call.name}' threw unexpectedly: ${e.message}", e)
                            ToolResult.error("Tool '${call.name}' failed: ${e.message}")
                        }
                        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
                        listener.onEvent(AgentEvent.ToolFinished(call.name, toolResult, call.id))
                        if (elapsedMs > 500) {
                            listener.onEvent(AgentEvent.Info("⚡ ${call.name} completed in ${elapsedMs}ms"))
                        }
                        ContentBlock.ToolResult(call.id, toolResult.content, toolResult.isError)
                    }
                })
            }
        // Per-future get: one failure must not discard the others (which would leave missing
        // tool_results and corrupt the transcript on resume).
        return futures.map { (call, future) ->
            try {
                future.get()
            } catch (e: Exception) {
                thisLogger().warn("Parallel tool '${call.name}' execution failed: ${e.message}", e)
                ContentBlock.ToolResult(call.id, "Tool execution failed: ${e.message}", isError = true)
            }
        }
    }

    private data class MetaResult(
        val interrupted: Boolean,
        val delegationCount: Int,
        val turnUsage: TokenUsage,
        val results: List<ContentBlock.ToolResult>,
    )

    private companion object {
        const val MAX_DELEGATIONS = 16

        /** Stuck-loop guard: how many recent step fingerprints to remember (enough to catch short cycles). */
        const val STUCK_RING_SIZE = 5

        /** Soft cap on notes shown to the model — older notes fold with a marker. */
        const val MAX_NOTES_RETAINED = 50

        /** Sub-agent budget: tight iteration cap and token limit keep each unit cheap. */
        const val SUB_MAX_ITERATIONS = 8
        const val SUB_MAX_TURN_TOKENS = 25_000

        private const val SUMMARY_MAX_TOKENS = 1500
        private const val SUMMARY_DOCTRINE =
            "You compress an AI coding agent's transcript. Output a DENSE recap that preserves everything " +
                "needed to keep working: the original task; files, symbols and APIs examined WITH their " +
                "file:line locations; concrete findings and conclusions; decisions and edits already made; and " +
                "open questions / next steps. Drop raw file contents and chatter. Terse bullet points, no preamble."

        /** Shared pool for parallel tool execution — reused across turns to avoid repeated thread creation. */
        private val SHARED_POOL: ExecutorService = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        )
    }
}
