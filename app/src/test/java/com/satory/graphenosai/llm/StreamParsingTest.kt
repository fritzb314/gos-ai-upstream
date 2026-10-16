package com.satory.graphenosai.llm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamParsingTest {

    private fun events(vararg lines: String) = sseJsonEvents(lines.asSequence()).toList()

    private fun chunk(content: String) = """data: {"choices":[{"delta":{"content":"$content"}}]}"""

    @Test
    fun `parses data lines and stops at done`() {
        val parsed = events(chunk("Hel"), chunk("lo"), "data: [DONE]", chunk("ignored"))
        assertEquals(listOf("Hel", "lo"), parsed.map(::deltaContent))
    }

    @Test
    fun `skips comments, blank lines, empty and malformed payloads`() {
        val parsed = events(": keep-alive", "", "data: ", "data: not json", "event: ping", chunk("ok"))
        assertEquals(listOf("ok"), parsed.map(::deltaContent))
    }

    @Test
    fun `delta content is empty when content is null or missing`() {
        assertEquals("", deltaContent(JSONObject("""{"choices":[{"delta":{"content":null}}]}""")))
        assertEquals("", deltaContent(JSONObject("""{"choices":[{"delta":{"role":"assistant"}}]}""")))
        assertEquals("", deltaContent(JSONObject("""{"choices":[]}""")))
        assertEquals("", deltaContent(JSONObject("""{"id":"x"}""")))
    }

    @Test
    fun `delta content ignores non-string content`() {
        assertEquals("", deltaContent(JSONObject("""{"choices":[{"delta":{"content":[{"type":"text"}]}}]}""")))
    }

    @Test
    fun `merges tool call chunks and concatenates arguments`() {
        val acc = JSONObject()
        mergeToolCallDelta(acc, JSONObject("""{"index":0,"id":"call_1","type":"function","function":{"name":"web_search","arguments":"{\"qu"}}"""))
        mergeToolCallDelta(acc, JSONObject("""{"index":0,"function":{"arguments":"ery\":\"kotlin\"}"}}"""))

        assertEquals("call_1", acc.getString("id"))
        assertEquals("web_search", acc.getJSONObject("function").getString("name"))
        assertEquals("""{"query":"kotlin"}""", acc.getJSONObject("function").getString("arguments"))
    }

    @Test
    fun `null fields in a chunk do not overwrite earlier values`() {
        val acc = JSONObject()
        mergeToolCallDelta(acc, JSONObject("""{"id":"call_1","function":{"name":"web_search"}}"""))
        mergeToolCallDelta(acc, JSONObject("""{"id":null,"function":{"name":null,"arguments":"{}"}}"""))

        assertEquals("call_1", acc.getString("id"))
        assertEquals("web_search", acc.getJSONObject("function").getString("name"))
    }

    @Test
    fun `assembles complete tool calls in index order`() {
        val calls = assembleToolCalls(
            mapOf(
                1 to JSONObject("""{"id":"call_2","function":{"name":"web_search","arguments":"{\"query\":\"b\"}"}}"""),
                0 to JSONObject("""{"id":"call_1","function":{"name":"web_search","arguments":"{\"query\":\"a\"}"}}""")
            )
        )
        assertEquals(listOf("call_1", "call_2"), calls.map { it.id })
        assertEquals("""{"query":"a"}""", calls[0].function.arguments)
    }

    @Test
    fun `drops tool calls without id or name and defaults arguments`() {
        val calls = assembleToolCalls(
            mapOf(
                0 to JSONObject("""{"id":"call_1","function":{"name":"web_search"}}"""),
                1 to JSONObject("""{"function":{"name":"web_search"}}"""),
                2 to JSONObject("""{"id":"call_3","function":{}}""")
            )
        )
        assertEquals(1, calls.size)
        assertEquals("{}", calls[0].function.arguments)
        assertTrue(calls.all { it.type == "function" })
    }
}
