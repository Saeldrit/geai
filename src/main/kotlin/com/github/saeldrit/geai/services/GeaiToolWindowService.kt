package com.github.saeldrit.geai.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Lightweight project-scoped service kept for tool-window wiring and tests.
 *
 * The real agent orchestration lives in [com.github.saeldrit.geai.agent.GeaiAgentService];
 * this type intentionally stays free of heavy dependencies so it can be requested cheaply.
 */
@Service(Service.Level.PROJECT)
class GeaiToolWindowService(private val project: Project) {

    fun projectName(): String = project.name
}
