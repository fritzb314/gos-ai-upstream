package com.satory.graphenosai.storage

import com.satory.graphenosai.llm.ChatSession.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChatHistoryManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val manager by lazy { ChatHistoryManager(File(tmp.root, "chat_history")) }

    private val conversation = listOf(
        Message("user", "What is Kotlin?", timestamp = 1_000),
        Message("assistant", "A programming language.", timestamp = 2_000),
        Message("user", "Show me", timestamp = 3_000, imageBase64 = "AAAA")
    )

    @Test
    fun `saved chat loads back identically, including images`() {
        val id = manager.saveChat(conversation)
        assertEquals(conversation, manager.loadChat(id))
    }

    @Test
    fun `creates the history folder when missing`() {
        val nested = File(tmp.root, "a/b/chat_history")
        ChatHistoryManager(nested).saveChat(conversation)
        assertTrue(nested.isDirectory)
    }

    @Test
    fun `title comes from the first user message, shortened after 50 characters`() {
        manager.saveChat(listOf(Message("user", "x".repeat(60))))
        manager.saveChat(listOf(Message("user", "short question")))

        val titles = manager.getSavedChats().map { it.title }.toSet()
        assertEquals(setOf("x".repeat(50) + "...", "short question"), titles)
    }

    @Test
    fun `explicit title is used as given`() {
        manager.saveChat(conversation, title = "My chat")
        assertEquals("My chat", manager.getSavedChats().single().title)
    }

    @Test
    fun `summary shows last assistant message as preview`() {
        val id = manager.saveChat(conversation)
        val summary = manager.getSavedChats().single()

        assertEquals(id, summary.id)
        assertEquals(3, summary.messageCount)
        assertEquals("A programming language.", summary.preview)
    }

    @Test
    fun `long previews are shortened after 100 characters`() {
        manager.saveChat(listOf(Message("user", "q"), Message("assistant", "y".repeat(150))))
        assertEquals("y".repeat(100) + "...", manager.getSavedChats().single().preview)
    }

    @Test
    fun `update replaces messages but keeps the title`() {
        val id = manager.saveChat(conversation, title = "Kotlin")
        val updated = conversation + Message("assistant", "Here you go.", timestamp = 4_000)

        assertTrue(manager.updateChat(id, updated))
        assertEquals(updated, manager.loadChat(id))
        assertEquals("Kotlin", manager.getSavedChats().single().title)
    }

    @Test
    fun `updating an unknown chat saves it as a new one`() {
        assertFalse(manager.updateChat("missing", conversation))
        assertEquals(1, manager.getSavedChats().size)
    }

    @Test
    fun `delete removes a chat`() {
        val id = manager.saveChat(conversation)
        assertTrue(manager.deleteChat(id))
        assertNull(manager.loadChat(id))
        assertFalse(manager.deleteChat(id))
    }

    @Test
    fun `clear removes all chats`() {
        manager.saveChat(conversation)
        manager.saveChat(conversation)
        manager.clearAllChats()
        assertTrue(manager.getSavedChats().isEmpty())
    }

    @Test
    fun `keeps at most 50 chats`() {
        repeat(51) { manager.saveChat(conversation) }
        assertEquals(50, manager.getSavedChats().size)
    }

    @Test
    fun `corrupt files are skipped instead of breaking the list`() {
        manager.saveChat(conversation)
        File(tmp.root, "chat_history/broken.json").writeText("{ not json")

        assertEquals(1, manager.getSavedChats().size)
        assertNull(manager.loadChat("broken"))
    }
}
