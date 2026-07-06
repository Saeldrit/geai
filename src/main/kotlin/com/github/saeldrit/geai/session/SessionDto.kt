package com.github.saeldrit.geai.session

import com.github.saeldrit.geai.agent.AgentSession
import com.github.saeldrit.geai.llm.ChatMessage
import com.github.saeldrit.geai.llm.ContentBlock
import com.github.saeldrit.geai.llm.Role
import com.github.saeldrit.geai.llm.TokenUsage
import java.util.UUID

/**
 * Flat, Gson-friendly mirror of a session. The domain uses a sealed [ContentBlock] hierarchy that
 * Gson cannot serialize polymorphically, so we round-trip through these DTOs with an explicit
 * `type` discriminator.
 */
internal data class SessionDto(
    var id: String = "",
    var title: String = "",
    var createdAtEpochMs: Long = 0,
    var inputTokens: Int = 0,
    var outputTokens: Int = 0,
    var messages: List<MessageDto> = emptyList(),
    var scratchpad: List<String> = emptyList(),
)

internal data class MessageDto(
    var role: String = "USER",
    var blocks: List<BlockDto> = emptyList(),
)

internal data class BlockDto(
    var type: String = "text",
    var text: String? = null,
    var id: String? = null,
    var name: String? = null,
    var inputJson: String? = null,
    var toolUseId: String? = null,
    var content: String? = null,
    var isError: Boolean = false,
)

/** Bidirectional mapping between [AgentSession] and its persisted [SessionDto]. */
internal object SessionCodec {

    fun toDto(session: AgentSession): SessionDto = SessionDto(
        id = session.id,
        title = session.title,
        createdAtEpochMs = session.createdAtEpochMs,
        inputTokens = session.totalUsage.inputTokens,
        outputTokens = session.totalUsage.outputTokens,
        messages = session.messages.map { message ->
            MessageDto(message.role.name, message.content.map(::blockToDto))
        },
        scratchpad = session.scratchpad.toList(),
    )

    fun fromDto(dto: SessionDto): AgentSession {
        val session = AgentSession(
            id = dto.id.ifBlank { UUID.randomUUID().toString() },
            title = dto.title.ifBlank { "Session" },
            createdAtEpochMs = if (dto.createdAtEpochMs == 0L) System.currentTimeMillis() else dto.createdAtEpochMs,
        )
        dto.messages.forEach { messageDto ->
            val role = runCatching { Role.valueOf(messageDto.role) }.getOrDefault(Role.USER)
            session.messages.add(ChatMessage(role, messageDto.blocks.mapNotNull(::blockFromDto)))
        }
        session.totalUsage = TokenUsage(dto.inputTokens, dto.outputTokens)
        session.scratchpad.addAll(dto.scratchpad)
        return session
    }

    private fun blockToDto(block: ContentBlock): BlockDto = when (block) {
        is ContentBlock.Text -> BlockDto(type = "text", text = block.text)
        is ContentBlock.ToolUse -> BlockDto(type = "tool_use", id = block.id, name = block.name, inputJson = block.inputJson)
        is ContentBlock.ToolResult -> BlockDto(type = "tool_result", toolUseId = block.toolUseId, content = block.content, isError = block.isError)
        is ContentBlock.Image -> BlockDto(type = "image", text = block.mediaType, content = block.base64Data)
    }

    private fun blockFromDto(dto: BlockDto): ContentBlock? = when (dto.type) {
        "text" -> ContentBlock.Text(dto.text.orEmpty())
        "tool_use" -> ContentBlock.ToolUse(dto.id.orEmpty(), dto.name.orEmpty(), dto.inputJson ?: "{}")
        "tool_result" -> ContentBlock.ToolResult(dto.toolUseId.orEmpty(), dto.content.orEmpty(), dto.isError)
        "image" -> ContentBlock.Image(base64Data = dto.content.orEmpty(), mediaType = dto.text.orEmpty())
        else -> null
    }
}

/** Lightweight session descriptor for listing without loading full transcripts. */
data class SessionMeta(
    val id: String,
    val title: String,
    val updatedAtEpochMs: Long,
    val messageCount: Int,
)
