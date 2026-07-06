package com.github.saeldrit.geai.toolWindow

import com.github.saeldrit.geai.agent.AgentEvent
import com.github.saeldrit.geai.agent.AgentListener
import com.github.saeldrit.geai.agent.Attachment
import com.github.saeldrit.geai.agent.GeaiAgentService
import com.github.saeldrit.geai.cost.UsageFormat
import com.github.saeldrit.geai.llm.TokenUsage
import com.github.saeldrit.geai.settings.GeaiSettings
import com.github.saeldrit.geai.settings.GeaiSettingsConfigurable
import com.github.saeldrit.geai.settings.LlmProvider
import com.github.saeldrit.geai.tools.ToolResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.openapi.vfs.LocalFileSystem
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.InputEvent
import java.io.File
import java.net.URI
import javax.swing.*
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.event.HyperlinkEvent
import javax.swing.event.HyperlinkListener
import javax.swing.text.html.HTMLEditorKit

/**
 * Swing chat surface rendered as HTML via [JEditorPane].
 * Used when JCEF is unavailable (e.g. Android Studio with a JBR that lacks Chromium natives).
 */
class GeaiChatPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val service = GeaiAgentService.getInstance(project)

    // --- HTML rendering ---
    private val editorPane = JEditorPane().apply {
        isEditable = false
        contentType = "text/html"
        editorKit = HTMLEditorKit()
        addHyperlinkListener(HyperlinkListener { e ->
            if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                try { Desktop.getDesktop().browse(URI(e.url.toString())) } catch (_: Exception) {}
            }
        })
    }

    // --- accumulated HTML fragments per message ---
    private val messages = mutableListOf<String>()
    private var streamingIdx = -1

    // --- input / controls ---
    private val input = JBTextArea(3, 0).apply {
        lineWrap = true; wrapStyleWord = true
        toolTipText = "Describe the bug. Enter to send, Shift+Enter for newline."
    }
    private val sendButton = JButton("Send")
    private val stopButton = JButton("Stop").apply { isEnabled = false }
    private val attachButton = JButton("📎").apply { toolTipText = "Attach file(s)" }
    private val newSessionButton = JButton("New session")
    private val statusLabel = JBLabel("Ready").apply { border = JBUI.Borders.empty(2, 8) }
    private val attachLabel = JBLabel("").apply {
        border = JBUI.Borders.empty(0, 8, 2, 8); foreground = JBColor.GRAY
        isVisible = false
    }
    private val pendingAttachments = mutableListOf<Attachment>()
    private val preflightLabel = JBLabel("").apply {
        border = JBUI.Borders.empty(0, 8, 4, 8); foreground = JBColor.GRAY
    }

    private var lastTurnUsage: TokenUsage = TokenUsage.ZERO

    // ── init ──────────────────────────────────────────────────────────────
    init {
        add(buildToolbar(), BorderLayout.NORTH)
        add(JBScrollPane(editorPane), BorderLayout.CENTER)
        add(buildInputArea(), BorderLayout.SOUTH)
        wireActions()
        renderWelcome()
        if (!com.github.saeldrit.geai.llm.LlmClientFactory.isConfigured()) {
            addInfo("Geai is not configured. Open Settings | Tools | Geai and add an API key.")
        }
    }

    private fun buildToolbar(): JComponent = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(4)
        val left = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            add(newSessionButton)
            add(JButton("Settings").apply {
                addActionListener { ShowSettingsUtil.getInstance().showSettingsDialog(project, GeaiSettingsConfigurable::class.java) }
            })
        }
        add(left, BorderLayout.WEST)
        add(statusLabel, BorderLayout.EAST)
    }

    private fun buildInputArea(): JComponent = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(4)
        add(preflightLabel, BorderLayout.NORTH)
        val row = JPanel(BorderLayout(8, 0)).apply {
            add(JBScrollPane(input).apply { preferredSize = Dimension(0, 72) }, BorderLayout.CENTER)
            val buttons = JPanel(BorderLayout(0, 4)).apply {
                add(sendButton, BorderLayout.NORTH)
                add(stopButton, BorderLayout.SOUTH)
            }
            add(buttons, BorderLayout.EAST)
        }
        val bottom = JPanel(BorderLayout()).apply {
            add(attachButton, BorderLayout.WEST)
            add(attachLabel, BorderLayout.CENTER)
        }
        add(row, BorderLayout.CENTER)
        add(bottom, BorderLayout.SOUTH)
    }

    // ── actions ───────────────────────────────────────────────────────────
    private fun wireActions() {
        sendButton.addActionListener { submit() }
        stopButton.addActionListener { service.stop() }
        newSessionButton.addActionListener {
            service.newSession()
            messages.clear(); streamingIdx = -1; renderHtml()
            lastTurnUsage = TokenUsage.ZERO
        }
        attachButton.addActionListener { openFileChooser() }
        input.getInputMap(JComponent.WHEN_FOCUSED).put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "send")
        input.actionMap.put("send", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) { submit() }
        })
        input.getInputMap(JComponent.WHEN_FOCUSED).put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "newline")
        input.actionMap.put("newline", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) { input.insert("\n", input.caretPosition) }
        })
    }

    private fun openFileChooser() {
        val chooser = JFileChooser().apply {
            isMultiSelectionEnabled = true
            fileSelectionMode = JFileChooser.FILES_ONLY
            fileFilter = FileNameExtensionFilter(
                "Images & Text files",
                "png", "jpg", "jpeg", "gif", "webp", "bmp",
                "txt", "md", "json", "xml", "csv", "log",
                "kt", "java", "py", "js", "ts", "html", "css", "sh",
                "yaml", "yml", "toml", "ini", "cfg", "properties", "gradle", "kts"
            )
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            val files = chooser.selectedFiles ?: return
            val remaining = 10 - pendingAttachments.size
            if (remaining <= 0) return
            files.take(remaining).forEach { f ->
                val bytes = f.readBytes()
                val base64 = java.util.Base64.getEncoder().encodeToString(bytes)
                val mediaType = java.net.URLConnection.guessContentTypeFromName(f.name)
                    ?: if (f.name.matches(Regex(".*\\.(kt|java|py|js|ts|sh|md|json|yaml|yml|xml|csv|log|toml|html|css|sql|rb|go|rs|php|txt|cfg|conf|ini|gradle|properties)$", RegexOption.IGNORE_CASE)))
                        "text/plain" else "application/octet-stream"
                pendingAttachments += Attachment(f.name, mediaType, base64)
            }
            updateAttachLabel()
        }
    }

    private fun updateAttachLabel() {
        if (pendingAttachments.isEmpty()) {
            attachLabel.text = ""; attachLabel.isVisible = false
        } else {
            attachLabel.text = pendingAttachments.joinToString(", ") { it.name }
            attachLabel.isVisible = true
        }
    }

    private fun submit() {
        val text = input.text.trim()
        if (text.isEmpty() && pendingAttachments.isEmpty()) return
        input.text = ""
        val atts = pendingAttachments.toList()
        pendingAttachments.clear(); updateAttachLabel()
        streamingIdx = -1
        service.submit(text, myListener, atts)
    }

    // ── Agent listener ────────────────────────────────────────────────────
    private val myListener = AgentListener { event ->
        ApplicationManager.getApplication().invokeLater({
            when (event) {
                is AgentEvent.UserMessage -> {
                    addMessage("You", escapeHtml(event.text), "user")
                    streamingIdx = -1
                }
                is AgentEvent.Thinking -> statusLabel.text = "Thinking\u2026"
                is AgentEvent.Reasoning -> {
                    messages.add("""<details class="reason"><summary>Reasoning</summary><pre class="rbody">${escapeHtml(event.text)}</pre></details>""")
                    streamingIdx = messages.size - 1
                    renderHtml()
                }
                is AgentEvent.ReasoningDelta -> {
                    if (streamingIdx in messages.indices) {
                        val old = messages[streamingIdx]
                        messages[streamingIdx] = old.replace("</pre></details>",
                            "${escapeHtml(event.text)}</pre></details>")
                        renderHtml()
                    }
                }
                is AgentEvent.AssistantText -> {
                    finalizeStreaming(Markdown.toHtml(event.text))
                    streamingIdx = -1
                    statusLabel.text = "Ready"
                }
                is AgentEvent.AssistantTextDelta -> {
                    appendStreamingDelta(event.text)
                    statusLabel.text = "Writing\u2026"
                }
                is AgentEvent.ToolStarted -> {
                    val short = if (event.argsJson.length > 80) event.argsJson.take(77) + "\u2026" else event.argsJson
                    messages.add("""<div class="tool">\u23F3 ${escapeHtml(event.tool)} ${escapeHtml(short)}</div>""")
                    renderHtml()
                    statusLabel.text = "Tool: ${event.tool}\u2026"
                }
                is AgentEvent.ToolFinished -> {
                    markLastToolDone(event.result.isError)
                    if (event.result.isError) addError(event.result.content)
                }
                is AgentEvent.Info -> addInfo(event.text)
                is AgentEvent.Error -> addError(event.text)
                is AgentEvent.Cancelled -> statusLabel.text = "Stopped."
                is AgentEvent.Done -> {
                    statusLabel.text = "Ready"
                    lastTurnUsage = event.usage
                }
            }
            // stream-status (non-persistent, except Done handled above)
            when (event) {
                is AgentEvent.Done -> Unit
                is AgentEvent.Thinking -> statusLabel.text = "Thinking\u2026"
                is AgentEvent.AssistantTextDelta, is AgentEvent.AssistantText -> statusLabel.text = "Writing\u2026"
                is AgentEvent.ToolStarted -> statusLabel.text = "Tool: ${event.tool}\u2026"
                else -> Unit
            }
        }, ModalityState.nonModal())
    }

    // ── message helpers ───────────────────────────────────────────────────
    private fun addMessage(who: String, bodyHtml: String, css: String) {
        messages.add("""<div class="msg $css"><div class="who">$who</div><div class="body">$bodyHtml</div></div>""")
        renderHtml()
    }

    private fun addError(text: String) {
        messages.add("""<div class="errline">${escapeHtml(text)}</div>""")
        renderHtml()
    }

    private fun addInfo(text: String) {
        messages.add("""<div class="info">${escapeHtml(text)}</div>""")
        renderHtml()
    }

    // --- streaming helpers ---
    // We accumulate raw markdown text alongside the rendered HTML so deltas can re-render.
    private val rawTextBuf = StringBuilder()

    private fun appendStreamingDelta(delta: String) {
        if (streamingIdx < 0) {
            streamingIdx = messages.size
            rawTextBuf.clear()
            messages.add("") // placeholder
        }
        rawTextBuf.append(delta)
        val rendered = Markdown.toHtml(rawTextBuf.toString())
        messages[streamingIdx] = """<div class="msg assistant"><div class="who">geai</div><div class="body">$rendered</div></div>"""
        renderHtml(scroll = true)
    }

    private fun finalizeStreaming(finalHtml: String) {
        if (streamingIdx in messages.indices) {
            rawTextBuf.clear()
            messages[streamingIdx] = """<div class="msg assistant"><div class="who">geai</div><div class="body">$finalHtml</div></div>"""
        } else {
            addMessage("geai", finalHtml, "assistant")
        }
        renderHtml()
    }

    private fun markLastToolDone(isError: Boolean) {
        for (i in messages.indices.reversed()) {
            val m = messages[i]
            if (m.contains("\u23F3")) {
                messages[i] = m.replace("\u23F3", if (isError) "\u274C" else "\u2705")
                break
            }
        }
        renderHtml()
    }

    // ── full HTML rebuild ─────────────────────────────────────────────────
    private fun renderWelcome() {
        editorPane.text = buildPage(welcomeHtml())
        editorPane.caretPosition = 0
    }

    private fun renderHtml(scroll: Boolean = false) {
        val body = if (messages.isEmpty()) welcomeHtml() else messages.joinToString("\n")
        editorPane.text = buildPage(body)
        if (scroll) SwingUtilities.invokeLater { editorPane.caretPosition = editorPane.document.length }
    }

    private fun welcomeHtml() = """
        <div class="welcome">
          <div class="logo"><span class="mark">\u25C9</span> geai</div>
          <p class="tagline">Describe the bug to get started.</p>
        </div>
    """.trimIndent()

    private fun buildPage(body: String): String = """
        <!DOCTYPE html>
        <html><head><style>${STYLESHEET}</style></head>
        <body><div id="root">$body</div></body></html>
    """.trimIndent()

    fun dispose() { /* listener is passed per-submit, nothing to clean up */ }

    companion object {
        private const val STYLESHEET = """
            body {
                margin:0; padding:0;
                background:#1e1f22; color:#dfe1e5;
                font-family:"Segoe UI",system-ui,sans-serif;
                font-size:13px; line-height:1.5;
            }
            #root { padding:14px; }
            .welcome { text-align:center; padding-top:40px; }
            .welcome .logo { font-size:26px; font-weight:700; }
            .welcome .mark { color:#589df6; }
            .welcome .tagline { color:#8c8c8c; max-width:480px; margin:8px auto 0; }
            .msg { margin:0 0 14px; }
            .msg .who { font-weight:600; margin-bottom:3px; }
            .msg.user .who { color:#589df6; }
            .msg.assistant .who { color:#4ec98b; }
            .msg .body { white-space:normal; word-wrap:break-word; }
            .msg .body p { margin:0 0 8px; }
            .tool { color:#8c8c8c; font-size:12px; margin:0 0 6px;
                    font-family:ui-monospace,Consolas,monospace; overflow-wrap:anywhere; }
            .info { color:#8c8c8c; font-style:italic; margin:0 0 8px; overflow-wrap:anywhere; }
            .errline { color:#e06c75; font-weight:600; margin:0 0 8px; white-space:pre-wrap; }
            .reason { margin:0 0 12px; border:1px solid #393b40; border-radius:8px;
                     background:#2b2d30; overflow:hidden; }
            .reason summary { cursor:pointer; padding:7px 10px; color:#8c8c8c; font-size:12px; }
            .reason .rbody { padding:6px 12px 10px; color:#8c8c8c; white-space:pre-wrap;
                           font-size:12px; border-top:1px solid #393b40;
                           font-family:ui-monospace,Consolas,monospace; }
            pre { background:#181a1c; border:1px solid #393b40; border-radius:8px;
                 padding:9px 11px; overflow-x:auto; margin:6px 0; }
            code { font-family:ui-monospace,Consolas,monospace; font-size:12px; }
            p code { background:#181a1c; border:1px solid #393b40; border-radius:4px; padding:1px 4px; }
            .msg.assistant h1, .msg.assistant h2 { font-size:1.1em; font-weight:700; margin:10px 0 4px;
                  padding-bottom:3px; border-bottom:1px solid #393b40; }
            .msg.assistant h3 { font-size:1em; font-weight:700; margin:8px 0 3px; }
            .msg.assistant ul, .msg.assistant ol { padding-left:1.4em; margin:4px 0; }
            .msg.assistant li { margin:1px 0; }
            .msg.assistant table { border-collapse:collapse; margin:6px 0; font-size:0.92em; width:100%%; }
            .msg.assistant th, .msg.assistant td { border:1px solid #393b40; padding:3px 8px; text-align:left; }
            .msg.assistant th { background:#35373b; font-weight:600; }
            a { color:#589df6; }
            hr { border:none; border-top:1px solid #393b40; margin:8px 0; }
        """
    }
}

// ── Lightweight markdown → HTML ───────────────────────────────────────────
private object Markdown {
    fun toHtml(md: String): String {
        val sb = StringBuilder()
        val lines = md.replace("\r\n", "\n").split("\n")
        var i = 0
        var inCode = false
        val codeBuf = StringBuilder()

        while (i < lines.size) {
            val line = lines[i]
            if (line.trimStart().startsWith("```")) {
                if (!inCode) { inCode = true; codeBuf.clear(); i++; continue }
                else { sb.append("<pre><code>${escapeHtml(codeBuf.toString().trimEnd('\n'))}</code></pre>\n"); inCode = false; i++; continue }
            }
            if (inCode) { if (codeBuf.isNotEmpty()) codeBuf.append('\n'); codeBuf.append(line); i++; continue }

            val trimmed = line.trim()
            if (trimmed.isEmpty()) { sb.append("<br>"); i++; continue }
            if (trimmed.matches(Regex("^(?:-{3,}|\\*{3,}|_{3,})$"))) { sb.append("<hr>"); i++; continue }

            val hMatch = Regex("^(#{1,6})\\s+(.+)").matchEntire(trimmed)
            if (hMatch != null) { val lv = hMatch.groupValues[1].length; sb.append("<h$lv>${inline(hMatch.groupValues[2])}</h$lv>\n"); i++; continue }

            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                sb.append("<ul>\n")
                while (i < lines.size) { val l = lines[i].trim(); if (l.startsWith("- ") || l.startsWith("* ")) { sb.append("<li>${inline(l.substring(2))}</li>\n"); i++ } else break }
                sb.append("</ul>\n"); continue
            }
            if (trimmed.matches(Regex("^\\d+\\.\\s.*"))) {
                sb.append("<ol>\n")
                while (i < lines.size) { val m = Regex("^\\d+\\.\\s(.*)").matchEntire(lines[i].trim()); if (m != null) { sb.append("<li>${inline(m.groupValues[1])}</li>\n"); i++ } else break }
                sb.append("</ol>\n"); continue
            }

            sb.append("<p>${inline(trimmed)}</p>\n"); i++
        }
        if (inCode && codeBuf.isNotEmpty()) sb.append("<pre><code>${escapeHtml(codeBuf.toString())}</code></pre>\n")
        return sb.toString()
    }

    private fun inline(text: String): String {
        var s = escapeHtml(text)
        s = s.replace(Regex("`([^`]+)`"), "<code>$1</code>")
        s = s.replace(Regex("\\*\\*\\*(.+?)\\*\\*\\*"), "<b><i>$1</i></b>")
        s = s.replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
        s = s.replace(Regex("\\*(.+?)\\*"), "<i>$1</i>")
        s = s.replace(Regex("\\[([^]]+)]\\(([^)]+)\\)"), "<a href=\"$2\">$1</a>")
        return s
    }
}

private fun escapeHtml(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
