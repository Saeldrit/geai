package com.github.saeldrit.geai.agent

import com.github.saeldrit.geai.bundle.ContextBundler
import com.github.saeldrit.geai.context.ContextCompressor
import com.github.saeldrit.geai.context.NoteEntry
import com.github.saeldrit.geai.context.NotePriority
import com.github.saeldrit.geai.context.ScratchpadManager
import com.github.saeldrit.geai.context.Skill
import com.github.saeldrit.geai.context.SkillStore
import com.github.saeldrit.geai.context.SemanticCompressor
import com.github.saeldrit.geai.cost.UsageFormat
import com.github.saeldrit.geai.llm.ChatMessage
import com.github.saeldrit.geai.llm.ChatRequest
import com.github.saeldrit.geai.llm.ContentBlock
import com.github.saeldrit.geai.llm.LlmClient
import com.github.saeldrit.geai.llm.LlmClientFactory
import com.github.saeldrit.geai.llm.LlmException
import com.github.saeldrit.geai.settings.LlmProvider
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
    // Trend tracking for context_status
    private var previousTokenEst: Int = 0
    // Bundle quality tracking
    private var lastBundleAtoms: Int = 0
    private var lastBundleDropped: Int = 0

    fun run(session: AgentSession, userText: String, listener: AgentListener, indicator: ProgressIndicator, attachments: List<Attachment> = emptyList()) {
        val settings = GeaiSettings.getInstance().state
        val modelSupportsVision = LlmProvider.modelSupportsVision(settings.model ?: "")

        val userContent = mutableListOf<ContentBlock>(ContentBlock.Text(userText))
        val imageAttachments = attachments.filter { it.isImage }
        val nonImageAttachments = attachments.filter { !it.isImage }

        if (imageAttachments.isNotEmpty() && !modelSupportsVision) {
            listener.onEvent(AgentEvent.Info("⚠ Модель ${settings.model} не поддерживает изображения — они будут проигнорированы. Выберите vision-модель для работы с картинками."))
        } else {
            imageAttachments.forEach { att ->
                userContent.add(ContentBlock.Image(att.base64Data, att.mediaType))
            }
        }
        nonImageAttachments.forEach { att ->
            userContent.add(ContentBlock.Text("[Attached file: ${att.name}]\n${String(java.util.Base64.getDecoder().decode(att.base64Data))}"))
        }
        session.messages.add(ChatMessage(Role.USER, userContent))
        listener.onEvent(AgentEvent.UserMessage(userText, attachments))

        // Mark the current task so the model always sees it as the last message, even after
        // aggressive compaction of old context.  The first message is no longer a sacred anchor
        // — it is summarized like any other old message.  activeTask (injected as the LAST
        // user message in the outgoing context) is the sole task anchor.
        session.activeTask = userText

        // Detect task switch: if the session already has significant context and the user
        // sends a different message, clean up stale scratchpad entries so old notes don't
        // conflict with the new task.
        if (session.messages.size > 10 && session.scratchpad.isNotEmpty()) {
            val stats = ScratchpadManager.cleanForNewTask(session.scratchpad)
            if (stats.totalCleaned > 0) {
                listener.onEvent(
                    AgentEvent.Info(
                        "🧹 Task switch: dropped ${stats.lowDropped} stale notes, " +
                            "summarized ${stats.normalSummarized} findings, " +
                            "kept ${stats.criticalKept} critical notes.",
                    ),
                )
            }
        }

        val client = clientOverride ?: try {
            LlmClientFactory.create()
        } catch (e: LlmException) {
            listener.onEvent(AgentEvent.Error(e.message ?: "Geai is not configured."))
            return
        }

        val systemPrompt = SystemPrompt.build(project)
        // A leading /<cmd> selects a MODE: pre-load its tool group and steer via a focused directive.
        val command = SlashCommands.parse(userText)
        // Per-turn volatile suffix (bundle + mode directive) — kept after the cache breakpoint so the
        // doctrine stays cacheable across turns.
        val rawBundleSuffix = buildBundle(project, userText, emptyList(), settings, command)
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
            var visionRetries = 0
            // On-demand tool groups (progressive disclosure). Pre-seeded with the mode's group and,
            // while a debug session is live, the debug group — saving a load_tools round-trip.
            val activeGroups = linkedSetOf<String>()
            activeGroups.addAll(command.preloadGroups)
            if (!profile.isSubAgent && DebuggerSupport.hasActiveSession(project)) activeGroups.add("debug")
            var delegationCount = 0
            var turnUsage = TokenUsage.ZERO
            var bundleRefreshIteration = 0
            var messagesAtBundleRefresh = 0
            var anchorsAtBundleRefresh = 0
            var compressionCount = 0
            // Adaptive kill-switch
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

                // Bundle refresh: first refresh after 5 iterations, then adaptive (10+ new messages OR 8+ iterations OR 3+ new anchors).
                if (settings.graceEnabled) {
                    val currentAnchors = session.scratchpad.count { it.anchor != null }
                    val shouldRefresh = if (bundleRefreshIteration == 0) {
                        iteration >= 5
                    } else {
                        val msgGrowth = session.messages.size - messagesAtBundleRefresh
                        val newAnchors = currentAnchors - anchorsAtBundleRefresh
                        msgGrowth >= 10 || iteration - bundleRefreshIteration >= 8 || newAnchors >= 3
                    }
                    if (shouldRefresh) {
                        val seeds = session.scratchpad.filter { it.anchor != null }.takeLast(5).mapNotNull { it.anchor }
                        runCatching {
                            val newBundle = buildBundle(project, session.activeTask.ifBlank { userText }, seeds, settings, command)
                            if (newBundle.isNotBlank()) {
                                val msgGrowth = session.messages.size - messagesAtBundleRefresh
                                bundleSuffix = newBundle
                                bundleRefreshIteration = iteration
                                messagesAtBundleRefresh = session.messages.size
                                anchorsAtBundleRefresh = currentAnchors
                                listener.onEvent(AgentEvent.Info("🔄 Context bundle refreshed (${seeds.size} anchors, $msgGrowth new messages, iteration $iteration)."))
                            }
                        } // silently ignore bundle refresh failures
                    }
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
                        activeTask = session.activeTask,
                    )
                }
                var compressionUsed = false
                if (!skipCompression && compacted !== session.messages) {
                    val foldedAway = session.messages.size - compacted.size
                    compressionUsed = foldedAway > 0
                    if (compressionUsed) compressionCount++
                    session.messages.clear()
                    session.messages.addAll(compacted)
                    if (foldedAway > 0) {
                        val compMetrics = ContextCompressor.lastMetrics
                        val metricsStr = if (compMetrics != null) " [${compMetrics.method}, ${(compMetrics.ratio * 100).toInt()}% retained, ${compMetrics.inputChars}→${compMetrics.outputChars} chars]" else ""
                        listener.onEvent(AgentEvent.Info("🗜 Folded $foldedAway earlier step(s) into a recap to keep the context lean — continuing.$metricsStr"))
                    }
                    // Extract user preferences detected during compression and persist as skills
                    val extractedPrefs = com.github.saeldrit.geai.context.SemanticCompressor.lastExtractedPreferences
                    if (extractedPrefs.isNotEmpty()) {
                        for (pref in extractedPrefs) {
                            val id = pref.lowercase().replace(Regex("[^a-z0-9\\s-]"), "").trim().split(Regex("\\s+")).take(5).joinToString("-").take(50)
                            if (id.isNotBlank() && session.scratchpad.none { it.text.contains(pref, ignoreCase = true) }) {
                                val result = SkillStore.getInstance(project).save(pref, "extracted")
                                if (result.hasConflicts && result.conflictDomain != null) {
                                    listener.onEvent(AgentEvent.Info("⚠ Skill '$id' conflicts with existing preference in '${result.conflictDomain}' domain. Consider removing the old one."))
                                }
                            }
                        }
                        com.github.saeldrit.geai.context.SemanticCompressor.lastExtractedPreferences = emptyList()
                    }
                }
                val compressionMs = System.currentTimeMillis() - compStart
                val outgoing = appendNotesAsTrailingUser(session.messages, session.scratchpad)
                // Inject activeTask as the VERY LAST message — after notes, after compression.
                // The model must never lose sight of the current task, especially after
                // aggressive compaction folded older context into a summary.
                val withTask = if (session.activeTask.isNotBlank()) {
                    outgoing + ChatMessage.user(
                        "[Current task — do NOT revisit any earlier request:]\n${session.activeTask}"
                    )
                } else {
                    outgoing
                }
                val request = ChatRequest(
                    model = settings.loopModel(),
                    system = systemPrompt,
                    // Bundle is per-turn stable — lives after the doctrine cache breakpoint.
                    systemVolatileSuffix = bundleSuffix,
                    messages = withTask,
                    tools = advertisedSpecs(settings, activeGroups),
                    maxTokens = settings.maxTokens,
                )

                listener.onEvent(AgentEvent.Thinking)
                val llmStart = System.currentTimeMillis()
                val contextChars = outgoing.sumOf { m: ChatMessage -> m.text.length + m.content.sumOf { b -> b.toString().length } }
                val result: com.github.saeldrit.geai.llm.ChatResult = try {
                    client.chatStream(request, indicator) { event ->
                        when (event) {
                            is com.github.saeldrit.geai.llm.StreamEvent.TextDelta ->
                                listener.onEvent(AgentEvent.AssistantTextDelta(event.text))
                            is com.github.saeldrit.geai.llm.StreamEvent.ThinkingDelta ->
                                listener.onEvent(AgentEvent.ReasoningDelta(event.text))
                            is com.github.saeldrit.geai.llm.StreamEvent.ToolUseStarted,
                            is com.github.saeldrit.geai.llm.StreamEvent.ToolUseInputDelta,
                            is com.github.saeldrit.geai.llm.StreamEvent.Done -> Unit
                        }
                    }
                } catch (e: LlmException) {
                    if (isVisionError(e.message ?: "") && stripImagesFromSession(session) && visionRetries++ < 1) {
                        listener.onEvent(AgentEvent.Info("⚠ Изображения удалены из сессии — повторяю запрос без них..."))
                        continue // retry the loop iteration with stripped session — at most once
                    }
                    throw e // not a vision error, nothing to strip, or already retried — propagate
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
                    delegationCount, turnUsage, interrupted, iteration,
                    project, settings, command, compressionCount, bundleRefreshIteration,
                    bundleSuffix,
                )
                interrupted = metaResults.interrupted
                delegationCount = metaResults.delegationCount
                turnUsage = metaResults.turnUsage
                toolResults.addAll(metaResults.results)
                if (metaResults.bundleOverride != null) bundleSuffix = metaResults.bundleOverride!!

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
                    session.scratchpad.add(NoteEntry("kb_lookup consistently returns nothing (empty knowledge store). Skip kb_lookup this session — just use find_files/search_text/resolve_ref directly.", NotePriority.LOW))
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
                            NoteEntry(
                                "Loop guard: you repeated a step whose result you ALREADY have. Do NOT issue that call " +
                                    "again — use the output you have and take a DIFFERENT next step (read specific NEW lines, " +
                                    "or make an edit_file change). Re-listing or re-reading the same thing is not progress.",
                                NotePriority.LOW,
                            ),
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
            // If the provider rejected the request because the model doesn't support images,
            // strip all image blocks from the session transcript so the user can retry without
            // the same error repeating forever.
            val msg = e.message ?: ""
            if (session.messages.any { it.content.any { c -> c is ContentBlock.Image } }
                && isVisionError(msg)) {
                stripImagesFromSession(session)
                listener.onEvent(AgentEvent.Error(
                    "⚠ Model doesn't support image input. The image has been removed from the session — " +
                        "please retry without attachments or switch to a vision-capable model " +
                        "(Claude, GPT-4o, Gemini). Details: $msg"
                ))
            } else {
                listener.onEvent(AgentEvent.Error(msg.ifBlank { "LLM request failed." }))
            }
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
                activeTask = session.activeTask,
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
                GeaiToolset.loaderSpec() + GeaiToolset.delegateSpec() + GeaiToolset.noteSpec() + GeaiToolset.requestContextSpec() + GeaiToolset.contextStatusSpec()
        }
        cachedSpecs = specs
        cachedSpecsKey = key
        return specs
    }

    private fun buildBundle(project: Project, query: String, seeds: List<String>, settings: GeaiSettingsState, cmd: SlashCommands.Parsed): String {
        val bundle: String = if (settings.graceEnabled) {
            val b = try { ContextBundler.build(project, query, seeds, maxNodes = 10, hops = 2, charBudget = 8_000) } catch (e: ProcessCanceledException) { throw e } catch (_: Exception) { null }
            if (b != null && b.nodeIds.isNotEmpty()) {
                lastBundleAtoms = b.resolved + b.rules + b.nodeIds.size
                lastBundleDropped = b.dropped
                "<context_bundle>\n${b.text}\n</context_bundle>"
            } else {
                lastBundleAtoms = 0
                lastBundleDropped = 0
                ""
            }
        } else ""
        return listOfNotNull(bundle.takeIf { it.isNotBlank() }, cmd.directive?.let { "<mode>\n$it\n</mode>" }).joinToString("\n\n")
    }

    private fun recordNote(call: ContentBlock.ToolUse, scratchpad: MutableList<NoteEntry>, iteration: Int): ToolResult {
        val args = ToolArgs.parse(call.inputJson)
        val text = args.stringOrNull("text")?.trim().orEmpty()
        if (text.isEmpty()) return ToolResult.error("note needs a non-empty 'text'.")
        val priority = args.stringOrNull("priority")?.uppercase()?.let {
            runCatching { NotePriority.valueOf(it) }.getOrNull()
        } ?: NotePriority.NORMAL
        val anchor = args.stringOrNull("anchor")?.trim()?.takeIf { it.isNotEmpty() }
        scratchpad.add(NoteEntry(text, priority, anchor, iteration))
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
            // Normalize read_file: strip start_line/end_line so that reading the same file with
            // different ranges produces the same fingerprint — otherwise the stuck-loop guard
            // never fires for an agent that varies its read ranges each iteration.
            h = h * 31 + normalizeForSignature(c.name, c.inputJson).hashCode()
        }
        for (r in results) {
            // Exclude note results — they contain "Noted (N total)" where N grows each call,
            // masking repeated tool patterns behind a changing scratchpad size.
            if (r.toolUseId.isNotBlank()) {
                val callerName = calls.firstOrNull { it.id == r.toolUseId }?.name
                if (callerName == GeaiToolset.NOTE) continue
            }
            h = h * 31 + r.isError.hashCode()
            h = h * 31 + r.content.hashCode()
        }
        return h
    }

    /** Strip volatile parameters from tool input JSON for stable fingerprinting. */
    private fun normalizeForSignature(toolName: String, inputJson: String): String {
        if (toolName != "read_file") return inputJson
        return inputJson
            .replace(Regex("""\s*"start_line"\s*:\s*\d+\s*,?"""), "")
            .replace(Regex("""\s*,?\s*"end_line"\s*:\s*\d+"""), "")
    }

    /**
     * Render running notes as a trailing user message, kept outside the cached system prefix so
     * note growth doesn't invalidate the prompt cache. Soft-caps at [MAX_NOTES_RETAINED].
     */
    private fun appendNotesAsTrailingUser(outgoing: List<ChatMessage>, scratchpad: List<NoteEntry>): List<ChatMessage> {
        if (scratchpad.isEmpty()) return outgoing
        val visible = retainNotes(scratchpad, MAX_NOTES_RETAINED)
        val dropped = scratchpad.size - visible.size
        val sorted = visible.sortedBy { it.priority.ordinal } // CRITICAL(0), NORMAL(1), LOW(2)
        val header = if (dropped > 0) "<your_notes> (showing ${visible.size} of ${scratchpad.size}; $dropped lower-priority/older notes folded)\n" else "<your_notes>\n"
        val notesText = header + sorted.joinToString("\n") { formatNote(it) } + "\n</your_notes>"
        return outgoing + ChatMessage.user(notesText)
    }

    private fun formatNote(note: NoteEntry): String {
        val prefix = when (note.priority) {
            NotePriority.CRITICAL -> "- [!]"
            NotePriority.NORMAL -> "-"
            NotePriority.LOW -> "- [-]"
        }
        val anchorPart = if (note.anchor != null) " (${note.anchor})" else ""
        return "$prefix$anchorPart ${note.text}"
    }

    /**
     * Retain up to [limit] notes, preferring CRITICAL > NORMAL > LOW.
     * Within the same priority, older notes are dropped first.
     * CRITICAL notes are never dropped.
     */
    private fun retainNotes(notes: List<NoteEntry>, limit: Int): List<NoteEntry> {
        if (notes.size <= limit) return notes
        val critical = notes.filter { it.priority == NotePriority.CRITICAL }
        val normal = notes.filter { it.priority == NotePriority.NORMAL }
        val low = notes.filter { it.priority == NotePriority.LOW }
        val result = mutableListOf<NoteEntry>()
        result.addAll(critical) // always keep all CRITICAL
        val remaining = limit - result.size
        if (remaining <= 0) return result
        if (normal.size <= remaining) {
            result.addAll(normal)
            val lowSlots = remaining - normal.size
            result.addAll(low.takeLast(lowSlots))
        } else {
            result.addAll(normal.takeLast(remaining))
        }
        return result
    }

    private data class SubOutcome(val result: ToolResult, val usage: TokenUsage)

    /** Compact one-line label for streamed sub-agent narration. */
    private fun oneLine(s: String): String = s.replace(Regex("\\s+"), " ").trim().take(140)

    /**
     * Run a delegated sub-task in a fresh, isolated [AgentLoop] with a read-only toolset and a
     * tight budget. Only the final text comes back as the tool result, keeping the orchestrator's
     * transcript compact. Token spend is returned so the caller can bill it to the turn.
     */
    private fun runDelegate(call: ContentBlock.ToolUse, indicator: ProgressIndicator, parentListener: AgentListener, session: AgentSession, bundleSuffix: String = ""): SubOutcome {
        val args = ToolArgs.parse(call.inputJson)
        val task = args.stringOrNull("task")?.takeIf { it.isNotBlank() }
            ?: return SubOutcome(ToolResult.error("delegate needs a non-empty 'task'."), TokenUsage.ZERO)
        val hint = args.stringOrNull("hint")?.takeIf { it.isNotBlank() }
        // Pass parent's notes sorted by priority (CRITICAL first, then NORMAL, skip LOW)
        val parentNotes = session.scratchpad
            .filter { it.priority != NotePriority.LOW }
            .sortedByDescending { it.priority.ordinal }
            .take(10)
        val prompt = buildString {
            append(task)
            if (hint != null) append("\n\nLeads/anchors to start from: $hint")
            // Pass parent's active task for context
            if (session.activeTask.isNotBlank() && session.activeTask != task) {
                append("\n\n<parent_task>\n${session.activeTask}\n</parent_task>")
            }
            // Pass context bundle if available — sub-agent can start from pre-gathered context
            if (bundleSuffix.isNotBlank()) {
                append("\n\n$bundleSuffix")
            }
            // Pass parent's findings with priority markers
            if (parentNotes.isNotEmpty()) {
                append("\n\n<parent_findings>\n")
                parentNotes.forEach { note ->
                    val prefix = when (note.priority) {
                        NotePriority.CRITICAL -> "[!] "
                        NotePriority.NORMAL -> ""
                        NotePriority.LOW -> "[-] "
                    }
                    val anchor = note.anchor?.let { " ($it)" } ?: ""
                    append("- $prefix${note.text}$anchor\n")
                }
                append("</parent_findings>")
            }
            // Structured output hint
            append("\n\nReturn your findings as a structured list: for each finding, include the file:line location, what you observed, and its significance.")
            if (length > 12000) setLength(12000)
        }

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
    private val META_TOOL_NAMES = setOf(GeaiToolset.NOTE, GeaiToolset.LOAD_TOOLS, GeaiToolset.DELEGATE, GeaiToolset.REQUEST_CONTEXT, GeaiToolset.CONTEXT_STATUS)

    /**
     * `note`/`load_tools`/`context_status` run in PARALLEL: they mutate independent state (scratchpad vs activeGroups)
     * or are read-only (context_status), so there is no race. Events are emitted in original call order to keep the UI
     * sequence correct. `delegate` calls are deferred and run CONCURRENTLY — each is an isolated read-only sub-agent
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
        iteration: Int,
        project: Project,
        settings: GeaiSettingsState,
        command: SlashCommands.Parsed,
        compressionCount: Int = 0,
        bundleRefreshIteration: Int = 0,
        bundleSuffix: String = "",
    ): MetaResult {
        var metaInterrupted = interrupted
        var metaDelegationCount = delegationCount
        var metaTurnUsage = turnUsage
        var metaBundleOverride: String? = null
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
                GeaiToolset.NOTE -> results[i] = emitMeta(call, recordNote(call, session.scratchpad, iteration), listener)
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
                GeaiToolset.REQUEST_CONTEXT -> {
                    val query = ToolArgs.parse(call.inputJson).stringOrNull("query")?.trim().orEmpty()
                    if (query.isBlank()) {
                        results[i] = emitMeta(call, ToolResult.error("request_context needs a 'query'."), listener)
                    } else {
                        metaBundleOverride = buildBundle(project, query, session.scratchpad.filter { it.anchor != null }.takeLast(5).mapNotNull { it.anchor }, settings, command)
                        results[i] = emitMeta(call, if (metaBundleOverride.isNotBlank()) ToolResult.ok("Context bundle refreshed for: $query") else ToolResult.error("No context found for: $query"), listener)
                    }
                }
                GeaiToolset.CONTEXT_STATUS -> {
                    val tokenEst = ContextCompressor.estimatedTokens(session.messages)
                    val budget = settings.transcriptWindow()
                    val pct = if (budget > 0) (tokenEst * 100 / budget) else 0
                    val critical = session.scratchpad.count { it.priority == NotePriority.CRITICAL }
                    val normal = session.scratchpad.count { it.priority == NotePriority.NORMAL }
                    val low = session.scratchpad.count { it.priority == NotePriority.LOW }
                    val metrics = ContextCompressor.lastMetrics
                    val trend = tokenEst - previousTokenEst
                    val trendStr = when {
                        trend > 500 -> "↑ growing (+$trend)"
                        trend < -500 -> "↓ shrinking ($trend)"
                        else -> "→ stable"
                    }
                    previousTokenEst = tokenEst
                    val report = buildString {
                        appendLine("Transcript: ~$tokenEst tokens ($pct% of $budget budget) $trendStr")
                        appendLine("Messages: ${session.messages.size}")
                        appendLine("Scratchpad: ${session.scratchpad.size} notes ($critical CRITICAL, $normal NORMAL, $low LOW)")
                        if (session.activeTask.isNotBlank()) appendLine("Active task: \"${session.activeTask.take(100)}\"")
                        appendLine("Bundle refreshes: ${iteration - bundleRefreshIteration} iterations since last")
                        if (lastBundleAtoms > 0) {
                            val fillRate = if (lastBundleAtoms + lastBundleDropped > 0) (lastBundleAtoms * 100 / (lastBundleAtoms + lastBundleDropped)) else 0
                            appendLine("Bundle quality: $lastBundleAtoms atoms included, $lastBundleDropped dropped ($fillRate% fill rate)")
                        }
                        appendLine("Compression count: $compressionCount")
                        if (metrics != null) {
                            appendLine("Last compression: ${metrics.method} (${(metrics.ratio * 100).toInt()}% retained, ${metrics.inputChars}→${metrics.outputChars} chars)")
                        }
                        val qualityReport = com.github.saeldrit.geai.context.SemanticCompressor.lastQualityReport
                        if (qualityReport != null) {
                            appendLine("Last summary quality: ${qualityReport.score}/100 (${qualityReport.findingsWithLocation}/${qualityReport.findingCount} findings with location)")
                        }
                        // Actionable recommendations
                        appendLine()
                        appendLine("Recommendations:")
                        when {
                            pct >= 90 -> appendLine("- URGENT: Context nearly full. Run /compact or start a new session to avoid losing context.")
                            pct >= 75 -> appendLine("- Context is getting large. Consider /compact to free space for more work.")
                            pct >= 50 && compressionCount == 0 -> appendLine("- Context growing. First compression will trigger automatically at ~70% budget.")
                        }
                        if (critical > 10) appendLine("- Many CRITICAL notes ($critical). Review if all are still relevant to the current task.")
                        if (session.scratchpad.size > 30) appendLine("- Scratchpad has ${session.scratchpad.size} notes. Old LOW notes will be evicted automatically.")
                        if (iteration - bundleRefreshIteration > 15) appendLine("- Bundle is stale (${iteration - bundleRefreshIteration} iterations since refresh). Consider request_context to refresh it.")
                        if (qualityReport != null && qualityReport.score < 50) appendLine("- Last summary was low quality (${qualityReport.score}/100). Context may be losing important details.")
                        if (pct < 50 && compressionCount == 0) appendLine("- Context is healthy. No action needed.")
                    }
                    results[i] = emitMeta(call, ToolResult.ok(report), listener)
                }
                else -> error("unreachable: $call is not a meta-tool")
            }
        }

        // Pass 2 (parallel): run the reserved delegates concurrently. Cap threads so a big fan-out does
        // not slam the LLM endpoint with too many simultaneous requests.
        if (delegateIndices.isNotEmpty()) {
            val futures = delegateIndices.map { idx ->
                idx to SHARED_POOL.submit(Callable { runDelegateTimed(calls[idx], indicator, listener, session, bundleSuffix) })
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
        return MetaResult(metaInterrupted, metaDelegationCount, metaTurnUsage, results.filterNotNull(), metaBundleOverride)
    }

    /** Emit ToolFinished for an instant meta-tool (note/load_tools) and wrap its result for the transcript. */
    private fun emitMeta(call: ContentBlock.ToolUse, result: ToolResult, listener: AgentListener): ContentBlock.ToolResult {
        listener.onEvent(AgentEvent.ToolFinished(call.name, result, call.id))
        return ContentBlock.ToolResult(call.id, result.content, result.isError)
    }

    /** Run one delegate on a worker thread, timing it and emitting ToolFinished (+ slow-call note). */
    private fun runDelegateTimed(call: ContentBlock.ToolUse, indicator: ProgressIndicator, listener: AgentListener, session: AgentSession, bundleSuffix: String = ""): SubOutcome {
        val t0 = System.nanoTime()
        val outcome = if (indicator.isCanceled) {
            SubOutcome(ToolResult.error("Skipped: turn was interrupted."), TokenUsage.ZERO)
        } else {
            runDelegate(call, indicator, listener, session, bundleSuffix)
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
        val bundleOverride: String? = null,
    )

    private companion object {
        const val MAX_DELEGATIONS = 16

        /** Stuck-loop guard: how many recent step fingerprints to remember (enough to catch short cycles). */
        const val STUCK_RING_SIZE = 10

        /** Soft cap on notes shown to the model — older notes fold with a marker. */
        const val MAX_NOTES_RETAINED = 50

        /** Sub-agent budget: tight iteration cap and token limit keep each unit cheap. */
        const val SUB_MAX_ITERATIONS = 8
        const val SUB_MAX_TURN_TOKENS = 25_000

        private const val SUMMARY_MAX_TOKENS = 2000
        private val SUMMARY_DOCTRINE = com.github.saeldrit.geai.context.SemanticCompressor.DOCTRINE

        /** Keywords that signal a provider rejected the request because images aren't supported. */
        private val VISION_ERROR_KEYWORDS = listOf(
            "image input", "image_url", "vision", "visual",
            "does not support image", "doesn't support image",
            "no endpoints found",
        )

        /** Heuristic: does [errorMessage] look like the provider rejected image content? */
        private fun isVisionError(errorMessage: String): Boolean {
            val lower = errorMessage.lowercase()
            return VISION_ERROR_KEYWORDS.any { lower.contains(it) }
        }

        /** Remove all [ContentBlock.Image] blocks from every message in [session]. Returns true if any images were stripped. */
        private fun stripImagesFromSession(session: AgentSession): Boolean {
            var stripped = false
            for (msg in session.messages) {
                val images = msg.content.filterIsInstance<ContentBlock.Image>()
                if (images.isNotEmpty()) {
                    stripped = true
                    val cleaned = msg.content.filter { it !is ContentBlock.Image }.toMutableList()
                    if (cleaned.isEmpty()) cleaned.add(ContentBlock.Text("(image removed — not supported by model)"))
                    // ChatMessage is a data class — replace in-place via the mutable list
                    val idx = session.messages.indexOf(msg)
                    session.messages[idx] = msg.copy(content = cleaned)
                }
            }
            return stripped
        }

        /** Shared pool for parallel tool execution — reused across turns to avoid repeated thread creation. */
        private val SHARED_POOL: ExecutorService = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        )
    }
}
