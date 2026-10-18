package com.satory.graphenosai.llm

import com.satory.graphenosai.llm.ChatSession.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPromptTest {

    private val history = listOf(
        Message("user", "Hi"),
        Message("assistant", "Hello!"),
        Message("system", "ignored"),
        Message("user", "How are you?")
    )

    @Test
    fun `chatml prompt contains system, history and opens the assistant turn`() {
        assertEquals(
            "<|im_start|>system\nBe brief.<|im_end|>\n" +
                "<|im_start|>user\nHi<|im_end|>\n" +
                "<|im_start|>assistant\nHello!<|im_end|>\n" +
                "<|im_start|>user\nHow are you?<|im_end|>\n" +
                "<|im_start|>assistant\n",
            buildChatPrompt("chatml", "Be brief.", history)
        )
    }

    @Test
    fun `gemma prompt calls the assistant model`() {
        assertEquals(
            "<start_of_turn>system\nBe brief.<end_of_turn>\n" +
                "<start_of_turn>user\nHi<end_of_turn>\n" +
                "<start_of_turn>model\nHello!<end_of_turn>\n" +
                "<start_of_turn>model\n",
            buildChatPrompt("gemma", "Be brief.", history.take(2))
        )
    }

    @Test
    fun `final user message is added after the history`() {
        val prompt = buildChatPrompt("chatml", "sys", history.take(2), finalUserMessage = "Question with search results")
        assertTrue(prompt.endsWith("<|im_start|>user\nQuestion with search results<|im_end|>\n<|im_start|>assistant\n"))
    }

    private fun stream(filter: StopTokenFilter, vararg tokens: String) = tokens.joinToString("") { filter.accept(it) }

    @Test
    fun `stop filter passes normal text through`() {
        val filter = StopTokenFilter(listOf("<|im_end|>"))
        assertEquals("Hello world", stream(filter, "Hello", " world"))
    }

    @Test
    fun `stop filter cuts at a stop token and stops`() {
        val filter = StopTokenFilter(listOf("<|im_end|>"))
        assertEquals("Done.", stream(filter, "Done.<|im_end|>", "more"))
        assertTrue(filter.stopped)
        assertEquals("", filter.accept("anything"))
    }

    @Test
    fun `stop filter catches a stop token split across tokens`() {
        val filter = StopTokenFilter(listOf("<|im_end|>"))
        assertEquals("Hi", stream(filter, "Hi<|im", "_end|>"))
        assertTrue(filter.stopped)
    }

    @Test
    fun `stop filter releases held text that is not a stop token`() {
        val filter = StopTokenFilter(listOf("<|im_end|>"))
        assertEquals("a", filter.accept("a<"))
        assertEquals("<b", filter.accept("b"))
    }

    @Test
    fun `clean output removes leaked format tokens`() {
        assertEquals("Answer", LlamaCppClient.cleanOutput("Answer<|im_end|><|endoftext|>"))
        assertEquals("Answer", LlamaCppClient.cleanOutput("<start_of_turn>Answer<end_of_turn>", format = "gemma"))
    }
}
