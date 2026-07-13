package com.github.saeldrit.geai.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class GeaiToolWindowService(private val project: Project) {

    fun projectName(): String = project.name
}
