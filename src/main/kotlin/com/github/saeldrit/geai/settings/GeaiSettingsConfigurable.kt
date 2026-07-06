package com.github.saeldrit.geai.settings

import com.github.saeldrit.geai.GeaiBundle
import com.github.saeldrit.geai.llm.LlmException
import com.github.saeldrit.geai.llm.openai.OpenAiCompatibleClient
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Settings page under **Settings | Tools | Geai**. Deliberately minimal: the everyday tab ("Model")
 * holds only what a user must set to get going — provider, model, key — while optional/expert flags
 * live under "Advanced". The mechanical loop knobs (iteration ceiling, transcript/turn token budgets)
 * are NOT exposed: they have sound internal defaults so the agent just works without anyone tuning a
 * number. The navigator model appears only when tiered routing is on. API keys go to [GeaiSecrets].
 */
class GeaiSettingsConfigurable : Configurable {

    // Model (the essentials)
    private val providerCombo = ComboBox(DefaultComboBoxModel(LlmProvider.entries.toTypedArray()))
    private val modelCombo = ComboBox<String>().apply {
        isEditable = true
        toolTipText = "Editable — pick a suggestion or type any model id (e.g. an OpenRouter model like anthropic/claude-sonnet-4)"
    }
    private val refreshModelsButton = JButton("🔄").apply {
        toolTipText = "Fetch available models from the provider"
        isFocusable = false
    }
    private val baseUrlField = JBTextField()
    private val apiKeyField = JBPasswordField()

    // Advanced / expert
    private val graceEnabledCheck =
        JBCheckBox("Enable GRACE (anchors / specs / graph / context bundle / tiered routing) — off = lean baseline")
    private val tieredRoutingCheck =
        JBCheckBox("Tiered routing: navigate on a cheap model, escalate code authoring to the main model (same provider/key)")
    private val navigatorModelField = JBTextField()
    private val autoReadCheck = JBCheckBox("Auto-approve read-only tools (read / list / search / navigate)")
    private val autoEditCheck = JBCheckBox("Auto-approve mutating tools (write / edit / run / self-modify) — ON by default; uncheck to require per-call confirmation")
    private val sourcePathField = JBTextField()

    private lateinit var navigatorPanel: JPanel

    /** Full list of all known model ids — used for editor filtering. */
    private val allModelItems = mutableListOf<String>()

    /** Guard flag preventing DocumentListener re-entrancy (infinite loop on setSelectedItem). */
    private var isFilteringModels = false

    override fun getDisplayName(): String = GeaiBundle.message("geai.settings.title")

    override fun createComponent(): JComponent {
        graceEnabledCheck.addActionListener { updateEnabled() }
        tieredRoutingCheck.addActionListener { updateEnabled() }
        providerCombo.addActionListener { onProviderChanged() }
        refreshModelsButton.addActionListener { refreshModelsAsync() }

        // Filter the combo popup
        val editorComponent = modelCombo.editor.editorComponent
        if (editorComponent is javax.swing.text.JTextComponent) {
            editorComponent.document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent?) = applyFilter()
                override fun removeUpdate(e: DocumentEvent?) = applyFilter()
                override fun changedUpdate(e: DocumentEvent?) = applyFilter()

                private fun applyFilter() {
                    if (isFilteringModels) return
                    val text = editorComponent.text?.trim() ?: ""
                    SwingUtilities.invokeLater { filterModels(text) }
                }
            })
        }

        val modelPanel = JPanel(BorderLayout())
        modelPanel.add(modelCombo, BorderLayout.CENTER)
        modelPanel.add(refreshModelsButton, BorderLayout.EAST)

        val mainForm = FormBuilder.createFormBuilder()
            .addLabeledComponent("Provider:", providerCombo)
            .addLabeledComponent("Model:", modelPanel)
            .addLabeledComponent("Base URL:", baseUrlField)
            .addLabeledComponent("API key:", apiKeyField)
            .panel

        navigatorPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Navigator model:", navigatorModelField)
            .panel
        val advancedInner = FormBuilder.createFormBuilder()
            .addComponent(graceEnabledCheck)
            .addComponent(tieredRoutingCheck)
            .addComponent(navigatorPanel)
            .addSeparator()
            .addComponent(autoReadCheck)
            .addComponent(autoEditCheck)
            .addSeparator()
            .addLabeledComponent("Geai source path:", sourcePathField)
            .panel
        val advancedToggle = javax.swing.JCheckBox("Show advanced settings")
        advancedToggle.foreground = javax.swing.UIManager.getColor("Component.infoForeground")
        advancedInner.isVisible = false
        advancedToggle.addActionListener { advancedInner.isVisible = advancedToggle.isSelected }
        val advancedSection = JPanel(java.awt.BorderLayout())
        advancedSection.add(advancedToggle, java.awt.BorderLayout.NORTH)
        advancedSection.add(advancedInner, java.awt.BorderLayout.CENTER)

        val wrapper = JPanel(java.awt.BorderLayout())
        wrapper.add(mainForm, java.awt.BorderLayout.NORTH)
        wrapper.add(advancedSection, java.awt.BorderLayout.SOUTH)
        reset()
        return wrapper
    }

    private fun onProviderChanged() {
        val provider = providerCombo.selectedItem as? LlmProvider ?: return
        populateModels(provider)
        modelCombo.selectedItem = null // fall back to the new provider's default
        baseUrlField.emptyText.text = provider.defaultBaseUrl
    }

    private fun populateModels(provider: LlmProvider) {
        allModelItems.clear()
        allModelItems.addAll(provider.suggestedModels)
        modelCombo.removeAllItems()
        provider.suggestedModels.forEach { modelCombo.addItem(it) }
    }

    /**
     * Filter the combo-box popup items to those matching [text] (case-insensitive).
     * Uses [isFilteringModels] guard to prevent re-entrancy: [setSelectedItem] changes
     * the editor document which would otherwise fire DocumentListener → infinite loop.
     */
    private fun filterModels(text: String) {
        if (isFilteringModels) return
        isFilteringModels = true
        try {
            if (text.isEmpty()) {
                if (modelCombo.itemCount != allModelItems.size) {
                    val selected = modelCombo.selectedItem as? String
                    modelCombo.removeAllItems()
                    allModelItems.forEach { modelCombo.addItem(it) }
                    if (selected != null) modelCombo.selectedItem = selected
                }
                return
            }
            val lower = text.lowercase()
            val matching = allModelItems.filter { it.lowercase().contains(lower) }
            // Always keep the current editor text visible so the user can type custom models
            val toShow = if (text !in matching && text.isNotBlank()) {
                matching + text
            } else {
                matching
            }
            // Skip UI update if nothing actually changed (avoid redundant DocumentEvents)
            if (toShow.toSet() == (0 until modelCombo.itemCount).map { modelCombo.getItemAt(it) }.toSet()) return
            modelCombo.removeAllItems()
            toShow.forEach { modelCombo.addItem(it) }
            modelCombo.selectedItem = text
        } finally {
            isFilteringModels = false
        }
    }

    /**
     * Fetch models from the provider API on a background thread, then update the UI.
     */
    private fun refreshModelsAsync() {
        val provider = providerCombo.selectedItem as? LlmProvider ?: return
        val apiKey = String(apiKeyField.password).trim()
        if (apiKey.isEmpty()) {
            Messages.showWarningDialog(
                "Enter an API key first, then refresh models.",
                "No API Key",
            )
            return
        }

        // For native Anthropic API we know listModels is not supported
        if (provider == LlmProvider.ANTHROPIC) {
            Messages.showInfoMessage(
                "Model list is not available for ${provider.displayName}.",
                "Not Supported",
            )
            return
        }

        refreshModelsButton.isEnabled = false
        refreshModelsButton.text = "⏳"

        val baseUrlValue = baseUrlField.text.trim().ifBlank { provider.defaultBaseUrl }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val client = OpenAiCompatibleClient(baseUrlValue, apiKey)
                val models = client.listModels(com.intellij.openapi.progress.EmptyProgressIndicator())

                SwingUtilities.invokeLater {
                    refreshModelsButton.isEnabled = true
                    refreshModelsButton.text = "🔄"

                    if (models != null) {
                        if (models.isNotEmpty()) {
                            allModelItems.clear()
                            allModelItems.addAll(models)
                            modelCombo.removeAllItems()
                            models.forEach { modelCombo.addItem(it) }
                            modelCombo.selectedItem = null
                        } else {
                            Messages.showInfoMessage(
                                "Provider returned an empty model list.",
                                "No Models",
                            )
                        }
                    } else {
                        Messages.showInfoMessage(
                            "Model list is not available for ${provider.displayName}.",
                            "Not Supported",
                        )
                    }
                }
            } catch (e: LlmException) {
                SwingUtilities.invokeLater {
                    refreshModelsButton.isEnabled = true
                    refreshModelsButton.text = "🔄"
                    Messages.showErrorDialog(
                        "Failed to fetch models: ${e.message}",
                        "API Error",
                    )
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    refreshModelsButton.isEnabled = true
                    refreshModelsButton.text = "🔄"
                    Messages.showErrorDialog(
                        "Unexpected error: ${e.message ?: e.javaClass.simpleName}",
                        "Error",
                    )
                }
            }
        }
    }

    private fun currentModel(): String =
        ((modelCombo.editor.item as? String) ?: (modelCombo.selectedItem as? String)).orEmpty().trim()

    /** The navigator model surfaces only when GRACE + tiered routing are on. */
    private fun updateEnabled() {
        val grace = graceEnabledCheck.isSelected
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
        graceEnabledCheck.isSelected = state.graceEnabled
        tieredRoutingCheck.isSelected = state.tieredRoutingEnabled
        navigatorModelField.text = state.navigatorModel.orEmpty()
        navigatorModelField.emptyText.text = "cheap model of the same provider, e.g. claude-haiku-4-5 / deepseek-chat (blank = main model)"
        apiKeyField.text = GeaiSecrets.apiKey(state.provider).orEmpty()
        updateEnabled()
    }
}
