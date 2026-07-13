package com.github.saeldrit.geai.agent

import com.github.saeldrit.geai.bundle.ContextBundler
import com.github.saeldrit.geai.context.ContextCompressor
import com.github.saeldrit.geai.context.NoteEntry
import com.github.saeldrit.geai.context.NotePriority
import com.github.saeldrit.geai.context.ScratchpadManager
import com.github.saeldrit.geai.context.SkillStore
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
import com.github.saeldrit.geai.settings.effectiveOutputReserve
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
    private val clientOverride: LlmClient? = null,
) {

    private var cachedSpecs: List<ToolSpec>? = null
    private var cachedSpecsKey: Int = 0
    private var previousTokenEst: Int = 0
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

        session.activeTask = userText

        val looksLikeNewTask = userText.trim().length >= 40
        if (looksLikeNewTask && session.messages.size > 10 && session.scratchpad.isNotEmpty()) {
            val stats = ScratchpadManager.cleanForNewTask(session.scratchpad)
            if (stats.totalCleaned > 0) {
                listener.onEvent(
                    AgentEvent.Info(
                        "🧹 Task switch: dropped ${stats.lowDropped} stale + ${stats.normalDropped} old notes, " +
                            "kept ${stats.criticalKept} critical and ${stats.normalKept} recent findings.",
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

        val systemPrompt = SystemPrompt.build(project, lean = profile.isSubAgent)
        val command = SlashCommands.parse(userText)
        val pastHint = if (!profile.isSubAgent && session.messages.size <= 1) {
            runCatching { com.github.saeldrit.geai.context.PastSessions.hint(project, userText, session.id) }.getOrDefault("")
        } else {
            ""
        }
        val rawBundleSuffix = listOf(buildBundle(project, userText, emptyList(), settings, command), pastHint)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        val maxIterations = profile.maxIterations.coerceAtLeast(1)
        val subTokenBudget = if (profile.isSubAgent) profile.maxTurnTokens else 0

        val metrics = AgentMetrics()
        try {
            var iteration = 0


            val recentStepSignatures = ArrayDeque<Long>()
            var noProgressHits = 0
            var visionRetries = 0
            var contextOverflowRetries = 0
            var maxTokensAutoContinues = 0
            val activeGroups = linkedSetOf<String>()
            activeGroups.addAll(command.preloadGroups)
            if (!profile.isSubAgent && DebuggerSupport.hasActiveSession(project)) activeGroups.add("debug")
            var delegationCount = 0
            var turnUsage = TokenUsage.ZERO
            var compressionCount = 0
            var bundleSuffix = rawBundleSuffix
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
                if (subTokenBudget > 0 && turnUsage.inputTokens + turnUsage.outputTokens >= subTokenBudget) {
                    summarizeAndFinish(
                        "Sub-agent reached its token budget ($subTokenBudget) — returning findings.",
                        session, client, systemPrompt, bundleSuffix, settings, turnUsage, indicator, listener,
                    )
                    return
                }

                val skipCompression = activeGroups.contains("debug")
                val compStart = System.currentTimeMillis()
                val messagesBeforeComp = session.messages.size
                val compacted = if (skipCompression) {
                    session.messages
                } else {
                    ContextCompressor.compress(
                        session.messages,
                        settings.transcriptWindow(),
                        settings.effectiveOutputReserve(),
                        systemPrompt.length + bundleSuffix.length,
                        summarizer,
                        activeTask = session.activeTask,
                    )
                }
                var compressionUsed = false
                if (!skipCompression && compacted !== session.messages) {
                    val foldedAway = session.messages.size - compacted.size
                    val compMetrics = ContextCompressor.lastMetrics
                    compressionUsed = compMetrics != null && compMetrics.method != "none"
                    if (compressionUsed) compressionCount++
                    session.messages.clear()
                    session.messages.addAll(compacted)
                    if (compressionUsed) {
                        val metricsStr = if (compMetrics != null) " [${compMetrics.method}, ${(compMetrics.ratio * 100).toInt()}% retained, ${compMetrics.inputChars}→${compMetrics.outputChars} chars]" else ""
                        listener.onEvent(AgentEvent.Info("🗜 Folded $foldedAway earlier step(s) into a summary — continuing.$metricsStr"))
                    }
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

                val request = ChatRequest(
                    model = settings.loopModel(),
                    system = systemPrompt,
                    systemVolatileSuffix = bundleSuffix,
                    messages = outgoing,
                    tools = advertisedSpecs(settings, activeGroups),
                    maxTokens = settings.maxTokens,
                )

                listener.onEvent(AgentEvent.Thinking)
                val llmStart = System.currentTimeMillis()
                val contextChars = outgoing.sumOf { m: ChatMessage ->
                    m.content.sumOf { b ->
                        when (b) {
                            is ContentBlock.Text -> b.text.length
                            is ContentBlock.ToolUse -> b.name.length + b.inputJson.length
                            is ContentBlock.ToolResult -> b.content.length
                            is ContentBlock.Image -> 0
                        }
                    }
                }
                val streamedFutures = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.Future<ContentBlock.ToolResult>>()
                val announcedToolIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
                val result: com.github.saeldrit.geai.llm.ChatResult = try {
                    client.chatStream(request, indicator) { event ->
                        when (event) {
                            is com.github.saeldrit.geai.llm.StreamEvent.TextDelta ->
                                listener.onEvent(AgentEvent.AssistantTextDelta(event.text))
                            is com.github.saeldrit.geai.llm.StreamEvent.ThinkingDelta ->
                                listener.onEvent(AgentEvent.ReasoningDelta(event.text))
                            is com.github.saeldrit.geai.llm.StreamEvent.ToolUseCompleted -> {
                                val tool = registry.find(event.name)
                                if (settings.streamToolExecution &&
                                    event.name !in META_TOOL_NAMES &&
                                    tool != null && !tool.mutating && !tool.interactive
                                ) {
                                    val call = ContentBlock.ToolUse(event.id, event.name, event.inputJson)
                                    if (announcedToolIds.add(call.id)) {
                                        listener.onEvent(AgentEvent.ToolStarted(call.name, call.inputJson, call.id))
                                    }
                                    streamedFutures[call.id] =
                                        SHARED_POOL.submit(Callable { runRegularTimed(call, settings, indicator, listener) })
                                }
                            }
                            is com.github.saeldrit.geai.llm.StreamEvent.ToolUseStarted,
                            is com.github.saeldrit.geai.llm.StreamEvent.ToolUseInputDelta,
                            is com.github.saeldrit.geai.llm.StreamEvent.Done -> Unit
                        }
                    }
                } catch (e: LlmException) {
                    streamedFutures.values.forEach { it.cancel(true) }
                    if (isVisionError(e.message ?: "") && stripImagesFromSession(session) && visionRetries++ < 1) {
                        listener.onEvent(AgentEvent.Info("⚠ Изображения удалены из сессии — повторяю запрос без них..."))
                        continue
                    }
                    if (isContextOverflowError(e.message ?: "") && contextOverflowRetries++ < 1) {
                        val forced = ContextCompressor.compress(
                            session.messages,
                            settings.transcriptWindow() / 2,
                            settings.effectiveOutputReserve(),
                            systemPrompt.length + bundleSuffix.length,
                            summarizer,
                            activeTask = session.activeTask,
                        )
                        if (forced !== session.messages) {
                            compressionCount++
                            session.messages.clear()
                            session.messages.addAll(forced)
                            listener.onEvent(AgentEvent.Info("⚠ Provider rejected the request as too long — context force-compacted, retrying. Consider lowering maxContextTokens in Settings | Tools | Geai for this model."))
                            continue
                        }
                    }
                    throw e
                }
                val llmMs = System.currentTimeMillis() - llmStart
                session.totalUsage += result.usage
                turnUsage += result.usage
                session.messages.add(result.message)
                result.message.text.takeIf { it.isNotBlank() }?.let { listener.onEvent(AgentEvent.AssistantText(it)) }

                val toolUses = result.message.toolUses
                if (toolUses.isEmpty()) {
                    if (result.stopReason == StopReason.MAX_TOKENS && maxTokensAutoContinues < 5) {
                        maxTokensAutoContinues++
                        listener.onEvent(AgentEvent.Info(
                            "⚠ Response was truncated (${maxTokensAutoContinues}/5 auto-continues) — asking the model to continue..."
                        ))
                        session.messages.add(ChatMessage.user("Continue exactly where you left off. Do not repeat anything."))
                        continue
                    }
                    if (result.stopReason == StopReason.MAX_TOKENS) {
                        listener.onEvent(AgentEvent.Info("Response was truncated by the token limit; raise Max tokens in Settings | Tools | Geai."))
                    }
                    listener.onEvent(AgentEvent.Info(UsageFormat.summary(settings.loopModel(), turnUsage, session.totalUsage, settings.modelPrices)))
                    listener.onEvent(AgentEvent.Done(session.totalUsage))
                    return
                }

                val toolResults = ArrayList<ContentBlock.ToolResult>(toolUses.size)
                var interrupted = false
                val metaCalls = toolUses.filter { it.name in META_TOOL_NAMES }
                val regularCalls = toolUses.filter { it.name !in META_TOOL_NAMES }

                toolUses.forEach {
                    if (announcedToolIds.add(it.id)) listener.onEvent(AgentEvent.ToolStarted(it.name, it.inputJson, it.id))
                }

                val toolStart = System.currentTimeMillis()
                val metaResults = executeMetaTools(
                    metaCalls, session, activeGroups, indicator, listener,
                    delegationCount, turnUsage, interrupted, iteration,
                    project, settings, command, compressionCount,
                    bundleSuffix,
                )
                interrupted = metaResults.interrupted
                delegationCount = metaResults.delegationCount
                turnUsage = metaResults.turnUsage
                toolResults.addAll(metaResults.results)
                if (metaResults.bundleOverride != null) bundleSuffix = metaResults.bundleOverride!!

                val streamedCalls = regularCalls.filter { streamedFutures.containsKey(it.id) }
                streamedCalls.forEach { call ->
                    val res = try {
                        streamedFutures[call.id]!!.get()
                    } catch (e: Exception) {
                        thisLogger().warn("Streamed tool '${call.name}' execution failed: ${e.message}", e)
                        ContentBlock.ToolResult(call.id, "Tool execution failed: ${e.message}", isError = true)
                    }
                    toolResults.add(res)
                }

                val freshCalls = regularCalls.filterNot { streamedFutures.containsKey(it.id) }
                if (freshCalls.isNotEmpty() && !interrupted) {
                    val (serialCalls, parallelCalls) = freshCalls.partition {
                        val t = registry.find(it.name); t?.mutating == true || t?.interactive == true
                    }
                    toolResults.addAll(executeToolsParallel(parallelCalls, settings, indicator, listener))
                    toolResults.addAll(executeToolsSequential(serialCalls, settings, indicator, listener))
                }

                session.messages.add(ChatMessage.toolResults(toolResults))
                val toolMs = System.currentTimeMillis() - toolStart
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

                val pollOnly = toolUses.isNotEmpty() && toolUses.all { registry.find(it.name)?.idempotentPoll == true }
                if (!pollOnly) {
                    val stepSignature = stepSignature(toolUses, toolResults)
                    if (recentStepSignatures.contains(stepSignature)) {
                        noProgressHits++
                        if (noProgressHits >= STUCK_ABORT_HITS) {
                            listener.onEvent(AgentEvent.Error("Stopped: the model kept repeating tool call(s) with no new result even after nudges — likely stuck. Aborted to avoid wasting tokens."))
                            listener.onEvent(AgentEvent.Info(UsageFormat.summary(settings.loopModel(), turnUsage, session.totalUsage, settings.modelPrices)))
                            listener.onEvent(AgentEvent.Done(session.totalUsage))
                            return
                        }
                        session.messages.add(
                            ChatMessage.user(
                                "You repeated a step whose result you already have (identical call, identical result). " +
                                    "Use the output you already received and take a DIFFERENT next step.",
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
                session.messages,
                settings.transcriptWindow(),
                settings.effectiveOutputReserve(),
                systemPrompt.length + bundleSuffix.length,
                summarizer,
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

    private fun stepSignature(calls: List<ContentBlock.ToolUse>, results: List<ContentBlock.ToolResult>): Long {
        var h = 1125899906842597L
        for (c in calls) {
            h = h * 31 + c.name.hashCode()
            h = h * 31 + normalizeForSignature(c.name, c.inputJson).hashCode()
        }
        for (r in results) {
            if (r.toolUseId.isNotBlank()) {
                val callerName = calls.firstOrNull { it.id == r.toolUseId }?.name
                if (callerName == GeaiToolset.NOTE) continue
            }
            h = h * 31 + r.isError.hashCode()
            h = h * 31 + r.content.hashCode()
        }
        return h
    }

    private fun normalizeForSignature(toolName: String, inputJson: String): String {
        if (toolName != "read_file") return inputJson
        return inputJson
            .replace(Regex("""\s*"start_line"\s*:\s*\d+\s*,?"""), "")
            .replace(Regex("""\s*,?\s*"end_line"\s*:\s*\d+"""), "")
    }

    private fun appendNotesAsTrailingUser(outgoing: List<ChatMessage>, scratchpad: List<NoteEntry>): List<ChatMessage> {
        if (scratchpad.isEmpty()) return outgoing
        val visible = retainNotes(scratchpad, MAX_NOTES_RETAINED)
        val dropped = scratchpad.size - visible.size
        val sorted = visible.sortedBy { it.priority.ordinal }
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

    private fun retainNotes(notes: List<NoteEntry>, limit: Int): List<NoteEntry> {
        if (notes.size <= limit) return notes
        val critical = notes.filter { it.priority == NotePriority.CRITICAL }
        val normal = notes.filter { it.priority == NotePriority.NORMAL }
        val low = notes.filter { it.priority == NotePriority.LOW }
        val result = mutableListOf<NoteEntry>()
        result.addAll(critical)
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

    private fun oneLine(s: String): String = s.replace(Regex("\\s+"), " ").trim().take(140)

    private fun runDelegate(call: ContentBlock.ToolUse, indicator: ProgressIndicator, parentListener: AgentListener, session: AgentSession, bundleSuffix: String = ""): SubOutcome {
        val args = ToolArgs.parse(call.inputJson)
        val task = args.stringOrNull("task")?.takeIf { it.isNotBlank() }
            ?: return SubOutcome(ToolResult.error("delegate needs a non-empty 'task'."), TokenUsage.ZERO)
        val hint = args.stringOrNull("hint")?.takeIf { it.isNotBlank() }
        val parentNotes = session.scratchpad
            .filter { it.priority != NotePriority.LOW }
            .sortedByDescending { it.priority.ordinal }
            .take(10)
        val prompt = buildString {
            append(task)
            if (hint != null) append("\n\nLeads/anchors to start from: $hint")
            if (session.activeTask.isNotBlank() && session.activeTask != task) {
                append("\n\n<parent_task>\n${session.activeTask}\n</parent_task>")
            }
            if (bundleSuffix.isNotBlank()) {
                append("\n\n$bundleSuffix")
            }
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
            append("\n\nReturn your findings as a structured list: for each finding, include the file:line location, what you observed, and its significance.")
            if (length > 12000) setLength(12000)
        }

        val subSession = AgentSession()
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

                else -> Unit
            }
        }
        runCatching {
            AgentLoop(project, ToolRegistry(GeaiToolset.delegateTools()), LoopProfile.sub(SUB_MAX_ITERATIONS, SUB_MAX_TURN_TOKENS))
                .run(subSession, prompt, subListener, indicator)
        }
        val text = captured.toString().ifBlank { "(the sub-agent returned no result)" }
        return SubOutcome(ToolResult.ok(text), subSession.totalUsage)
    }

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

    private val META_TOOL_NAMES = setOf(GeaiToolset.NOTE, GeaiToolset.LOAD_TOOLS, GeaiToolset.DELEGATE, GeaiToolset.REQUEST_CONTEXT, GeaiToolset.CONTEXT_STATUS)

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
        bundleSuffix: String = "",
    ): MetaResult {
        var metaInterrupted = interrupted
        var metaDelegationCount = delegationCount
        var metaTurnUsage = turnUsage
        var metaBundleOverride: String? = null
        val results = arrayOfNulls<ContentBlock.ToolResult>(calls.size)

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
                        appendLine()
                        appendLine("Recommendations:")
                        when {
                            pct >= 90 -> appendLine("- URGENT: Context nearly full. Compaction will fold history into a summary very soon — record anything precious with `note` now.")
                            pct >= 70 -> appendLine("- Context is large; automatic compaction is imminent. Findings recorded with `note` survive it verbatim.")
                            else -> appendLine("- Context is healthy. No action needed.")
                        }
                        if (critical > 10) appendLine("- Many CRITICAL notes ($critical). Review if all are still relevant to the current task.")
                        if (session.scratchpad.size > 30) appendLine("- Scratchpad has ${session.scratchpad.size} notes. Old LOW notes will be evicted automatically.")
                        if (qualityReport != null && qualityReport.score < 50) appendLine("- Last summary was low quality (${qualityReport.score}/100). Context may be losing important details.")
                    }
                    results[i] = emitMeta(call, ToolResult.ok(report), listener)
                }
                else -> error("unreachable: $call is not a meta-tool")
            }
        }

        if (delegateIndices.isNotEmpty()) {
            val futures = delegateIndices.map { idx ->
                idx to SHARED_POOL.submit(Callable { runDelegateTimed(calls[idx], indicator, listener, session, bundleSuffix) })
            }
            for ((idx, future) in futures) {
                if (indicator.isCanceled) {
                    future.cancel(true)
                    results[idx] = ContentBlock.ToolResult(calls[idx].id, "Skipped: turn was interrupted.", isError = true)
                    continue
                }
                try {
                    val outcome = try {
                        future.get(30, java.util.concurrent.TimeUnit.SECONDS)
                    } catch (_: java.util.concurrent.TimeoutException) {
                        while (true) {
                            if (indicator.isCanceled) {
                                future.cancel(true)
                                break
                            }
                            try {
                                future.get(10, java.util.concurrent.TimeUnit.SECONDS)
                                break
                            } catch (_: java.util.concurrent.TimeoutException) { /* keep waiting */ }
                        }
                        if (indicator.isCanceled) {
                            results[idx] = ContentBlock.ToolResult(calls[idx].id, "Skipped: turn was interrupted.", isError = true)
                            continue
                        }
                        future.get()
                    }
                    metaTurnUsage += outcome.usage
                    session.totalUsage += outcome.usage
                    results[idx] = ContentBlock.ToolResult(calls[idx].id, outcome.result.content, outcome.result.isError)
                } catch (e: Exception) {
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

    private fun emitMeta(call: ContentBlock.ToolUse, result: ToolResult, listener: AgentListener): ContentBlock.ToolResult {
        listener.onEvent(AgentEvent.ToolFinished(call.name, result, call.id))
        return ContentBlock.ToolResult(call.id, result.content, result.isError)
    }

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

    private fun runRegularTimed(
        call: ContentBlock.ToolUse,
        settings: GeaiSettingsState,
        indicator: ProgressIndicator,
        listener: AgentListener,
    ): ContentBlock.ToolResult {
        if (indicator.isCanceled) {
            val result = ContentBlock.ToolResult(call.id, "Skipped: turn was interrupted.", isError = true)
            listener.onEvent(AgentEvent.ToolFinished(call.name, ToolResult.error(result.content), call.id))
            return result
        }
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
        return ContentBlock.ToolResult(call.id, toolResult.content, toolResult.isError)
    }

    private fun executeToolsSequential(
        calls: List<ContentBlock.ToolUse>,
        settings: GeaiSettingsState,
        indicator: ProgressIndicator,
        listener: AgentListener,
    ): List<ContentBlock.ToolResult> = calls.map { call -> runRegularTimed(call, settings, indicator, listener) }

    private fun executeToolsParallel(
        calls: List<ContentBlock.ToolUse>,
        settings: GeaiSettingsState,
        indicator: ProgressIndicator,
        listener: AgentListener,
    ): List<ContentBlock.ToolResult> {
        if (calls.isEmpty()) return emptyList()
        val futures: List<Pair<ContentBlock.ToolUse, java.util.concurrent.Future<ContentBlock.ToolResult>>> =
            calls.map { call ->
                call to SHARED_POOL.submit(Callable { runRegularTimed(call, settings, indicator, listener) })
            }
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
        const val MAX_DELEGATIONS = 6

        /** Stuck-loop guard: how many recent step fingerprints to remember (enough to catch short cycles). */
        const val STUCK_RING_SIZE = 10

        /** Stuck-loop guard: abort only after this many repeated-fingerprint hits (nudged in between). */
        const val STUCK_ABORT_HITS = 3

        const val MAX_NOTES_RETAINED = 50

        /**
         * Sub-agent budget. Wide enough that a delegated investigation can actually finish and
         * return real findings — a starved sub-agent (4 iterations / 15k tokens) returned junk the
         * orchestrator then re-did itself, doubling the cost instead of saving it.
         */
        const val SUB_MAX_ITERATIONS = 8
        const val SUB_MAX_TURN_TOKENS = 40_000

        private const val SUMMARY_MAX_TOKENS = 2000
        private val SUMMARY_DOCTRINE = com.github.saeldrit.geai.context.SemanticCompressor.DOCTRINE

        private val VISION_ERROR_KEYWORDS = listOf(
            "image input", "image_url", "vision", "visual",
            "does not support image", "doesn't support image",
            "no endpoints found",
        )

        private fun isVisionError(errorMessage: String): Boolean {
            val lower = errorMessage.lowercase()
            return VISION_ERROR_KEYWORDS.any { lower.contains(it) }
        }

        private val CONTEXT_OVERFLOW_KEYWORDS = listOf(
            "context length", "context_length", "maximum context", "prompt is too long",
            "too many tokens", "exceeds the maximum", "input length", "context window",
            "maximum prompt length", "request too large",
        )

        private fun isContextOverflowError(errorMessage: String): Boolean {
            val lower = errorMessage.lowercase()
            return CONTEXT_OVERFLOW_KEYWORDS.any { lower.contains(it) }
        }

        private fun stripImagesFromSession(session: AgentSession): Boolean {
            var stripped = false
            for (msg in session.messages) {
                val images = msg.content.filterIsInstance<ContentBlock.Image>()
                if (images.isNotEmpty()) {
                    stripped = true
                    val cleaned = msg.content.filter { it !is ContentBlock.Image }.toMutableList()
                    if (cleaned.isEmpty()) cleaned.add(ContentBlock.Text("(image removed — not supported by model)"))
                    val idx = session.messages.indexOf(msg)
                    session.messages[idx] = msg.copy(content = cleaned)
                }
            }
            return stripped
        }

        private val SHARED_POOL: ExecutorService = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        )
    }
}

