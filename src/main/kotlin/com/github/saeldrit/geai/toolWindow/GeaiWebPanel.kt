package com.github.saeldrit.geai.toolWindow

import com.github.saeldrit.geai.agent.AgentEvent
import com.github.saeldrit.geai.agent.AgentListener
import com.github.saeldrit.geai.agent.AgentSession
import com.github.saeldrit.geai.agent.GeaiAgentService
import com.github.saeldrit.geai.agent.SlashCommands
import com.github.saeldrit.geai.context.ContextCompressor
import com.github.saeldrit.geai.cost.Pricing
import com.github.saeldrit.geai.llm.ContentBlock
import com.github.saeldrit.geai.llm.LlmClientFactory
import com.github.saeldrit.geai.llm.Role
import com.github.saeldrit.geai.llm.http.JsonSupport
import com.github.saeldrit.geai.session.GeaiSessionStore
import com.github.saeldrit.geai.tools.fs.FsPaths
import com.github.saeldrit.geai.settings.GeaiSettings
import com.github.saeldrit.geai.settings.GeaiSettingsConfigurable
import com.github.saeldrit.geai.settings.effectiveModel
import com.github.saeldrit.geai.settings.effectiveOutputReserve
import com.github.saeldrit.geai.settings.loopModel
import com.github.saeldrit.geai.settings.transcriptWindow
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.UIUtil
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.awt.Color
import java.awt.datatransfer.StringSelection
import javax.swing.JPanel

/**
 * Full-fidelity chat surface rendered with JCEF (an embedded Chromium). Hosts a self-contained
 * HTML/CSS/JS app and bridges it to [GeaiAgentService] via [JBCefJSQuery] (JS->Kotlin) and
 * `executeJavaScript` (Kotlin->JS). Used when JCEF is supported; otherwise the factory falls back
 * to the Swing [GeaiChatPanel].
 */
class GeaiWebPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val service = GeaiAgentService.getInstance(project)
    private val browser = JBCefBrowser()
    private val query = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private var bridgeReady = false

    init {
        add(browser.component, BorderLayout.CENTER)
        query.addHandler { request -> onJsMessage(request); null }
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(b: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain == true && !bridgeReady) {
                    bridgeReady = true
                    injectBridge()
                }
            }
        }, browser.cefBrowser)
        browser.loadHTML(loadHtml())
        Disposer.register(this, browser)
        Disposer.register(this, query)
    }

    override fun dispose() {
        deltaFlushTimer.stop()
    }

    private fun loadHtml(): String =
        javaClass.getResource("/webview/index.html")?.readText()
            ?: "<html><body style='color:#ddd;background:#1e1f22;font-family:sans-serif'>geai: webview/index.html not found</body></html>"

    private fun injectBridge() {
        exec("window.geaiSend=function(m){${query.inject("m")}}; if(window.geaiBoot)window.geaiBoot();")
    }

    private fun exec(js: String) {
        val safe = js.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        browser.cefBrowser.executeJavaScript(safe, browser.cefBrowser.url, 0)
    }

    private fun onJsMessage(request: String) {
        val obj = runCatching { JsonParser.parseString(request).asJsonObject }.getOrNull() ?: return
        val action = obj.get("action")?.asString ?: return
        ApplicationManager.getApplication().invokeLater({ dispatch(action, obj) }, ModalityState.any())
    }

    private fun dispatch(action: String, obj: JsonObject) {
        when (action) {
            "ready" -> exec("window.geaiInit(${JsonSupport.gson.toJson(initState())});")
            "submit" -> {
                val text = obj.get("text")?.asString ?: ""
                val attArr = obj.getAsJsonArray("attachments")
                val attachments = if (attArr != null && attArr.size() > 0) {
                    attArr.map { a ->
                        val o = a.asJsonObject
                        com.github.saeldrit.geai.agent.Attachment(
                            name = o.get("name")?.asString ?: "file",
                            mediaType = o.get("mediaType")?.asString ?: "application/octet-stream",
                            base64Data = o.get("data")?.asString ?: ""
                        )
                    }
                } else emptyList()
                submitWithAttachments(text, attachments)
            }
            "stop" -> service.stop()
            "newSession" -> {
                service.newSession()
                exec("window.geaiReset&&window.geaiReset();")
            }

            "openSettings" -> ShowSettingsUtil.getInstance().showSettingsDialog(project, GeaiSettingsConfigurable::class.java)
            "benchmark" -> com.github.saeldrit.geai.benchmark.BenchmarkLauncher.launch(project)
            "compact" -> service.compact(webListener(service.currentSession().id))
            "history" -> exec("window.geaiHistory(${JsonSupport.gson.toJson(historyList())});")
            "loadSession" -> obj.get("id")?.asString?.let { loadSession(it) }
            "deleteSession" -> obj.get("id")?.asString?.let {
                GeaiSessionStore.getInstance(project).delete(it)
                exec("window.geaiHistory(${JsonSupport.gson.toJson(historyList())});")
            }

            "notes" -> exec("window.geaiNotes(${JsonSupport.gson.toJson(notesJson())});")
            "revertTurn" -> service.revertLastTurn(webListener(service.currentSession().id))
            "exportSession" -> exportSession()
            "copy" -> obj.get("text")?.asString?.let { CopyPasteManager.getInstance().setContents(StringSelection(it)) }
            "openFile" -> openInEditor(
                obj.get("path")?.takeUnless { it.isJsonNull }?.asString,
                obj.get("line")?.takeUnless { it.isJsonNull }?.asInt,
            )
        }
    }

    private fun openInEditor(rawPath: String?, line: Int?) {
        val path = rawPath?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val file = (FsPaths.resolve(project, path) ?: findByName(path))?.takeIf { !it.isDirectory } ?: return
        OpenFileDescriptor(project, file, ((line ?: 1) - 1).coerceAtLeast(0), 0).navigate(true)
    }

    private fun findByName(path: String): VirtualFile? {
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        if ('.' !in name) return null
        return runReadAction {
            FilenameIndex.getVirtualFilesByName(name, GlobalSearchScope.projectScope(project)).firstOrNull()
        }
    }

    private fun submit(text: String) = submitWithAttachments(text, emptyList())

    private fun submitWithAttachments(text: String, attachments: List<com.github.saeldrit.geai.agent.Attachment>) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachments.isEmpty()) return
        if (service.isRunning()) return
        val effectiveText = trimmed.ifEmpty { "(file attached)" }
        val session = service.currentSession()
        if (session.isEmpty && session.title == "New session") session.title = effectiveText.take(60)
        service.submit(effectiveText, webListener(session.id), attachments)
    }

    private fun loadSession(id: String) {
        val session = GeaiSessionStore.getInstance(project).load(id) ?: return
        service.openSession(session)
        exec("window.geaiInit(${JsonSupport.gson.toJson(initState())});")
    }

    private val textDeltaBuf = StringBuilder()
    private val reasonDeltaBuf = StringBuilder()
    private val deltaFlushTimer = javax.swing.Timer(33) { flushDeltas() }.apply { isRepeats = false }

    private fun flushDeltas() {
        if (reasonDeltaBuf.isNotEmpty()) {
            val json = JsonSupport.gson.toJson(event("reasoningDelta").apply { addProperty("text", reasonDeltaBuf.toString()) })
            reasonDeltaBuf.setLength(0)
            exec("window.geaiEvent($json);")
        }
        if (textDeltaBuf.isNotEmpty()) {
            val json = JsonSupport.gson.toJson(event("assistantTextDelta").apply { addProperty("text", textDeltaBuf.toString()) })
            textDeltaBuf.setLength(0)
            exec("window.geaiEvent($json);")
        }
    }

    private fun webListener(sessionId: String): AgentListener = AgentListener { event ->
        if (service.currentSession()?.id != sessionId) return@AgentListener
        ApplicationManager.getApplication().invokeLater({
            when (event) {
                is AgentEvent.AssistantTextDelta -> {
                    textDeltaBuf.append(event.text)
                    if (!deltaFlushTimer.isRunning) deltaFlushTimer.start()
                }
                is AgentEvent.ReasoningDelta -> {
                    reasonDeltaBuf.append(event.text)
                    if (!deltaFlushTimer.isRunning) deltaFlushTimer.start()
                }
                else -> {
                    flushDeltas()
                    exec("window.geaiEvent(${JsonSupport.gson.toJson(eventToJson(event))});")
                    if (event is AgentEvent.Done || event is AgentEvent.ToolFinished) {
                        exec("window.geaiUsage(${JsonSupport.gson.toJson(usageJson())});")
                    }
                }
            }
        }, ModalityState.any())
    }

    private fun usageJson(): JsonObject {
        val settings = GeaiSettings.getInstance().state
        val session = service.currentSession()
        val cost = Pricing.rateFor(Pricing.parse(settings.modelPrices), settings.loopModel())
            ?.let { Pricing.costUsd(session.totalUsage, it) }
        return JsonObject().apply {
            addProperty("contextTokens", ContextCompressor.estimatedTokens(session.messages))
            addProperty("contextWindow", settings.transcriptWindow())
            addProperty(
                "compactAt",
                ContextCompressor.compactionThresholdTokens(settings.transcriptWindow(), settings.effectiveOutputReserve()),
            )
            addProperty("tokensIn", session.totalUsage.inputTokens)
            addProperty("tokensOut", session.totalUsage.outputTokens)
            addProperty("cacheRead", session.totalUsage.cacheReadTokens)
            cost?.let { addProperty("costUsd", it) }
        }
    }

    private fun exportSession() {
        val session = service.currentSession()
        val listener = webListener(session.id)
        if (session.isEmpty) {
            listener.onEvent(AgentEvent.Info("Nothing to export — the session is empty."))
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching {
                val markdown = buildSessionMarkdown(session)
                val base = project.basePath ?: error("Project base path is unavailable")
                val dir = java.nio.file.Paths.get(base, ".geai", "exports")
                java.nio.file.Files.createDirectories(dir)
                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(java.util.Date())
                val slug = session.title.lowercase().replace(Regex("[^\\p{L}\\d]+"), "-").trim('-').take(40).ifBlank { "session" }
                val file = dir.resolve("$stamp-$slug.md")
                java.nio.file.Files.writeString(file, markdown, java.nio.charset.StandardCharsets.UTF_8)
                file
            }.onSuccess { path ->
                listener.onEvent(AgentEvent.Info("📄 Session exported: ${path.fileName} (.geai/exports/)"))
                ApplicationManager.getApplication().invokeLater({
                    com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
                        ?.let { vf -> OpenFileDescriptor(project, vf).navigate(true) }
                }, ModalityState.any())
            }.onFailure { e ->
                listener.onEvent(AgentEvent.Error("Export failed: ${e.message}"))
            }
        }
    }

    private fun buildSessionMarkdown(session: AgentSession): String = buildString {
        appendLine("# geai session — ${session.title}")
        appendLine()
        appendLine(
            "_Exported ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date())} · " +
                "${session.messages.size} messages · ↑${session.totalUsage.inputTokens} ↓${session.totalUsage.outputTokens} tokens_",
        )
        appendLine()
        val toolNames = HashMap<String, String>()
        session.messages.forEach { message ->
            when (message.role) {
                Role.USER -> message.text.takeIf { it.isNotBlank() && !isSyntheticUserText(it) }?.let {
                    appendLine("## 👤 You")
                    appendLine()
                    appendLine(it)
                    appendLine()
                }

                Role.ASSISTANT -> {
                    message.toolUses.forEach { use ->
                        toolNames[use.id] = use.name
                        appendLine("- 🔧 `${use.name}` ${preview(use.inputJson)}")
                    }
                    message.text.takeIf { it.isNotBlank() }?.let {
                        appendLine("## 🤖 geai")
                        appendLine()
                        appendLine(it)
                        appendLine()
                    }
                }

                Role.TOOL -> message.content.filterIsInstance<ContentBlock.ToolResult>().forEach { r ->
                    val status = if (r.isError) "✗" else "✓"
                    appendLine("  - $status ${toolNames[r.toolUseId] ?: "tool"}: ${r.content.replace(Regex("\\s+"), " ").take(200)}")
                }

                Role.SYSTEM -> Unit
            }
        }
    }

    private fun notesJson(): JsonObject {
        val session = service.currentSession()
        return JsonObject().apply {
            addProperty("activeTask", session.activeTask)
            add("notes", JsonArray().apply {
                session.scratchpad.forEach { note ->
                    add(JsonObject().apply {
                        addProperty("text", note.text)
                        addProperty("priority", note.priority.name)
                        note.anchor?.let { addProperty("anchor", it) }
                    })
                }
            })
        }
    }

    private fun initState(): JsonObject {
        val settings = GeaiSettings.getInstance().state
        val engine = "${settings.provider.displayName} · ${settings.effectiveModel()}"
        val configured = LlmClientFactory.isConfigured()
        val session = service.currentSession()
        return JsonObject().apply {
            add("theme", theme())
            addProperty("engine", engine)
            addProperty("configured", configured)
            addProperty("running", service.isRunning())
            addProperty("tokensIn", session.totalUsage.inputTokens)
            addProperty("tokensOut", session.totalUsage.outputTokens)
            add("usage", usageJson())
            add("skills", skillsJson())
            add("commands", commandsJson())
            add("transcript", transcriptJson(session))
        }
    }

    private fun skillsJson(): JsonArray = JsonArray().apply {
        GeaiSkills.all().forEach { skill ->
            add(JsonObject().apply {
                addProperty("id", skill.id)
                addProperty("icon", skill.icon)
                addProperty("title", skill.title)
                skill.badge?.let { addProperty("badge", it) }
                addProperty("prompt", skill.prompt)
            })
        }
    }

    private fun commandsJson(): JsonArray = JsonArray().apply {
        SlashCommands.catalog().forEach { (name, desc) ->
            add(JsonObject().apply {
                addProperty("name", name)
                addProperty("desc", desc)
            })
        }
    }

    private fun isSyntheticUserText(text: String): Boolean =
        text.startsWith("[CURRENT ACTIVE TASK") ||
            text.startsWith("[Structured summary") ||
            text.startsWith("[SESSION MEMORY") ||
            text.startsWith("You repeated a step whose result you already have") ||
            text.startsWith("Continue exactly where you left off")

    private fun transcriptJson(session: AgentSession): JsonArray = JsonArray().apply {
        val toolNames = HashMap<String, String>()
        session.messages.forEach { message ->
            when (message.role) {
                Role.USER -> message.text.takeIf {
                    it.isNotBlank() && !isSyntheticUserText(it)
                }?.let { add(event("userMessage").apply { addProperty("text", it) }) }

                Role.ASSISTANT -> {
                    message.text.takeIf { it.isNotBlank() }
                        ?.let { add(event("assistantText").apply { addProperty("text", it) }) }
                    message.toolUses.forEach { use ->
                        toolNames[use.id] = use.name
                        add(event("toolStarted").apply {
                            addProperty("tool", use.name)
                            addProperty("args", preview(use.inputJson))
                        })
                    }
                }

                Role.TOOL -> message.content.filterIsInstance<ContentBlock.ToolResult>().forEach { result ->
                    add(event("toolFinished").apply {
                        addProperty("tool", toolNames[result.toolUseId] ?: "tool")
                        addProperty("content", result.content)
                        addProperty("error", result.isError)
                    })
                }

                Role.SYSTEM -> Unit
            }
        }
    }

    private fun eventToJson(agentEvent: AgentEvent): JsonObject = when (agentEvent) {
        is AgentEvent.UserMessage -> event("userMessage").apply { addProperty("text", agentEvent.text) }
        is AgentEvent.Thinking -> event("thinking")
        is AgentEvent.Reasoning -> event("reasoning").apply { addProperty("text", agentEvent.text) }
        is AgentEvent.ReasoningDelta -> event("reasoningDelta").apply { addProperty("text", agentEvent.text) }
        is AgentEvent.AssistantText -> event("assistantText").apply { addProperty("text", agentEvent.text) }
        is AgentEvent.AssistantTextDelta -> event("assistantTextDelta").apply { addProperty("text", agentEvent.text) }
        is AgentEvent.ToolStarted -> event("toolStarted").apply {
            agentEvent.id?.let { addProperty("id", it) }
            addProperty("tool", agentEvent.tool)
            addProperty("args", preview(agentEvent.argsJson))
        }

        is AgentEvent.ToolFinished -> event("toolFinished").apply {
            agentEvent.id?.let { addProperty("id", it) }
            addProperty("tool", agentEvent.tool)
            addProperty("content", agentEvent.result.content)
            addProperty("error", agentEvent.result.isError)
        }

        is AgentEvent.Info -> event("info").apply { addProperty("text", agentEvent.text) }
        is AgentEvent.Error -> event("error").apply { addProperty("text", agentEvent.text) }
        is AgentEvent.Cancelled -> event("cancelled").apply { addProperty("text", agentEvent.text) }
        is AgentEvent.Done -> event("done").apply {
            addProperty("tokensIn", agentEvent.usage.inputTokens)
            addProperty("tokensOut", agentEvent.usage.outputTokens)
        }
    }

    private fun event(type: String): JsonObject = JsonObject().apply { addProperty("type", type) }

    private fun historyList(): JsonArray = JsonArray().apply {
        GeaiSessionStore.getInstance(project).listMeta().forEach { meta ->
            add(JsonObject().apply {
                addProperty("id", meta.id)
                addProperty("title", meta.title)
                addProperty("messageCount", meta.messageCount)
                addProperty("updatedAt", meta.updatedAtEpochMs)
            })
        }
    }

    private fun preview(raw: String): String =
        raw.replace("\n", " ").trim().let { if (it.length > 120) it.take(120) + "…" else it }

    private fun theme(): JsonObject {
        val bg = UIUtil.getPanelBackground()
        return JsonObject().apply {
            addProperty("bg", hex(bg))
            addProperty("fg", hex(UIUtil.getLabelForeground()))
            addProperty("field", hex(UIUtil.getTextFieldBackground()))
            addProperty("card", hex(shift(bg, 12)))
            addProperty("card-hover", hex(shift(bg, 24)))
            addProperty("border", hex(JBColor.border()))
            addProperty("accent", hex(JBColor(Color(0x3574F0), Color(0x589DF6))))
            addProperty("muted", hex(JBColor.GRAY))
        }
    }

    private fun shift(color: Color, delta: Int): Color {
        val isDark = (color.red + color.green + color.blue) / 3 < 128
        val k = if (isDark) delta else -delta
        return Color(
            (color.red + k).coerceIn(0, 255),
            (color.green + k).coerceIn(0, 255),
            (color.blue + k).coerceIn(0, 255),
        )
    }

    private fun hex(color: Color): String = String.format("#%02x%02x%02x", color.red, color.green, color.blue)
}
