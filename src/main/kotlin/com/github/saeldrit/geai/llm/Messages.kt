package com.github.saeldrit.geai.llm

/**
 * Conversation role. Tool results are carried by a dedicated [Role.TOOL] message so each
 * provider adapter can map them to its own wire shape (Anthropic: a `user` message with
 * `tool_result` blocks; OpenAI: one `tool` message per result).
 */
enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

sealed interface ContentBlock {

    data class Text(val text: String) : ContentBlock

    /** An inline image attached by the user. [mediaType] is a MIME type (e.g. "image/png"). */
    data class Image(val base64Data: String, val mediaType: String) : ContentBlock

    data class ToolUse(
        val id: String,
        val name: String,
        val inputJson: String,
    ) : ContentBlock

    data class ToolResult(
        val toolUseId: String,
        val content: String,
        val isError: Boolean = false,
    ) : ContentBlock
}

data class ChatMessage(
    val role: Role,
    val content: List<ContentBlock>,
) {
    val text: String
        get() = content.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }

    val toolUses: List<ContentBlock.ToolUse>
        get() = content.filterIsInstance<ContentBlock.ToolUse>()

    companion object {
        fun user(text: String): ChatMessage =
            ChatMessage(Role.USER, listOf(ContentBlock.Text(text)))

        fun assistant(blocks: List<ContentBlock>): ChatMessage =
            ChatMessage(Role.ASSISTANT, blocks)

        fun assistantText(text: String): ChatMessage =
            ChatMessage(Role.ASSISTANT, listOf(ContentBlock.Text(text)))

        fun toolResults(results: List<ContentBlock.ToolResult>): ChatMessage =
            ChatMessage(Role.TOOL, results)
    }
}
