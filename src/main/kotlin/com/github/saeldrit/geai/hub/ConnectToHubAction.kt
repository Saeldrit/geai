package com.github.saeldrit.geai.hub

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

class ConnectToHubAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null
        if (project == null) {
            e.presentation.text = "Geai: Connect to Hub"
            return
        }
        val service = HubService.getInstance(project)
        e.presentation.text = when (service.state) {
            is HubState.Connected -> "Geai: Disconnect from Hub"
            is HubState.Connecting -> "Geai: Cancel Hub Connection"
            is HubState.Error -> "Geai: Disconnect from Hub (reconnecting…)"
            else -> "Geai: Connect to Hub"
        }
        e.presentation.description = "Connect this IDE as a spoke agent to the geai Hub orchestrator"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        HubService.getInstance(project).toggle()
    }
}
