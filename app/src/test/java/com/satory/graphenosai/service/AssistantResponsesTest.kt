package com.satory.graphenosai.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantResponsesTest {

    @Test
    fun `detects url after open verb and trims trailing punctuation`() {
        assertEquals(listOf("https://example.com/page"), detectUrlsToOpen("Opening https://example.com/page."))
    }

    @Test
    fun `detects explicit open url tag`() {
        assertEquals(listOf("https://example.com"), detectUrlsToOpen("Sure. [OPEN_URL:https://example.com]"))
    }

    @Test
    fun `detects russian open phrase`() {
        assertEquals(listOf("https://example.ru"), detectUrlsToOpen("Открываю https://example.ru"))
    }

    @Test
    fun `returns each url only once`() {
        assertEquals(
            listOf("https://example.com"),
            detectUrlsToOpen("Opening https://example.com [OPEN_URL:https://example.com]")
        )
    }

    @Test
    fun `ignores urls that are only mentioned`() {
        assertTrue(detectUrlsToOpen("The docs are at https://example.com").isEmpty())
    }

    @Test
    fun `falls back to search when model claims no internet access`() {
        assertTrue(shouldFallbackToSearch("I'm sorry, but I don't have access to the internet."))
        assertTrue(shouldFallbackToSearch("I DO NOT HAVE WEB ACCESS right now"))
        assertTrue(shouldFallbackToSearch("К сожалению, у меня нет доступа к интернету."))
    }

    @Test
    fun `does not fall back for normal answers`() {
        assertFalse(shouldFallbackToSearch("The capital of France is Paris."))
    }
}
