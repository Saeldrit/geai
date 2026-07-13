package com.github.saeldrit.geai.toolWindow

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import javax.swing.JComponent

/**
 * Installs the geai chat surface. Prefers the JCEF web UI for full fidelity, falling back to the
 * Swing [GeaiChatPanel] when JCEF is unavailable — the classic Android Studio case: either the
 * `ide.browser.jcef.enabled` registry key is off, or the IDE was launched on a boot runtime
 * without the Chromium natives. The fallback panel shows a banner explaining exactly which one
 * it is and how to get the full UI back.
 */
class GeaiToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val jcefSupported = runCatching { JBCefApp.isSupported() }.getOrDefault(false)
        if (!jcefSupported) {
            val flag = runCatching { Registry.`is`("ide.browser.jcef.enabled") }.getOrNull()
            thisLogger().warn(
                "geai: JCEF is not supported by this IDE runtime — falling back to the simplified Swing panel. " +
                    "Registry ide.browser.jcef.enabled=$flag. If the flag is false, enabling it (plus an IDE " +
                    "restart) restores the full UI; if it is true, the boot JRE lacks JCEF — switch to a " +
                    "JetBrains Runtime with JCEF via the 'Choose Boot Java Runtime for the IDE' action.",
            )
        }
        val panel: JComponent = if (jcefSupported) GeaiWebPanel(project) else GeaiChatPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        (panel as? com.intellij.openapi.Disposable)?.let { content.setDisposer(it) }
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
