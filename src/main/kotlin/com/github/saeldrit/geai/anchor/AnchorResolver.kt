package com.github.saeldrit.geai.anchor

import com.intellij.openapi.project.Project

interface AnchorResolver {
    val scheme: String

    fun resolve(locator: String, project: Project): ResolvedAnchor
}
