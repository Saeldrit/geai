package com.github.saeldrit.geai.toolWindow

import com.github.saeldrit.geai.agent.AgentEvent
import com.github.saeldrit.geai.agent.AgentListener
import com.github.saeldrit.geai.agent.GeaiAgentService
import com.github.saeldrit.geai.llm.ChatMessage
import com.github.saeldrit.geai.llm.ContentBlock
import com.github.saeldrit.geai.llm.LlmClientFactory
import com.github.saeldrit.geai.llm.Role
import com.github.saeldrit.geai.settings.GeaiSettings
import com.github.saeldrit.geai.settings.GeaiSettingsConfigurable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
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
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.KeyStroke
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

/**
 * Swing chat surface for geai. Renders the transcript in a styled [JTextPane], submits prompts to
 * [GeaiAgentService], and marshals [AgentEvent]s back onto the EDT for display.
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

    init {
        add(buildToolbar(), BorderLayout.NORTH)
        add(JBScrollPane(wrapTop(transcript)), BorderLayout.CENTER)
        add(buildInputArea(), BorderLayout.SOUTH)
        wireActions()
        renderSession()
        val usingClaudeCode = GeaiSettings.getInstance().state.useClaudeCodeEngine
        if (!usingClaudeCode && !LlmClientFactory.isConfigured()) {
            appendBlock("Setup", "Geai is not configured. Open Settings | Tools | Geai and add an API key, or enable the Claude Code engine.", INFO)
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
        }
        add(left, BorderLayout.WEST)
        add(statusLabel, BorderLayout.EAST)
    }

    private fun buildInputArea(): JComponent = JPanel(BorderLayout(8, 0)).apply {
        border = JBUI.Borders.empty(4)
        add(JBScrollPane(input).apply { preferredSize = Dimension(0, 72) }, BorderLayout.CENTER)
        val buttons = JPanel(BorderLayout(0, 4)).apply {
            add(sendButton, BorderLayout.NORTH)
            add(stopButton, BorderLayout.SOUTH)
        }
        add(buttons, BorderLayout.EAST)
    }

    private fun wrapTop(component: JComponent): JComponent =
        JPanel(BorderLayout()).apply { add(component, BorderLayout.NORTH) }

    private fun wireActions() {
        sendButton.addActionListener { submit() }
        stopButton.addActionListener { service.stop() }
        newSessionButton.addActionListener {
            service.newSession()
            transcript.text = ""
            statusLabel.text = "New session"
        }
        // Enter sends; Shift+Enter inserts a newline.
        input.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "geai.send")
        input.actionMap.put("geai.send", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) = submit()
        })
        input.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "insert-break")
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
            is AgentEvent.Thinking -> statusLabel.text = "Thinking…"
            is AgentEvent.AssistantText -> appendBlock("geai", event.text, ASSISTANT)
            is AgentEvent.ToolStarted -> appendInline("→ ${event.tool} ${preview(event.argsJson)}", TOOL)
            is AgentEvent.ToolFinished -> {
                val mark = if (event.result.isError) "✗" else "✓"
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
                statusLabel.text = "Done · ${event.usage.inputTokens} in / ${event.usage.outputTokens} out"
                setRunning(false)
            }
        }
    }

    private fun setRunning(running: Boolean) {
        sendButton.isEnabled = !running
        stopButton.isEnabled = running
        newSessionButton.isEnabled = !running
        input.isEnabled = !running
        if (running) statusLabel.text = "Geai is working…"
    }

    private fun renderSession() {
        service.currentSession().messages.forEach { message -> renderMessage(message) }
    }

    private fun renderMessage(message: ChatMessage) {
        when (message.role) {
            Role.USER -> message.text.takeIf { it.isNotBlank() }?.let { appendBlock("You", it, USER) }
            Role.ASSISTANT -> {
                message.text.takeIf { it.isNotBlank() }?.let { appendBlock("geai", it, ASSISTANT) }
                message.toolUses.forEach { appendInline("→ ${it.name} ${preview(it.inputJson)}", TOOL) }
            }

            Role.TOOL -> message.content.filterIsInstance<ContentBlock.ToolResult>().forEach {
                val mark = if (it.isError) "✗" else "✓"
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
        return if (oneLine.length <= 160) oneLine else oneLine.take(160) + "…"
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
