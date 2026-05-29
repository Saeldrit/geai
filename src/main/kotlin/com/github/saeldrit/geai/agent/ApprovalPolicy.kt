package com.github.saeldrit.geai.agent

import com.github.saeldrit.geai.tools.AgentTool
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.util.concurrent.atomic.AtomicBoolean

/** User gate for mutating tools when auto-approval is off. Prompts modally on the EDT. */
object ApprovalPolicy {

    fun confirm(project: Project, tool: AgentTool, argsJson: String): Boolean {
        val approved = AtomicBoolean(false)
        ApplicationManager.getApplication().invokeAndWait {
            val answer = Messages.showYesNoDialog(
                project,
                "Geai wants to run the mutating tool '${tool.name}'.\n\nArguments:\n${argsJson.take(1500)}",
                "Geai: approve '${tool.name}'?",
                "Allow",
                "Deny",
                Messages.getQuestionIcon(),
            )
            approved.set(answer == Messages.YES)
        }
        return approved.get()
    }
}
