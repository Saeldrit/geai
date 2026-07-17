package com.github.saeldrit.geai.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
@State(
    name = "com.github.saeldrit.geai.settings.GeaiSettings",
    storages = [Storage("geai.xml")],
)
class GeaiSettings : SimplePersistentStateComponent<GeaiSettingsState>(GeaiSettingsState()) {
    companion object {
        fun getInstance(): GeaiSettings = service()
    }
}

class GeaiSettingsState : BaseState() {
    var provider by enum(LlmProvider.ANTHROPIC)
    var model by string()
    var baseUrl by string()
    // Output ceiling per reply. 8_192 was too low for a coding agent: large edits/summaries hit the
    // cap and triggered the "continue" auto-retry loop (each continue re-ships the whole context) —
    // observed 6 continues in a single session. 16_384 fits almost every real reply in one shot while
    // staying well under modern models' 128k streaming ceiling. Raise further in geai.xml if needed.
    var maxTokens by property(16_384)

    /**
     * Model context window in tokens — the real limit. Compaction is sized against this. Default
     * 128k is safe across Claude/GPT/DeepSeek/Qwen/GLM-class models; raise it for 200k+ models or
     * lower it for small local models via geai.xml. A provider "prompt too long" error additionally
     * triggers an automatic force-compaction, so a too-high value degrades gracefully.
     */
    var maxContextTokens by property(128_000)

    /**
     * Optional soft cap on transcript size (tokens). **0 = use the full [maxContextTokens] model
     * window** (the recommended default). Set a positive value only if you deliberately want earlier
     * compaction (e.g. to save cost on a huge-window model). Never exceeds [maxContextTokens].
     *
     * Previously defaulted to 96k, which combined with maxTokens-as-reserve collapsed the working
     * budget to ~15k tokens and caused continuous re-read loops.
     */
    var maxTranscriptTokens by property(0)

    /**
     * Tokens reserved for the model's *next reply* when sizing compaction. Independent of [maxTokens]
     * so a large output ceiling does not starve the transcript. Internal — not in the UI.
     */
    var outputReserveTokens by property(16_384)

    /**
     * Stale-output eviction: tool results older than this many most-recent assistant turns are
     * replaced by short stubs (see ToolResultEvictor) so the transcript — re-sent on EVERY LLM call —
     * does not keep paying for file dumps and command output the model already digested. Raise it for
     * models that struggle to re-read after eviction; 0 disables eviction. Internal — not in the UI.
     *
     * Default 8: observed in practice that a multi-file edit routinely spans 5-7 assistant turns
     * between reading a region and editing it (edit_file needs verbatim text) — at 4 the evictor
     * stubbed outputs the model was still using, forcing re-reads (the exact pathology it exists
     * to prevent). The 60k-char activation floor in ToolResultEvictor keeps small sessions safe.
     */
    var toolResultKeepTurns by property(8)

    /** Master switch for the GRACE toolset + doctrine (anchors/specs/graph/bundle/routing). Off = lean baseline. */
    var graceEnabled by property(true)

    /** Dev-only: log per-bundle atom telemetry (pulled/dropped/sizes) to .geai/telemetry. Off in prod. */
    var graceTelemetry by property(false)

    /** GRACE: prefer the semantic (vector) ranker for context bundles when available; else deterministic. */
    var graceVectorRanker by property(false)

    /**
     * GRACE tiered routing: when on, the agent loop runs on the cheap [navigatorModel] and escalates
     * code authoring to the strong main [model] via the escalate_author tool. Same provider/key —
     * the tiers are just two model names. Off by default (single model, current behaviour).
     */
    var tieredRoutingEnabled by property(false)

    /** Cheap navigator-tier model of the SAME provider (e.g. claude-haiku-4-5 / deepseek-chat). Blank = use main model. */
    var navigatorModel by string()

    /**
     * Per-model USD price table for cost display, one line `model=input,output,cacheRead,cacheWrite`
     * ($ per 1M tokens). Keyed by the actual model name so each model is costed at its own rate.
     * Blank/no match → tokens shown without a cost (never a fabricated figure). Kept current by the user.
     */
    var modelPrices by string()

    /**
     * Start executing read-only tools the moment their JSON finishes streaming, overlapping tool
     * I/O with the rest of the model's output (saves up to seconds per iteration on multi-tool
     * batches). Internal kill-switch — disable via geai.xml if a provider misbehaves.
     */
    var streamToolExecution by property(true)

    /** Read-only tools (read/list/search/navigate) may run without per-call confirmation. */
    var autoApproveReadTools by property(true)

    /**
     * Mutating tools (write/edit/self-modify/run) are auto-approved by default.
     * The first-run dialog offers "Allow for session" / "Allow always" / "Deny" so the user
     * can downgrade to per-call confirmation at any time via Settings | Tools | Geai.
     */
    var autoApproveEditTools by property(true)

    /** Absolute path to geai's own plugin source tree, enabling the self-modification tools. */
    var geaiSourcePath by string()

    var hubUrl by string()

    var hubAutoConnect by property(false)
}

fun GeaiSettingsState.effectiveHubUrl(): String =
    hubUrl?.takeIf { it.isNotBlank() } ?: "ws://localhost:9876/ws"

fun GeaiSettingsState.effectiveModel(): String =
    model?.takeIf { it.isNotBlank() } ?: provider.defaultModel

fun GeaiSettingsState.effectiveBaseUrl(): String =
    baseUrl?.takeIf { it.isNotBlank() }?.trimEnd('/') ?: provider.defaultBaseUrl

/**
 * Token budget the transcript is compacted against — the model's real context window, optionally
 * soft-capped by [GeaiSettingsState.maxTranscriptTokens] when that is set > 0.
 *
 * Default: full [GeaiSettingsState.maxContextTokens]. Prompt caching only cheapens the STABLE
 * prefix (system + tools), not the growing transcript, so there is no provider special-case.
 */
fun GeaiSettingsState.transcriptWindow(): Int {
    val modelWindow = maxContextTokens.coerceAtLeast(4_096)
    val softCap = maxTranscriptTokens
    return if (softCap <= 0) modelWindow else minOf(modelWindow, softCap)
}

/**
 * How many tokens to leave free for the next assistant reply when compacting. Must cover the
 * request's REAL max_tokens: providers validate input + max_tokens ≤ window, so a reserve smaller
 * than maxTokens produces "prompt too long" rejections near the top of the window.
 */
fun GeaiSettingsState.effectiveOutputReserve(): Int =
    maxOf(outputReserveTokens.coerceAtLeast(4_096), maxTokens.coerceAtLeast(1_024))

/** Model the agent loop runs on: the cheap navigator when tiered, otherwise the main model. */
fun GeaiSettingsState.loopModel(): String =
    if (tieredRoutingEnabled) navigatorModel?.takeIf { it.isNotBlank() } ?: effectiveModel() else effectiveModel()

fun GeaiSettingsState.authorModel(): String = effectiveModel()
