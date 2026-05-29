package com.github.saeldrit.geai.toolWindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import javax.swing.JComponent

/**
 * Installs the geai chat surface. Prefers the JCEF web UI for full fidelity, falling back to the
 * Swing [GeaiChatPanel] when JCEF is unavailable (e.g. a JBR without the bundled browser).
 */
class GeaiToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel: JComponent = if (JBCefApp.isSupported()) GeaiWebPanel(project) else GeaiChatPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        (panel as? com.intellij.openapi.Disposable)?.let { content.setDisposer(it) }
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
