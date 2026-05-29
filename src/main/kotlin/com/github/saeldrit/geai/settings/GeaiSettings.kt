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

    /** Read-only tools (read/list/search/navigate) may run without per-call confirmation. */
    var autoApproveReadTools by property(true)

    /** Mutating tools (write/edit/self-modify/run) require explicit opt-in. */
    var autoApproveEditTools by property(false)

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
