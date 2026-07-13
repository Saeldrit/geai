package com.github.saeldrit.geai.session

import com.github.saeldrit.geai.agent.AgentSession
import com.github.saeldrit.geai.context.NoteEntry
import com.github.saeldrit.geai.llm.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionCodecTest {

    @Test
    fun `scratchpad survives a dto round-trip`() {
        val session = AgentSession(title = "T")
        session.scratchpad.add(NoteEntry("finding A at Foo.kt:10"))
        session.scratchpad.add(NoteEntry("decision: use the repository pattern"))

        val restored = SessionCodec.fromDto(SessionCodec.toDto(session))

        assertEquals(
            listOf("finding A at Foo.kt:10", "decision: use the repository pattern"),
            restored.scratchpad.map { it.text },
        )
    }

    @Test
    fun `messages and usage survive a dto round-trip`() {
        val session = AgentSession(title = "T")
        session.messages.add(ChatMessage.user("hello"))

        val restored = SessionCodec.fromDto(SessionCodec.toDto(session))

        assertEquals(1, restored.messages.size)
        assertEquals("hello", restored.messages.first().text)
    }
}
