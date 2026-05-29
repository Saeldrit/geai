package com.github.saeldrit.geai.settings

import com.github.saeldrit.geai.GeaiBundle
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
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
    private val autoEditCheck = JBCheckBox("Auto-approve mutating tools (write / edit / run / self-modify)")
    private val sourcePathField = JBTextField()

    override fun getDisplayName(): String = GeaiBundle.message("geai.settings.title")

    override fun createComponent(): JComponent {
        engineCheck.addActionListener { updateEnabled() }
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
        listOf<JComponent>(providerCombo, modelCombo, baseUrlField, apiKeyField, maxTokensSpinner, maxIterationsSpinner)
            .forEach { it.isEnabled = !claudeEngine }
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
        apiKeyField.text = GeaiSecrets.apiKey(state.provider).orEmpty()
        updateEnabled()
    }
}
