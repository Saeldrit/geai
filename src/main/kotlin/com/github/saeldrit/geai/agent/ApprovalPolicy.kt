package com.github.saeldrit.geai.agent

import com.github.saeldrit.geai.settings.GeaiSettings
import com.github.saeldrit.geai.tools.AgentTool
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * User gate for mutating tools.
 *
 * Decision hierarchy (first match wins):
 * 1. [GeaiSettings] autoApproveEditTools = true  → always allow, no dialog.
 * 2. Session-level allow-list (in-memory, cleared on IDE restart) → allow silently.
 * 3. Dialog with three choices:
 *    - **Allow once**        → allow this call only.
 *    - **Allow for session** → add tool to session allow-list; no more dialogs this session.
 *    - **Allow always**      → persist autoApproveEditTools=true in Settings; no more dialogs ever.
 *    - **Deny**              → block this call.
 */
object ApprovalPolicy {

    /** Tools approved for the lifetime of this IDE session (cleared on restart). */
    private val sessionAllowed = ConcurrentHashMap.newKeySet<String>()

    /** Clear session approvals (e.g. on new conversation). */
    fun clearSession() = sessionAllowed.clear()

    fun confirm(project: Project, tool: AgentTool, argsJson: String): Boolean {
        // 1. Global auto-approve
        if (GeaiSettings.getInstance().state.autoApproveEditTools) return true
        // 2. Session allow-list
        if (tool.name in sessionAllowed) return true
        // 3. Show dialog on EDT
        val decision = AtomicReference(Decision.DENY)
        ApplicationManager.getApplication().invokeAndWait {
            val dlg = ApprovalDialog(project, tool.name, argsJson)
            dlg.show()
            decision.set(dlg.decision)
        }
        return when (decision.get()!!) {
            Decision.ALLOW_ONCE -> true
            Decision.ALLOW_SESSION -> {
                sessionAllowed.add(tool.name)
                true
            }
            Decision.ALLOW_ALWAYS -> {
                GeaiSettings.getInstance().state.autoApproveEditTools = true
                true
            }
            Decision.DENY -> false
        }
    }

    enum class Decision { ALLOW_ONCE, ALLOW_SESSION, ALLOW_ALWAYS, DENY }

    // ── Dialog ────────────────────────────────────────────────────────────────

    private class ApprovalDialog(
        project: Project,
        private val toolName: String,
        private val argsJson: String,
    ) : DialogWrapper(project, true) {

        var decision: Decision = Decision.DENY
            private set

        private val alwaysCheck = JBCheckBox("Remember: auto-approve ALL mutating tools from now on (saves to Settings)")

        init {
            title = "Geai — approve '$toolName'?"
            isModal = true
            init()
        }

        override fun createCenterPanel(): JComponent {
            val panel = JPanel(BorderLayout(0, JBUI.scale(8)))
            panel.border = JBUI.Borders.empty(4, 0)

            val label = JBLabel("<html>Geai wants to run the mutating tool <b>$toolName</b>.</html>")
            panel.add(label, BorderLayout.NORTH)

            val argsArea = JBTextArea(argsJson.take(2000)).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                font = JBUI.Fonts.create("JetBrains Mono", 11)
                background = JBUI.CurrentTheme.ToolWindow.background()
            }
            val scroll = JBScrollPane(argsArea).apply {
                preferredSize = Dimension(JBUI.scale(520), JBUI.scale(160))
            }
            panel.add(scroll, BorderLayout.CENTER)
            panel.add(alwaysCheck, BorderLayout.SOUTH)
            return panel
        }

        override fun createActions(): Array<Action> = arrayOf(
            buildAction("Allow once", Decision.ALLOW_ONCE, isDefault = true),
            buildAction("Allow for session", Decision.ALLOW_SESSION),
            buildAction("Deny", Decision.DENY),
        )

        private fun buildAction(text: String, d: Decision, isDefault: Boolean = false) =
            object : DialogWrapperAction(text) {
                init { if (isDefault) putValue(DEFAULT_ACTION, true) }
                override fun doAction(e: java.awt.event.ActionEvent) {
                    decision = if (alwaysCheck.isSelected) Decision.ALLOW_ALWAYS else d
                    close(OK_EXIT_CODE)
                }
            }
    }
}
