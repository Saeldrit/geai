package com.github.saeldrit.geai.tools.interaction

import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.util.concurrent.atomic.AtomicReference
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Lets the agent pause and ask the user a clarifying question or request confirmation
 * before proceeding with an ambiguous or risky action.
 *
 * Use this instead of silently guessing when:
 * - Intent is ambiguous ("which branch?", "overwrite or create new?")
 * - A destructive action needs explicit confirmation ("delete all sessions?")
 * - A debug session should be started ("start debug now?")
 *
 * This is a read-only tool (no side effects) — it never triggers the approval gate.
 */
object AskUserTool : AgentTool {

    override val name = "ask_user"
    override val mutating = false
    override val description =
        "Pause and ask the user a clarifying question or request confirmation before proceeding. " +
            "Use for ambiguous intent or before destructive/risky actions " +
            "(e.g. 'Start debug now?', 'Which branch should I push to?'). " +
            "Returns the user's free-text answer or 'yes'/'no'. " +
            "Do NOT use for routine tool approvals — those are handled automatically."
    override val parametersJsonSchema = """
        {"type":"object","properties":{
          "question":{"type":"string","description":"The question to show the user"},
          "placeholder":{"type":"string","description":"Hint text in the answer field (optional)"},
          "yes_no":{"type":"boolean","description":"Show Yes/No buttons instead of a text field (default false)"}
        },"required":["question"]}
    """.trimIndent()

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val question = args.string("question").trim()
        val placeholder = args.stringOrNull("placeholder").orEmpty()
        val yesNo = args.boolean("yes_no", false)

        val answer = AtomicReference<String>("(no answer)")
        ApplicationManager.getApplication().invokeAndWait {
            if (yesNo) {
                val result = Messages.showYesNoDialog(
                    context.project,
                    question,
                    "Geai",
                    "Yes",
                    "No",
                    Messages.getQuestionIcon(),
                )
                answer.set(if (result == Messages.YES) "yes" else "no")
            } else {
                val dlg = TextInputDialog(context.project, question, placeholder)
                dlg.show()
                answer.set(if (dlg.isOK) dlg.answer.ifBlank { "(no answer)" } else "(cancelled)")
            }
        }
        return ToolResult.ok(answer.get())
    }

    // ── Text input dialog ─────────────────────────────────────────────────────

    private class TextInputDialog(
        project: com.intellij.openapi.project.Project,
        private val question: String,
        private val placeholder: String,
    ) : DialogWrapper(project, true) {

        private val field = JBTextField().apply {
            emptyText.text = placeholder.ifBlank { "Type your answer…" }
            preferredSize = Dimension(JBUI.scale(440), preferredSize.height)
        }

        var answer: String = ""
            private set

        init {
            title = "Geai"
            init()
        }

        override fun createCenterPanel(): JComponent =
            FormBuilder.createFormBuilder()
                .addComponent(JBLabel("<html>${question.replace("\n", "<br>")}</html>"))
                .addLabeledComponent("Answer:", field)
                .addComponentFillVertically(JPanel(), 0)
                .panel

        override fun createActions(): Array<Action> = arrayOf(okAction, cancelAction)

        override fun doOKAction() {
            answer = field.text.trim()
            super.doOKAction()
        }
    }
}
