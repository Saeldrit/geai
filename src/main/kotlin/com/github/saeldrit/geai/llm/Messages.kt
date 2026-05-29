package com.github.saeldrit.geai.llm

/**
 * Conversation role. Tool results are carried by a dedicated [Role.TOOL] message so each
 * provider adapter can map them to its own wire shape (Anthropic: a `user` message with
 * `tool_result` blocks; OpenAI: one `tool` message per result).
 */
enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

/** A single piece of message content. Immutable by construction. */
sealed interface ContentBlock {

    /** Plain text, possibly streamed/aggregated. */
    data class Text(val text: String) : ContentBlock

    /** Model's request to invoke a tool. [inputJson] is the raw JSON object argument. */
    data class ToolUse(
        val id: String,
        val name: String,
        val inputJson: String,
    ) : ContentBlock

    /** Result of a tool invocation, referencing the originating [toolUseId]. */
    data class ToolResult(
        val toolUseId: String,
        val content: String,
        val isError: Boolean = false,
    ) : ContentBlock
}

/** An immutable conversation turn. */
data class ChatMessage(
    val role: Role,
    val content: List<ContentBlock>,
) {
    /** Concatenated text of all [ContentBlock.Text] blocks. */
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
