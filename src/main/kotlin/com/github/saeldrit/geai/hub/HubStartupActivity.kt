package com.github.saeldrit.geai.hub

import com.github.saeldrit.geai.settings.GeaiSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class HubStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (GeaiSettings.getInstance().state.hubAutoConnect) {
            HubService.getInstance(project).connect()
        }
    }
}
