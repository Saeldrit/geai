package com.github.saeldrit.geai.agent

import com.github.saeldrit.geai.llm.ChatMessage
import com.github.saeldrit.geai.llm.ChatRequest
import com.github.saeldrit.geai.llm.ChatResult
import com.github.saeldrit.geai.llm.ContentBlock
import com.github.saeldrit.geai.llm.LlmClient
import com.github.saeldrit.geai.llm.StopReason
import com.github.saeldrit.geai.llm.TokenUsage
import com.intellij.openapi.progress.ProgressIndicator

class FakeLlmClient(private val scripted: List<ChatResult>) : LlmClient {
    private var index = 0
    val callCount: Int get() = index

    override fun chat(request: ChatRequest, indicator: ProgressIndicator): ChatResult =
        scripted.getOrElse(index++) { endTurn("(end)") }

    companion object {
        fun toolUse(id: String, name: String, inputJson: String): ChatResult =
            ChatResult(
                ChatMessage.assistant(listOf(ContentBlock.ToolUse(id, name, inputJson))),
                StopReason.TOOL_USE,
                TokenUsage.ZERO,
            )

        fun endTurn(text: String): ChatResult =
            ChatResult(
                ChatMessage.assistant(listOf(ContentBlock.Text(text))),
                StopReason.END_TURN,
                TokenUsage.ZERO,
            )
    }
}
