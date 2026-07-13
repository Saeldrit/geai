package com.github.saeldrit.geai.settings

import com.github.saeldrit.geai.GeaiBundle
import com.github.saeldrit.geai.llm.LlmException
import com.github.saeldrit.geai.llm.openai.OpenAiCompatibleClient
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import com.intellij.ui.JBColor
import java.awt.BorderLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class GeaiSettingsConfigurable : Configurable {

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

    private val graceEnabledCheck =
        JBCheckBox("Enable GRACE (anchors / specs / graph / context bundle / tiered routing) — off = lean baseline")
    private val tieredRoutingCheck =
        JBCheckBox("Tiered routing: navigate on a cheap model, escalate code authoring to the main model (same provider/key)")
    private val navigatorModelField = JBTextField()
    private val autoReadCheck = JBCheckBox("Auto-approve read-only tools (read / list / search / navigate)")
    private val autoEditCheck = JBCheckBox("Auto-approve mutating tools (write / edit / run / self-modify) — ON by default; uncheck to require per-call confirmation")
    private val sourcePathField = JBTextField()

    private val hubUrlField = JBTextField()
    private val hubAutoConnectCheck = JBCheckBox("Connect to the geai Hub automatically when a project opens")

    private lateinit var navigatorPanel: JPanel
    private val baseUrlLabel = JBLabel("Base URL:")
    private val visionIndicator = JBLabel("")

    private val allModelItems = mutableListOf<String>()

    private var isFilteringModels = false

    override fun getDisplayName(): String = GeaiBundle.message("geai.settings.title")

    override fun createComponent(): JComponent {
        graceEnabledCheck.addActionListener { updateEnabled() }
        tieredRoutingCheck.addActionListener { updateEnabled() }
        providerCombo.addActionListener { onProviderChanged() }
        refreshModelsButton.addActionListener { refreshModelsAsync() }

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

        visionIndicator.foreground = JBColor.GRAY
        updateVisionIndicator()
        modelCombo.addActionListener { updateVisionIndicator() }

        val modelPanel = JPanel(BorderLayout(4, 0))
        modelPanel.add(modelCombo, BorderLayout.CENTER)
        modelPanel.add(visionIndicator, BorderLayout.EAST)
        modelPanel.add(refreshModelsButton, BorderLayout.LINE_END)

        val mainForm = FormBuilder.createFormBuilder()
            .addLabeledComponent("Provider:", providerCombo)
            .addLabeledComponent("Model:", modelPanel)
            .addLabeledComponent(baseUrlLabel, baseUrlField)
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
            .addSeparator()
            .addLabeledComponent("Hub URL:", hubUrlField)
            .addComponent(hubAutoConnectCheck)
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
        modelCombo.selectedItem = provider.defaultModel.lowercase()
        baseUrlField.text = provider.defaultBaseUrl
        baseUrlField.emptyText.text = provider.defaultBaseUrl
        val isFixedUrl = provider != LlmProvider.OPENAI_COMPATIBLE
        baseUrlLabel.isVisible = !isFixedUrl
        baseUrlField.isVisible = !isFixedUrl
        baseUrlField.parent?.revalidate()
        updateVisionIndicator()

        if (String(apiKeyField.password).isNotBlank() && provider != LlmProvider.ANTHROPIC) {
            refreshModelsAsync()
        }
    }

    private fun updateVisionIndicator() {
        val selected = modelCombo.selectedItem as? String ?: ""
        val supports = LlmProvider.modelSupportsVision(selected)
        visionIndicator.text = if (supports) "🖼 Vision" else "(text only)"
        visionIndicator.foreground = if (supports) JBColor.GRAY else UIUtil.getInactiveTextColor()
    }

    private fun populateModels(provider: LlmProvider) {
        allModelItems.clear()
        val lower = provider.suggestedModels.map { it.lowercase() }
        allModelItems.addAll(lower)
        modelCombo.removeAllItems()
        lower.forEach { modelCombo.addItem(it) }
    }

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
            val toShow = if (text !in matching && text.isNotBlank()) {
                matching + text
            } else {
                matching
            }
            if (toShow.toSet() == (0 until modelCombo.itemCount).map { modelCombo.getItemAt(it) }.toSet()) return
            modelCombo.removeAllItems()
            toShow.forEach { modelCombo.addItem(it) }
            modelCombo.selectedItem = text
        } finally {
            isFilteringModels = false
        }
    }

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
                            val lower = models.map { it.lowercase() }
                            allModelItems.clear()
                            allModelItems.addAll(lower)
                            modelCombo.removeAllItems()
                        lower.forEach { modelCombo.addItem(it) }
                            modelCombo.selectedItem = provider.defaultModel.lowercase().takeIf { it in lower } ?: lower.firstOrNull()
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
            hubUrlField.text != state.hubUrl.orEmpty() ||
            hubAutoConnectCheck.isSelected != state.hubAutoConnect ||
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
        state.hubUrl = hubUrlField.text.trim().ifBlank { null }
        state.hubAutoConnect = hubAutoConnectCheck.isSelected
        GeaiSecrets.setApiKey(provider, String(apiKeyField.password).trim().ifBlank { null })
    }

    override fun reset() {
        val state = GeaiSettings.getInstance().state
        providerCombo.selectedItem = state.provider
        populateModels(state.provider)
        val savedModel = state.model?.takeIf { it.isNotBlank() }
        modelCombo.selectedItem = savedModel ?: state.provider.defaultModel.lowercase()
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
        hubUrlField.text = state.hubUrl.orEmpty()
        hubUrlField.emptyText.text = "ws://localhost:9876/ws"
        hubAutoConnectCheck.isSelected = state.hubAutoConnect
        apiKeyField.text = GeaiSecrets.apiKey(state.provider).orEmpty()
        updateEnabled()
    }
}
