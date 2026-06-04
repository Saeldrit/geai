package com.github.saeldrit.geai.toolWindow

import com.github.saeldrit.geai.agent.AgentEvent
import com.github.saeldrit.geai.agent.AgentListener
import com.github.saeldrit.geai.agent.GeaiAgentService
import com.github.saeldrit.geai.context.ContextCompressor
import com.github.saeldrit.geai.cost.Pricing
import com.github.saeldrit.geai.cost.UsageFormat
import com.github.saeldrit.geai.llm.ChatMessage
import com.github.saeldrit.geai.llm.ContentBlock
import com.github.saeldrit.geai.llm.LlmClientFactory
import com.github.saeldrit.geai.llm.Role
import com.github.saeldrit.geai.llm.TokenUsage
import com.github.saeldrit.geai.settings.GeaiSettings
import com.github.saeldrit.geai.settings.GeaiSettingsConfigurable
import com.github.saeldrit.geai.settings.LlmProvider
import com.github.saeldrit.geai.settings.loopModel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.Locale
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.KeyStroke
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

/**
 * Swing chat surface for geai. Renders the transcript in a styled [JTextPane], submits prompts to
 * [GeaiAgentService], and marshals [AgentEvent]s back onto the EDT for display.
 *
 * The toolbar carries quick controls the user reaches for every turn: new session, settings,
 * provider/model pickers, and cost-tier presets (Fast / Balanced / Power). The status bar shows
 * a rich live token counter (↑in ↓out 💾cache · $cost) and a tooltip with the full breakdown.
 * A pre-flight estimate under the input field shows the projected cost BEFORE Send is clicked.
 */
class GeaiChatPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val service = GeaiAgentService.getInstance(project)

    private val transcript = JTextPane().apply {
        isEditable = false
        border = JBUI.Borders.empty(8)
    }
    private val input = JBTextArea(3, 0).apply {
        lineWrap = true
        wrapStyleWord = true
        toolTipText = "Describe the bug. Enter to send, Shift+Enter for a new line."
    }
    private val sendButton = JButton("Send")
    private val stopButton = JButton("Stop").apply { isEnabled = false }
    private val newSessionButton = JButton("New session")
    private val statusLabel = JBLabel("Ready").apply { border = JBUI.Borders.empty(2, 8) }

    /** Per-session cost accumulator; updated on every Info event with usage data. */
    private var lastTurnUsage: TokenUsage = TokenUsage.ZERO
    private val preflightLabel = JBLabel("").apply {
        border = JBUI.Borders.empty(0, 8, 4, 8)
        foreground = JBColor.GRAY
    }

    private val providerCombo = ComboBox(LlmProvider.entries.toTypedArray()).apply {
        toolTipText = "Switch provider \u2014 applies immediately to the next turn."
    }
    private val modelCombo = ComboBox<String>().apply {
        isEditable = true
        toolTipText = "Switch model within the selected provider."
    }

    init {
        add(buildToolbar(), BorderLayout.NORTH)
        add(JBScrollPane(wrapTop(transcript)), BorderLayout.CENTER)
        add(buildInputArea(), BorderLayout.SOUTH)
        wireActions()
        renderSession()
        syncFromSettings()
        updatePreflight()
        if (!LlmClientFactory.isConfigured()) {
            appendBlock("Setup", "Geai is not configured. Open Settings | Tools | Geai and add an API key.", INFO)
        }
    }

    private fun buildToolbar(): JComponent = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(4)
        val left = JPanel(HorizontalLayout(8)).apply {
            add(newSessionButton)
            add(JButton("Settings").apply {
                addActionListener {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, GeaiSettingsConfigurable::class.java)
                }
            })
            add(JBLabel("Provider:"))
            add(providerCombo)
            add(JBLabel("Model:"))
            add(modelCombo)
            // Cost-tier presets: one-click switches that prime provider + model + navigator for a
            // typical workflow without opening Settings. Anthropic-centric defaults that the user can
            // override later (the preset is fire-and-forget \u2014 it just writes the same fields Settings would).
            add(JButton("\uD83D\uDE80 Fast").apply {
                toolTipText = "Haiku / cheap navigator \u2014 fastest, lowest cost."
                addActionListener { applyPreset(Preset.FAST) }
            })
            add(JButton("\u2696 Balanced").apply {
                toolTipText = "Sonnet \u2014 default trade-off between cost and quality."
                addActionListener { applyPreset(Preset.BALANCED) }
            })
            add(JButton("\uD83D\uDC8E Power").apply {
                toolTipText = "Opus / strongest model \u2014 expensive, use for hard tasks."
                addActionListener { applyPreset(Preset.POWER) }
            })
        }
        add(left, BorderLayout.WEST)
        add(statusLabel, BorderLayout.EAST)
    }

    private fun buildInputArea(): JComponent = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(4)
        // Pre-flight estimate sits ABOVE the input row so the user sees the projected cost before
        // committing to Send. Recomputed on every keystroke (cheap: char-count / heuristic).
        add(preflightLabel, BorderLayout.NORTH)
        val row = JPanel(BorderLayout(8, 0)).apply {
            add(JBScrollPane(input).apply { preferredSize = Dimension(0, 72) }, BorderLayout.CENTER)
            val buttons = JPanel(BorderLayout(0, 4)).apply {
                add(sendButton, BorderLayout.NORTH)
                add(stopButton, BorderLayout.SOUTH)
            }
            add(buttons, BorderLayout.EAST)
        }
        add(row, BorderLayout.CENTER)
    }

    private fun wrapTop(component: JComponent): JComponent =
        JPanel(BorderLayout()).apply { add(component, BorderLayout.NORTH) }

    private fun wireActions() {
        sendButton.addActionListener { submit() }
        stopButton.addActionListener { service.stop() }
        newSessionButton.addActionListener {
            service.newSession()
            transcript.text = ""
            lastTurnUsage = TokenUsage.ZERO
            statusLabel.text = "New session"
            statusLabel.toolTipText = null
            updatePreflight()
        }
        // Enter sends; Shift+Enter inserts a newline.
        input.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "geai.send")
        input.actionMap.put("geai.send", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) = submit()
        })
        input.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "insert-break")
        input.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = updatePreflight()
            override fun removeUpdate(e: DocumentEvent?) = updatePreflight()
            override fun changedUpdate(e: DocumentEvent?) = updatePreflight()
        })
        providerCombo.addActionListener {
            (providerCombo.selectedItem as? LlmProvider)?.let { provider ->
                val state = GeaiSettings.getInstance().state
                if (state.provider != provider) {
                    state.provider = provider
                    state.model = null
                    populateModels(provider)
                    updatePreflight()
                }
            }
        }
        modelCombo.addActionListener {
            val text = (modelCombo.editor.item as? String)?.trim().orEmpty()
            val state = GeaiSettings.getInstance().state
            state.model = text.ifBlank { null }
            updatePreflight()
        }
    }

    /** Reflect the persisted settings into the toolbar combos (called on init + after presets). */
    private fun syncFromSettings() {
        val state = GeaiSettings.getInstance().state
        providerCombo.selectedItem = state.provider
        populateModels(state.provider)
        modelCombo.selectedItem = state.model?.takeIf { it.isNotBlank() } ?: state.provider.defaultModel
    }

    private fun populateModels(provider: LlmProvider) {
        modelCombo.removeAllItems()
        provider.suggestedModels.forEach { modelCombo.addItem(it) }
    }

    /** Apply a cost-tier preset and refresh the UI; the user can still hand-edit afterwards. */
    private fun applyPreset(preset: Preset) {
        val state = GeaiSettings.getInstance().state
        state.provider = preset.provider
        state.model = preset.model
        state.navigatorModel = preset.navigatorModel
        // Tiered routing only makes sense when a navigator is set AND it differs from the main model.
        state.tieredRoutingEnabled = preset.navigatorModel != null && preset.navigatorModel != preset.model
        syncFromSettings()
        updatePreflight()
        appendInline("Preset applied: ${preset.label} (${preset.provider.displayName} / ${preset.model})", INFO)
    }

    private fun submit() {
        val text = input.text.trim()
        if (text.isEmpty() || service.isRunning()) return
        input.text = ""
        setRunning(true)
        service.submit(text, edtListener())
    }

    /** Wraps UI updates so agent-thread events render on the EDT, even under a modal approval dialog. */
    private fun edtListener(): AgentListener = AgentListener { event ->
        ApplicationManager.getApplication().invokeLater({ onEvent(event) }, ModalityState.any())
    }

    private fun onEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.UserMessage -> appendBlock("You", event.text, USER)
            is AgentEvent.Thinking -> statusLabel.text = "Thinking\u2026"
            is AgentEvent.Reasoning -> appendInline("\u2304 reasoning: ${preview(event.text)}", INFO)
            // Streaming deltas are ignored in the Swing fallback \u2014 the trailing AssistantText renders
            // the complete block, so there is no live token-by-token append here.
            is AgentEvent.ReasoningDelta -> Unit
            is AgentEvent.AssistantTextDelta -> Unit
            is AgentEvent.AssistantText -> appendBlock("geai", event.text, ASSISTANT)
            is AgentEvent.ToolStarted -> appendInline("\u2192 ${event.tool} ${preview(event.argsJson)}", TOOL)
            is AgentEvent.ToolFinished -> {
                val mark = if (event.result.isError) "\u2717" else "\u2713"
                appendInline("$mark ${event.tool}: ${preview(event.result.content)}", TOOL)
            }

            is AgentEvent.Info -> appendInline(event.text, INFO)
            is AgentEvent.Error -> {
                appendBlock("Error", event.text, ERROR)
                setRunning(false)
            }

            is AgentEvent.Cancelled -> {
                appendInline(event.text, INFO)
                setRunning(false)
            }

            is AgentEvent.Done -> {
                lastTurnUsage = event.usage
                refreshStatus(event.usage)
                setRunning(false)
            }
        }
    }

    /** Status bar HUD: a compact, glanceable token+cost line with a full breakdown in the tooltip. */
    private fun refreshStatus(sessionUsage: TokenUsage) {
        val state = GeaiSettings.getInstance().state
        val model = state.loopModel()
        val rates = Pricing.parse(state.modelPrices)
        val rate = Pricing.rateFor(rates, model)
        val cost = rate?.let { Pricing.costUsd(sessionUsage, it) }
        val cacheChunk = if (sessionUsage.cacheReadTokens > 0 || sessionUsage.cacheWriteTokens > 0) {
            " \uD83D\uDCBE${humanK(sessionUsage.cacheReadTokens)}"
        } else {
            ""
        }
        val costChunk = cost?.let { " \u00b7 ~$" + String.format(Locale.US, "%.3f", it) } ?: ""
        statusLabel.text = "\u2191${humanK(sessionUsage.inputTokens)} \u2193${humanK(sessionUsage.outputTokens)}$cacheChunk$costChunk"
        statusLabel.toolTipText = "<html>" + UsageFormat.line("session", model, sessionUsage, state.modelPrices)
            .replace("\u00b7", "&middot;") + "</html>"
    }

    /** Recomputes the projected cost of the NEXT turn from current input + transcript. Cheap heuristic. */
    private fun updatePreflight() {
        val text = input.text
        if (text.isBlank()) {
            preflightLabel.text = ""
            return
        }
        val state = GeaiSettings.getInstance().state
        val model = state.loopModel()
        val transcriptTokens = ContextCompressor.estimatedTokens(service.currentSession().messages)
        // Rough input token estimate: transcript + input text + ~3k for system+tools overhead.
        val estimatedInput = transcriptTokens + (text.length / 4) + 3_000
        val rates = Pricing.parse(state.modelPrices)
        val rate = Pricing.rateFor(rates, model)
        val costText = rate?.let { r ->
            // Optimistic: assume the stable prefix hits cache (cacheRead price). Realistic for an
            // ongoing session; the first turn pays the full input rate, which the user can see in the
            // status line afterwards.
            val effectiveRate = if (r.cacheRead > 0 && transcriptTokens > 1000) r.cacheRead else r.input
            val cost = estimatedInput * effectiveRate / 1_000_000.0
            " \u00b7 ~$" + String.format(Locale.US, "%.4f", cost)
        }.orEmpty()
        preflightLabel.text = "Next turn estimate: ~${humanK(estimatedInput)} input$costText [$model]"
    }

    private fun setRunning(running: Boolean) {
        sendButton.isEnabled = !running
        stopButton.isEnabled = running
        newSessionButton.isEnabled = !running
        input.isEnabled = !running
        providerCombo.isEnabled = !running
        modelCombo.isEnabled = !running
        if (running) statusLabel.text = "Geai is working\u2026"
    }

    private fun renderSession() {
        service.currentSession().messages.forEach { message -> renderMessage(message) }
    }

    private fun renderMessage(message: ChatMessage) {
        when (message.role) {
            Role.USER -> message.text.takeIf { it.isNotBlank() }?.let { appendBlock("You", it, USER) }
            Role.ASSISTANT -> {
                message.text.takeIf { it.isNotBlank() }?.let { appendBlock("geai", it, ASSISTANT) }
                message.toolUses.forEach { appendInline("\u2192 ${it.name} ${preview(it.inputJson)}", TOOL) }
            }

            Role.TOOL -> message.content.filterIsInstance<ContentBlock.ToolResult>().forEach {
                val mark = if (it.isError) "\u2717" else "\u2713"
                appendInline("$mark ${preview(it.content)}", TOOL)
            }

            Role.SYSTEM -> Unit
        }
    }

    private fun appendBlock(header: String, body: String, headerStyle: SimpleAttributeSet) {
        append("\n$header\n", headerStyle)
        append(body.trim() + "\n", BODY)
        scrollToEnd()
    }

    private fun appendInline(text: String, style: SimpleAttributeSet) {
        append(text.trim() + "\n", style)
        scrollToEnd()
    }

    private fun append(text: String, style: SimpleAttributeSet) {
        val doc = transcript.styledDocument
        doc.insertString(doc.length, text, style)
    }

    private fun scrollToEnd() {
        transcript.caretPosition = transcript.styledDocument.length
    }

    private fun preview(text: String): String {
        val oneLine = text.replace('\n', ' ').trim()
        return if (oneLine.length <= 160) oneLine else oneLine.take(160) + "\u2026"
    }

    /** Compact token count: 1234 -> "1.2k", 12345 -> "12k". */
    private fun humanK(tokens: Int): String = when {
        tokens < 1000 -> tokens.toString()
        tokens < 10_000 -> String.format(Locale.US, "%.1fk", tokens / 1000.0)
        else -> "${tokens / 1000}k"
    }

    /**
     * Cost-tier preset: a one-click bundle of provider + main model + navigator model. The user can
     * always tweak fields in Settings afterwards \u2014 the preset just writes good defaults.
     */
    private enum class Preset(
        val label: String,
        val provider: LlmProvider,
        val model: String,
        val navigatorModel: String?,
    ) {
        FAST("\uD83D\uDE80 Fast", LlmProvider.ANTHROPIC, "claude-haiku-4-5-20251001", null),
        BALANCED("\u2696 Balanced", LlmProvider.ANTHROPIC, "claude-sonnet-4-6", "claude-haiku-4-5-20251001"),
        POWER("\uD83D\uDC8E Power", LlmProvider.ANTHROPIC, "claude-opus-4-8", "claude-haiku-4-5-20251001"),
    }

    private companion object {
        val USER = style(JBColor(Color(0x2D6FE0), Color(0x589DF6)), bold = true)
        val ASSISTANT = style(JBColor(Color(0x3C9A57), Color(0x6FAF74)), bold = true)
        val BODY = style(null, bold = false)
        val TOOL = style(JBColor.GRAY, bold = false, italic = true)
        val INFO = style(JBColor.GRAY, bold = false, italic = true)
        val ERROR = style(JBColor.RED, bold = true)

        fun style(color: JBColor?, bold: Boolean, italic: Boolean = false): SimpleAttributeSet =
            SimpleAttributeSet().apply {
                color?.let { StyleConstants.setForeground(this, it) }
                StyleConstants.setBold(this, bold)
                StyleConstants.setItalic(this, italic)
            }
    }
}
