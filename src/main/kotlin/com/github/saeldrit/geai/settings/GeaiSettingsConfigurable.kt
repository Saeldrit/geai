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
 * Settings page under **Settings | Tools | Geai**. API keys are written to [GeaiSecrets]
 * (credential store), everything else to [GeaiSettings].
 */
class GeaiSettingsConfigurable : Configurable {

    private val providerCombo = ComboBox(DefaultComboBoxModel(LlmProvider.entries.toTypedArray()))
    private val modelField = JBTextField()
    private val baseUrlField = JBTextField()
    private val apiKeyField = JBPasswordField()
    private val maxTokensSpinner = JSpinner(SpinnerNumberModel(8192, 256, 200_000, 256))
    private val maxIterationsSpinner = JSpinner(SpinnerNumberModel(32, 1, 200, 1))
    private val autoReadCheck = JBCheckBox("Auto-approve read-only tools (read / list / search / navigate)")
    private val autoEditCheck = JBCheckBox("Auto-approve mutating tools (write / edit / run / self-modify)")
    private val sourcePathField = JBTextField()
    private val engineCheck = JBCheckBox("Use Claude Code CLI as the engine (your Claude subscription login)")
    private val claudePathField = JBTextField()

    override fun getDisplayName(): String = GeaiBundle.message("geai.settings.title")

    override fun createComponent(): JComponent {
        providerCombo.addActionListener {
            (providerCombo.selectedItem as? LlmProvider)?.let { p ->
                modelField.emptyText.text = p.defaultModel
                baseUrlField.emptyText.text = p.defaultBaseUrl
                apiKeyField.text = GeaiSecrets.apiKey(p).orEmpty()
            }
        }
        val built = FormBuilder.createFormBuilder()
            .addLabeledComponent("Provider:", providerCombo)
            .addLabeledComponent("Model:", modelField)
            .addLabeledComponent("Base URL:", baseUrlField)
            .addLabeledComponent("API key:", apiKeyField)
            .addLabeledComponent("Max tokens:", maxTokensSpinner)
            .addLabeledComponent("Max agent iterations:", maxIterationsSpinner)
            .addComponent(autoReadCheck)
            .addComponent(autoEditCheck)
            .addLabeledComponent("Geai source path:", sourcePathField)
            .addComponent(engineCheck)
            .addLabeledComponent("Claude CLI path:", claudePathField)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        reset()
        return built
    }

    override fun isModified(): Boolean {
        val s = GeaiSettings.getInstance().state
        val provider = providerCombo.selectedItem as? LlmProvider ?: s.provider
        val storedKey = GeaiSecrets.apiKey(provider).orEmpty()
        return provider != s.provider ||
            modelField.text != s.model.orEmpty() ||
            baseUrlField.text != s.baseUrl.orEmpty() ||
            (maxTokensSpinner.value as Int) != s.maxTokens ||
            (maxIterationsSpinner.value as Int) != s.maxAgentIterations ||
            autoReadCheck.isSelected != s.autoApproveReadTools ||
            autoEditCheck.isSelected != s.autoApproveEditTools ||
            sourcePathField.text != s.geaiSourcePath.orEmpty() ||
            engineCheck.isSelected != s.useClaudeCodeEngine ||
            claudePathField.text != s.claudeCliPath.orEmpty() ||
            String(apiKeyField.password) != storedKey
    }

    override fun apply() {
        val s = GeaiSettings.getInstance().state
        val provider = providerCombo.selectedItem as? LlmProvider ?: s.provider
        s.provider = provider
        s.model = modelField.text.trim().ifBlank { null }
        s.baseUrl = baseUrlField.text.trim().ifBlank { null }
        s.maxTokens = maxTokensSpinner.value as Int
        s.maxAgentIterations = maxIterationsSpinner.value as Int
        s.autoApproveReadTools = autoReadCheck.isSelected
        s.autoApproveEditTools = autoEditCheck.isSelected
        s.geaiSourcePath = sourcePathField.text.trim().ifBlank { null }
        s.useClaudeCodeEngine = engineCheck.isSelected
        s.claudeCliPath = claudePathField.text.trim().ifBlank { null }
        GeaiSecrets.setApiKey(provider, String(apiKeyField.password).trim().ifBlank { null })
    }

    override fun reset() {
        val s = GeaiSettings.getInstance().state
        providerCombo.selectedItem = s.provider
        modelField.text = s.model.orEmpty()
        modelField.emptyText.text = s.provider.defaultModel
        baseUrlField.text = s.baseUrl.orEmpty()
        baseUrlField.emptyText.text = s.provider.defaultBaseUrl
        maxTokensSpinner.value = s.maxTokens
        maxIterationsSpinner.value = s.maxAgentIterations
        autoReadCheck.isSelected = s.autoApproveReadTools
        autoEditCheck.isSelected = s.autoApproveEditTools
        sourcePathField.text = s.geaiSourcePath.orEmpty()
        sourcePathField.emptyText.text = "absolute path to geai's own source — enables self-modification tools"
        engineCheck.isSelected = s.useClaudeCodeEngine
        claudePathField.text = s.claudeCliPath.orEmpty()
        claudePathField.emptyText.text = "blank = resolve 'claude' from PATH"
        apiKeyField.text = GeaiSecrets.apiKey(s.provider).orEmpty()
    }
}
