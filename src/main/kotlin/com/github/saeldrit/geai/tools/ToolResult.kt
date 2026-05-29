package com.github.saeldrit.geai.tools

/** Outcome of a tool invocation. [isError] lets the model distinguish failures and self-correct. */
data class ToolResult(val content: String, val isError: Boolean = false) {
    companion object {
        fun ok(content: String): ToolResult = ToolResult(content, isError = false)
        fun error(message: String): ToolResult = ToolResult(message, isError = true)
    }
}
