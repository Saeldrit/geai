package com.github.saeldrit.geai.tools

import com.github.saeldrit.geai.llm.ToolSpec

class ToolRegistry(tools: List<AgentTool>) {

    private val byName: Map<String, AgentTool> = tools.associateBy { it.name }

    val tools: List<AgentTool> = tools

    fun find(name: String): AgentTool? = byName[name]

    fun specs(): List<ToolSpec> = cachedSpecs

    private val cachedSpecs: List<ToolSpec> = tools.map { it.spec() }
}
