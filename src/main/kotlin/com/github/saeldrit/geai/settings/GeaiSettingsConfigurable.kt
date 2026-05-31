package com.github.saeldrit.geai.settings

import com.github.saeldrit.geai.GeaiBundle
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * Settings page under **Settings | Tools | Geai**. The engine toggle gates which fields apply:
 * with the Claude Code engine on, the provider/model/key fields are disabled (Claude Code brings
 * its own login and model). API keys are written to [GeaiSecrets]; everything else to [GeaiSettings].
 */
class GeaiSettingsConfigurable : Configurable {

    private val engineCheck =
        JBCheckBox("Use Claude Code CLI as the engine (your Claude subscription login — no API key needed)")
    private val claudePathField = JBTextField()

    private val providerCombo = ComboBox(DefaultComboBoxModel(LlmProvider.entries.toTypedArray()))
    private val modelCombo = ComboBox<String>().apply { isEditable = true }
    private val baseUrlField = JBTextField()
    private val apiKeyField = JBPasswordField()
    private val maxTokensSpinner = JSpinner(SpinnerNumberModel(8192, 256, 200_000, 256))
    private val maxIterationsSpinner = JSpinner(SpinnerNumberModel(32, 1, 200, 1))

    private val autoReadCheck = JBCheckBox("Auto-approve read-only tools (read / list / search / navigate)")
    private val autoEditCheck = JBCheckBox("Auto-approve mutating tools (write / edit / run / self-modify) — ON by default; uncheck to require per-call confirmation")
    private val sourcePathField = JBTextField()

    private val graceEnabledCheck =
        JBCheckBox("Enable GRACE (anchors / specs / graph / context bundle / tiered routing) — off = lean baseline")
    private val tieredRoutingCheck =
        JBCheckBox("GRACE tiered routing: navigate on a cheap model, escalate code authoring to the main model (same provider/key)")
    private val navigatorModelField = JBTextField()
    private val vectorRankerCheck =
        JBCheckBox("GRACE: prefer the semantic (vector) ranker for context bundles when available")
    private val telemetryCheck =
        JBCheckBox("GRACE telemetry (dev): log per-bundle atom pulled/dropped/sizes to .geai/telemetry")
    private val modelPricesArea = JBTextArea(4, 40)

    override fun getDisplayName(): String = GeaiBundle.message("geai.settings.title")

    override fun createComponent(): JComponent {
        engineCheck.addActionListener { updateEnabled() }
        graceEnabledCheck.addActionListener { updateEnabled() }
        tieredRoutingCheck.addActionListener { updateEnabled() }
        providerCombo.addActionListener {
            (providerCombo.selectedItem as? LlmProvider)?.let { provider ->
                populateModels(provider)
                modelCombo.selectedItem = null // fall back to the new provider's default
                baseUrlField.emptyText.text = provider.defaultBaseUrl
            }
        }
        val panel = FormBuilder.createFormBuilder()
            .addComponent(engineCheck)
            .addLabeledComponent("Claude CLI path:", claudePathField)
            .addSeparator()
            .addLabeledComponent("Provider:", providerCombo)
            .addLabeledComponent("Model:", modelCombo)
            .addLabeledComponent("Base URL:", baseUrlField)
            .addLabeledComponent("API key:", apiKeyField)
            .addLabeledComponent("Max tokens:", maxTokensSpinner)
            .addLabeledComponent("Max agent iterations:", maxIterationsSpinner)
            .addSeparator()
            .addComponent(autoReadCheck)
            .addComponent(autoEditCheck)
            .addLabeledComponent("Geai source path:", sourcePathField)
            .addSeparator()
            .addComponent(graceEnabledCheck)
            .addComponent(tieredRoutingCheck)
            .addLabeledComponent("Navigator model:", navigatorModelField)
            .addComponent(vectorRankerCheck)
            .addComponent(telemetryCheck)
            .addLabeledComponent("Model prices ($/1M):", modelPricesArea)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        reset()
        return panel
    }

    private fun populateModels(provider: LlmProvider) {
        modelCombo.removeAllItems()
        provider.suggestedModels.forEach { modelCombo.addItem(it) }
    }

    private fun currentModel(): String =
        ((modelCombo.editor.item as? String) ?: (modelCombo.selectedItem as? String)).orEmpty().trim()

    /** Disable the fields that do not apply to the active engine, so it is obvious what to fill in. */
    private fun updateEnabled() {
        val claudeEngine = engineCheck.isSelected
        claudePathField.isEnabled = claudeEngine
        listOf<JComponent>(providerCombo, modelCombo, baseUrlField, apiKeyField, maxTokensSpinner, maxIterationsSpinner, graceEnabledCheck)
            .forEach { it.isEnabled = !claudeEngine }
        // GRACE sub-options apply only with the native engine AND GRACE enabled.
        val grace = !claudeEngine && graceEnabledCheck.isSelected
        tieredRoutingCheck.isEnabled = grace
        vectorRankerCheck.isEnabled = grace
        telemetryCheck.isEnabled = grace
        navigatorModelField.isEnabled = grace && tieredRoutingCheck.isSelected
    }

    override fun isModified(): Boolean {
        val state = GeaiSettings.getInstance().state
        val provider = providerCombo.selectedItem as? LlmProvider ?: state.provider
        val storedKey = GeaiSecrets.apiKey(provider).orEmpty()
        return provider != state.provider ||
            currentModel() != state.model.orEmpty() ||
            baseUrlField.text != state.baseUrl.orEmpty() ||
            (maxTokensSpinner.value as Int) != state.maxTokens ||
            (maxIterationsSpinner.value as Int) != state.maxAgentIterations ||
            autoReadCheck.isSelected != state.autoApproveReadTools ||
            autoEditCheck.isSelected != state.autoApproveEditTools ||
            sourcePathField.text != state.geaiSourcePath.orEmpty() ||
            engineCheck.isSelected != state.useClaudeCodeEngine ||
            claudePathField.text != state.claudeCliPath.orEmpty() ||
            graceEnabledCheck.isSelected != state.graceEnabled ||
            tieredRoutingCheck.isSelected != state.tieredRoutingEnabled ||
            navigatorModelField.text != state.navigatorModel.orEmpty() ||
            vectorRankerCheck.isSelected != state.graceVectorRanker ||
            telemetryCheck.isSelected != state.graceTelemetry ||
            modelPricesArea.text != state.modelPrices.orEmpty() ||
            String(apiKeyField.password) != storedKey
    }

    override fun apply() {
        val state = GeaiSettings.getInstance().state
        val provider = providerCombo.selectedItem as? LlmProvider ?: state.provider
        state.provider = provider
        state.model = currentModel().ifBlank { null }
        state.baseUrl = baseUrlField.text.trim().ifBlank { null }
        state.maxTokens = maxTokensSpinner.value as Int
        state.maxAgentIterations = maxIterationsSpinner.value as Int
        state.autoApproveReadTools = autoReadCheck.isSelected
        state.autoApproveEditTools = autoEditCheck.isSelected
        state.geaiSourcePath = sourcePathField.text.trim().ifBlank { null }
        state.useClaudeCodeEngine = engineCheck.isSelected
        state.claudeCliPath = claudePathField.text.trim().ifBlank { null }
        state.graceEnabled = graceEnabledCheck.isSelected
        state.tieredRoutingEnabled = tieredRoutingCheck.isSelected
        state.navigatorModel = navigatorModelField.text.trim().ifBlank { null }
        state.graceVectorRanker = vectorRankerCheck.isSelected
        state.graceTelemetry = telemetryCheck.isSelected
        state.modelPrices = modelPricesArea.text.trim().ifBlank { null }
        GeaiSecrets.setApiKey(provider, String(apiKeyField.password).trim().ifBlank { null })
    }

    override fun reset() {
        val state = GeaiSettings.getInstance().state
        providerCombo.selectedItem = state.provider
        populateModels(state.provider)
        modelCombo.selectedItem = state.model?.takeIf { it.isNotBlank() }
        baseUrlField.text = state.baseUrl.orEmpty()
        baseUrlField.emptyText.text = state.provider.defaultBaseUrl
        maxTokensSpinner.value = state.maxTokens
        maxIterationsSpinner.value = state.maxAgentIterations
        autoReadCheck.isSelected = state.autoApproveReadTools
        autoEditCheck.isSelected = state.autoApproveEditTools
        sourcePathField.text = state.geaiSourcePath.orEmpty()
        sourcePathField.emptyText.text = "absolute path to geai's own source — enables self-modification tools"
        engineCheck.isSelected = state.useClaudeCodeEngine
        claudePathField.text = state.claudeCliPath.orEmpty()
        claudePathField.emptyText.text = "blank = resolve 'claude' from PATH"
        graceEnabledCheck.isSelected = state.graceEnabled
        tieredRoutingCheck.isSelected = state.tieredRoutingEnabled
        navigatorModelField.text = state.navigatorModel.orEmpty()
        navigatorModelField.emptyText.text = "cheap model of the same provider, e.g. claude-haiku-4-5 / deepseek-chat (blank = main model)"
        vectorRankerCheck.isSelected = state.graceVectorRanker
        telemetryCheck.isSelected = state.graceTelemetry
        modelPricesArea.text = state.modelPrices.orEmpty()
        modelPricesArea.emptyText.text = "one per line: model=input,output,cacheRead,cacheWrite ($/1M) — e.g. claude-opus-4-8=15,75,1.5,18.75"
        apiKeyField.text = GeaiSecrets.apiKey(state.provider).orEmpty()
        updateEnabled()
    }
}
