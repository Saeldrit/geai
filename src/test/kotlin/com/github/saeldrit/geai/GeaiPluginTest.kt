package com.github.saeldrit.geai

import com.github.saeldrit.geai.services.GeaiToolWindowService
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class GeaiPluginTest : BasePlatformTestCase() {

    fun testProjectServiceResolves() {
        val service = project.service<GeaiToolWindowService>()
        assertEquals(project.name, service.projectName())
    }
}
