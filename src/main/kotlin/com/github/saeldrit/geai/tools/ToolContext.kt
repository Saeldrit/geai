package com.github.saeldrit.geai.tools

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project

class ToolContext(
    val project: Project,
    val indicator: ProgressIndicator,
)
