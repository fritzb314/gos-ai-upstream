package com.satory.graphenosai.llm

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSessionTest {

    @Test
    fun `keeps at most 30 messages, dropping the oldest`() {
        val session = ChatSession()
        repeat(31) { session.addUserMessage("message $it") }

        assertEquals(30, session.messageCount())
        assertEquals("message 1", session.getAllMessages().first().content)
    }

    @Test
    fun `drops oldest messages when the estimated context gets too long`() {
        val session = ChatSession()
        session.addUserMessage("a".repeat(40_000))
        session.addAssistantMessage("b".repeat(40_000))
        session.addUserMessage("c")

        assertEquals(listOf("assistant", "user"), session.getAllMessages().map { it.role })
    }

    @Test
    fun `api messages start with the system prompt`() {
        val session = ChatSession()
        session.addUserMessage("hi")
        session.addAssistantMessage("hello")

        val messages = session.getMessagesForApi("be brief")

        assertEquals(3, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("be brief", messages.getJSONObject(0).getString("content"))
        assertEquals("hello", messages.getJSONObject(2).getString("content"))
    }

    @Test
    fun `blank messages get placeholder content`() {
        val session = ChatSession()
        session.addUserMessage(" ")
        session.addUserMessage("", imageBase64 = "AAAA")

        val messages = session.getMessagesForApi("sys")

        assertEquals("...", messages.getJSONObject(1).getString("content"))
        assertEquals("Describe this image", messages.getJSONObject(2).getString("content"))
    }

    @Test
    fun `images are sent as content parts only when vision is enabled`() {
        val session = ChatSession()
        session.addUserMessage("what is this?", imageBase64 = "AAAA")

        val withVision = session.getMessagesForApi("sys", includeVision = true).getJSONObject(1).get("content") as JSONArray
        assertEquals("what is this?", withVision.getJSONObject(0).getString("text"))
        assertEquals("data:image/jpeg;base64,AAAA", withVision.getJSONObject(1).getJSONObject("image_url").getString("url"))

        val withoutVision = session.getMessagesForApi("sys", includeVision = false).getJSONObject(1)
        assertEquals("what is this?", withoutVision.getString("content"))
    }

    @Test
    fun `last messages, summary and clear`() {
        val session = ChatSession()
        assertFalse(session.hasMessages())
        assertNull(session.getLastUserMessage())
        assertEquals("New conversation", session.getConversationSummary())

        session.addUserMessage("q1")
        session.addAssistantMessage("a1")
        session.addUserMessage("q2")

        assertEquals("q2", session.getLastUserMessage())
        assertEquals("a1", session.getLastAssistantMessage())
        assertEquals("2 questions, 1 responses", session.getConversationSummary())

        session.clear()
        assertTrue(session.getAllMessages().isEmpty())
    }
}
