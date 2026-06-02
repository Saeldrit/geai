package com.github.saeldrit.geai.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * Application-level persisted configuration for geai. API keys are intentionally NOT
 * stored here — they live in the OS credential store via [GeaiSecrets].
 */
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
    // Output cap per reply. Internal default — not surfaced in the UI; tunable via geai.xml if needed.
    var maxTokens by property(8192)

    /** Model context window (tokens) used to size transcript compaction. Default ~200k (Claude). */
    var maxContextTokens by property(200_000)

    /**
     * Active working window the transcript is compacted to (see [transcriptWindow]) — applies to ALL
     * providers. The loop resends the whole transcript every iteration, so it is kept near this budget
     * rather than the full [maxContextTokens] window, and deliberately BELOW the model ceiling so
     * compaction runs during a normal session, not only at the limit. Raise for more retained context,
     * lower to spend less.
     */
    var maxTranscriptTokens by property(48_000)

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

    /** When true, delegate the agent loop to the local Claude Code CLI (uses your subscription login). */
    var useClaudeCodeEngine by property(false)

    /** Path to the `claude` executable; blank means resolve "claude" from PATH. */
    var claudeCliPath by string()
}

fun GeaiSettingsState.effectiveModel(): String =
    model?.takeIf { it.isNotBlank() } ?: provider.defaultModel

fun GeaiSettingsState.effectiveBaseUrl(): String =
    baseUrl?.takeIf { it.isNotBlank() }?.trimEnd('/') ?: provider.defaultBaseUrl

/**
 * Token budget the transcript is compacted to — the active working window, applied for ALL providers.
 * The growing transcript is re-sent as fresh input on every iteration, so it is compacted to
 * [GeaiSettingsState.maxTranscriptTokens] (never above the model's actual [GeaiSettingsState.maxContextTokens]
 * window). Prompt caching only cheapens the STABLE prefix (system + tools) — it is NOT attached to the
 * transcript and gives no discount here, so there is no provider special-case.
 */
fun GeaiSettingsState.transcriptWindow(): Int =
    minOf(maxContextTokens, maxTranscriptTokens)

/** Model the agent loop runs on: the cheap navigator when tiered, otherwise the main model. */
fun GeaiSettingsState.loopModel(): String =
    if (tieredRoutingEnabled) navigatorModel?.takeIf { it.isNotBlank() } ?: effectiveModel() else effectiveModel()

/** Strong author-tier model for code authoring (the configured main model). */
fun GeaiSettingsState.authorModel(): String = effectiveModel()
