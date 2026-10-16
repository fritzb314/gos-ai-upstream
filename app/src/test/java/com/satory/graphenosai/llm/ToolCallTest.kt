package com.satory.graphenosai.llm

import com.satory.graphenosai.search.SearchResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallTest {

    @Test
    fun `parses query from tool arguments`() {
        assertEquals("kotlin", parseSearchQuery("""{"query":"  kotlin  "}"""))
    }

    @Test
    fun `uses raw arguments when they are not json`() {
        assertEquals("plain text", parseSearchQuery(" plain text "))
    }

    @Test
    fun `web search tool definition matches what the model is told to call`() {
        val tools = WebSearchTool.buildToolsArray()
        val function = tools.getJSONObject(0).getJSONObject("function")
        assertEquals(1, tools.length())
        assertEquals("web_search", function.getString("name"))
        assertEquals("query", function.getJSONObject("parameters").getJSONArray("required").getString(0))
    }

    @Test
    fun `assistant tool message has null content and all calls`() {
        val calls = listOf(ToolCall("call_1", function = ToolFunctionCall("web_search", """{"query":"a"}""")))
        val message = buildToolAssistantMessage(calls)

        assertEquals("assistant", message.getString("role"))
        assertTrue(message.isNull("content"))
        val call = message.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("call_1", call.getString("id"))
        assertEquals("""{"query":"a"}""", call.getJSONObject("function").getString("arguments"))
    }

    @Test
    fun `tool result message references the call`() {
        val message = buildToolResultMessage("call_1", "results")
        assertEquals("tool", message.getString("role"))
        assertEquals("call_1", message.getString("tool_call_id"))
        assertEquals("results", message.getString("content"))
    }

    @Test
    fun `search results message lists each result`() {
        val text = buildSearchResultsMessage(listOf(SearchResult("Title", "https://a.com", "Snippet")))
        assertEquals("Title: Title\nURL: https://a.com\nSnippet", text)
    }

    @Test
    fun `search results message says when nothing was found`() {
        assertEquals("Web search returned no results.", buildSearchResultsMessage(emptyList()))
    }

    @Test
    fun `parses dsml tool call written as text and strips the tags`() {
        val text = """Let me search. <|DSML|tool_calls><|DSML|invoke name="web_search"><|DSML|parameter name="query" string="true">kotlin coroutines<|DSML|end>"""

        val (calls, cleaned) = parseDsmlToolCalls(text)

        assertEquals(1, calls.size)
        assertEquals("web_search", calls[0].function.name)
        assertEquals("kotlin coroutines", JSONObject(calls[0].function.arguments).getString("query"))
        assertFalse(cleaned.contains("<|"))
    }

    @Test
    fun `leaves text without dsml untouched`() {
        val text = "Just a normal answer."
        val (calls, cleaned) = parseDsmlToolCalls(text)
        assertTrue(calls.isEmpty())
        assertEquals(text, cleaned)
    }
}
