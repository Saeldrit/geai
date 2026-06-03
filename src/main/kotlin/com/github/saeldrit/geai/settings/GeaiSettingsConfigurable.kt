package com.github.saeldrit.geai.settings

import com.github.saeldrit.geai.GeaiBundle
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings page under **Settings | Tools | Geai**. Deliberately minimal: the everyday tab ("Model")
 * holds only what a user must set to get going — engine and model — while optional/expert flags live
 * under "Advanced". The mechanical loop knobs (iteration ceiling, transcript/turn token budgets) are
 * NOT exposed at all: they have sound internal defaults so the agent just works without anyone tuning
 * a number. The UI is dynamic — the engine toggle swaps the Claude-CLI path for the API provider
 * block, and the navigator model appears only when tiered routing is on. API keys go to [GeaiSecrets].
 */
class GeaiSettingsConfigurable : Configurable {

    // --- Engine & model (the essentials) ------------------------------------------------------
    private val engineCheck =
        JBCheckBox("Use Claude Code CLI as the engine (your Claude subscription login — no API key needed)")
    private val claudePathField = JBTextField()

    private val providerCombo = ComboBox(DefaultComboBoxModel(LlmProvider.entries.toTypedArray()))
    private val modelCombo = ComboBox<String>().apply {
        isEditable = true
        toolTipText = "Editable — pick a suggestion or type any model id (e.g. an OpenRouter model like anthropic/claude-sonnet-4)"
    }
    private val baseUrlField = JBTextField()
    private val apiKeyField = JBPasswordField()

    // --- Advanced / expert --------------------------------------------------------------------
    private val graceEnabledCheck =
        JBCheckBox("Enable GRACE (anchors / specs / graph / context bundle / tiered routing) — off = lean baseline")
    private val tieredRoutingCheck =
        JBCheckBox("Tiered routing: navigate on a cheap model, escalate code authoring to the main model (same provider/key)")
    private val navigatorModelField = JBTextField()
    private val autoReadCheck = JBCheckBox("Auto-approve read-only tools (read / list / search / navigate)")
    private val autoEditCheck = JBCheckBox("Auto-approve mutating tools (write / edit / run / self-modify) — ON by default; uncheck to require per-call confirmation")
    private val sourcePathField = JBTextField()

    // Sub-panels whose visibility is toggled dynamically.
    private lateinit var cliPanel: JPanel
    private lateinit var providerPanel: JPanel
    private lateinit var navigatorPanel: JPanel

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

        cliPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Claude CLI path:", claudePathField)
            .panel
        providerPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Provider:", providerCombo)
            .addLabeledComponent("Model:", modelCombo)
            .addLabeledComponent("Base URL:", baseUrlField)
            .addLabeledComponent("API key:", apiKeyField)
            .panel
        val modelTab = FormBuilder.createFormBuilder()
            .addComponent(engineCheck)
            .addComponent(cliPanel)
            .addComponent(providerPanel)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        navigatorPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Navigator model:", navigatorModelField)
            .panel
        val advancedTab = FormBuilder.createFormBuilder()
            .addComponent(graceEnabledCheck)
            .addComponent(tieredRoutingCheck)
            .addComponent(navigatorPanel)
            .addSeparator()
            .addComponent(autoReadCheck)
            .addComponent(autoEditCheck)
            .addSeparator()
            .addLabeledComponent("Geai source path:", sourcePathField)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        val tabs = JBTabbedPane()
        tabs.addTab("Model", modelTab)
        tabs.addTab("Advanced", advancedTab)
        reset()
        return tabs
    }

    private fun populateModels(provider: LlmProvider) {
        modelCombo.removeAllItems()
        provider.suggestedModels.forEach { modelCombo.addItem(it) }
    }

    private fun currentModel(): String =
        ((modelCombo.editor.item as? String) ?: (modelCombo.selectedItem as? String)).orEmpty().trim()

    /**
     * Show only what applies right now. The engine fork swaps the CLI-path block for the API provider
     * block; the GRACE flags gray out under the Claude Code engine (they drive the native loop only);
     * the navigator model surfaces only when tiered routing is on. Auto-approve applies to both engines
     * (geai's own tools are gated by it either way), so it is never disabled.
     */
    private fun updateEnabled() {
        val claudeEngine = engineCheck.isSelected
        cliPanel.isVisible = claudeEngine
        providerPanel.isVisible = !claudeEngine

        graceEnabledCheck.isEnabled = !claudeEngine
        val grace = !claudeEngine && graceEnabledCheck.isSelected
        tieredRoutingCheck.isEnabled = grace
        navigatorPanel.isVisible = grace && tieredRoutingCheck.isSelected
        navigatorModelField.isEnabled = grace && tieredRoutingCheck.isSelected
    }

    override fun isModified(): Boolean {
        val state = GeaiSettings.getInstance().state
        val provider = providerCombo.selectedItem as? LlmProvider ?: state.provider
        val storedKey = GeaiSecrets.apiKey(provider).orEmpty()
        return provider != state.provider ||
            currentModel() != state.model.orEmpty() ||
            baseUrlField.text != state.baseUrl.orEmpty() ||
            autoReadCheck.isSelected != state.autoApproveReadTools ||
            autoEditCheck.isSelected != state.autoApproveEditTools ||
            sourcePathField.text != state.geaiSourcePath.orEmpty() ||
            engineCheck.isSelected != state.useClaudeCodeEngine ||
            claudePathField.text != state.claudeCliPath.orEmpty() ||
            graceEnabledCheck.isSelected != state.graceEnabled ||
            tieredRoutingCheck.isSelected != state.tieredRoutingEnabled ||
            navigatorModelField.text != state.navigatorModel.orEmpty() ||
            String(apiKeyField.password) != storedKey
    }

    override fun apply() {
        val state = GeaiSettings.getInstance().state
        val provider = providerCombo.selectedItem as? LlmProvider ?: state.provider
        state.provider = provider
        state.model = currentModel().ifBlank { null }
        state.baseUrl = baseUrlField.text.trim().ifBlank { null }
        state.autoApproveReadTools = autoReadCheck.isSelected
        state.autoApproveEditTools = autoEditCheck.isSelected
        state.geaiSourcePath = sourcePathField.text.trim().ifBlank { null }
        state.useClaudeCodeEngine = engineCheck.isSelected
        state.claudeCliPath = claudePathField.text.trim().ifBlank { null }
        state.graceEnabled = graceEnabledCheck.isSelected
        state.tieredRoutingEnabled = tieredRoutingCheck.isSelected
        state.navigatorModel = navigatorModelField.text.trim().ifBlank { null }
        GeaiSecrets.setApiKey(provider, String(apiKeyField.password).trim().ifBlank { null })
    }

    override fun reset() {
        val state = GeaiSettings.getInstance().state
        providerCombo.selectedItem = state.provider
        populateModels(state.provider)
        modelCombo.selectedItem = state.model?.takeIf { it.isNotBlank() }
        baseUrlField.text = state.baseUrl.orEmpty()
        baseUrlField.emptyText.text = state.provider.defaultBaseUrl
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
        apiKeyField.text = GeaiSecrets.apiKey(state.provider).orEmpty()
        updateEnabled()
    }
}
