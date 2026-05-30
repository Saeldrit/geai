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
    var maxTokens by property(8192)
    var maxAgentIterations by property(32)

    /** Model context window (tokens) used to size transcript compaction. Default ~200k (Claude). */
    var maxContextTokens by property(200_000)

    /** Master switch for the GRACE toolset + doctrine (anchors/specs/graph/bundle/routing). Off = lean baseline. */
    var graceEnabled by property(true)

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

/** Model the agent loop runs on: the cheap navigator when tiered, otherwise the main model. */
fun GeaiSettingsState.loopModel(): String =
    if (tieredRoutingEnabled) navigatorModel?.takeIf { it.isNotBlank() } ?: effectiveModel() else effectiveModel()

/** Strong author-tier model for code authoring (the configured main model). */
fun GeaiSettingsState.authorModel(): String = effectiveModel()
