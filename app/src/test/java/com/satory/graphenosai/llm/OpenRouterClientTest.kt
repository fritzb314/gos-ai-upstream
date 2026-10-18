package com.satory.graphenosai.llm

import com.satory.graphenosai.security.SecureKeyManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class OpenRouterClientTest {

    private val server = MockWebServer()
    private val keyManager = mockk<SecureKeyManager> {
        every { getOpenRouterApiKey() } returns "sk-or-real-openrouter-key"
    }
    private lateinit var client: OpenRouterClient

    @Before
    fun setUp() {
        server.start()
        client = OpenRouterClient(
            keyManager,
            modelOverride = "test-model",
            baseUrlOverride = server.url("/v1").toString(),
            apiKeyOverride = "test-key"
        )
    }

    @After
    fun tearDown() = server.shutdown()

    private fun sse(vararg payloads: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(payloads.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n")

    private fun text(content: String) = """{"choices":[{"delta":{"content":${JSONObject.quote(content)}}}]}"""

    private fun RecordedRequest.json() = JSONObject(body.readUtf8())

    @Test
    fun `streams text chunks and stores the answer in the session`() = runTest {
        server.enqueue(sse(text("Hello"), text(" world")))
        client.chatSession.addUserMessage("Hi")

        val chunks = client.streamCompletion("Hi").toList()

        assertEquals(listOf("Hello", " world"), chunks)
        assertEquals("Hello world", client.chatSession.getLastAssistantMessage())
    }

    @Test
    fun `sends model, history and api key to the configured endpoint`() = runTest {
        server.enqueue(sse(text("ok")))
        client.chatSession.addUserMessage("What is Kotlin?")

        client.streamCompletion("What is Kotlin?").toList()

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        val body = request.json()
        assertEquals("test-model", body.getString("model"))
        assertTrue(body.getBoolean("stream"))
        val messages = body.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("What is Kotlin?", messages.getJSONObject(1).getString("content"))
    }

    @Test
    fun `json null content does not show up as text`() = runTest {
        server.enqueue(sse("""{"choices":[{"delta":{"role":"assistant","content":null}}]}""", text("Hi")))
        client.chatSession.addUserMessage("q")

        assertEquals(listOf("Hi"), client.streamCompletion("q").toList())
    }

    @Test
    fun `http errors are reported in the stream and nothing is stored`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("invalid key"))
        client.chatSession.addUserMessage("q")

        val chunks = client.streamCompletion("q").toList()

        assertEquals(listOf("[Error 401: invalid key]"), chunks)
        assertEquals(null, client.chatSession.getLastAssistantMessage())
    }

    @Test
    fun `a dropped connection is reported as an error`() = runTest {
        // Two responses because the JVM's HttpURLConnection silently retries a failed POST once.
        repeat(2) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)) }
        client.chatSession.addUserMessage("q")

        val chunks = client.streamCompletion("q").toList()

        assertEquals(1, chunks.size)
        assertTrue(chunks.single(), chunks.single().startsWith("[Error"))
    }

    @Test
    fun `never sends the openrouter key to a custom endpoint`() = runTest {
        val selfHosted = OpenRouterClient(keyManager, baseUrlOverride = server.url("/v1").toString())
        selfHosted.chatSession.addUserMessage("q")

        val chunks = selfHosted.streamCompletion("q").toList()

        assertEquals(listOf("[API key not configured. Add your key in Settings.]"), chunks)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `custom endpoint keeps its model when rotating fallbacks`() = runTest {
        server.enqueue(sse(text("ok")))
        client.rotateFallbackModel()
        client.chatSession.addUserMessage("q")

        client.streamCompletion("q").toList()

        assertEquals("test-model", server.takeRequest().json().getString("model"))
    }

    @Test
    fun `direct query replaces the last user message but keeps history`() = runTest {
        server.enqueue(sse(text("ok")))
        client.chatSession.addUserMessage("first")
        client.chatSession.addAssistantMessage("answer")
        client.chatSession.addUserMessage("original question")

        client.streamCompletionDirect("question with search results").toList()

        val messages = server.takeRequest().json().getJSONArray("messages")
        assertEquals(listOf("system", "user", "assistant", "user"), (0 until messages.length()).map { messages.getJSONObject(it).getString("role") })
        assertEquals("question with search results", messages.getJSONObject(3).getString("content"))
        assertEquals("ok", client.chatSession.getLastAssistantMessage())
    }

    @Test
    fun `session stream adds the user message itself`() = runTest {
        server.enqueue(sse(text("answer")))

        client.streamCompletionWithSession("question").toList()

        assertEquals(listOf("user", "assistant"), client.chatSession.getAllMessages().map { it.role })
    }

    @Test
    fun `detects streamed tool calls and leaves the session for the follow-up`() = runTest {
        server.enqueue(
            sse(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"web_search","arguments":"{\"qu"}}]}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ery\":\"kotlin\"}"}}]}}]}""",
                """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}"""
            )
        )
        client.chatSession.addUserMessage("search kotlin")

        val events = client.streamCompletionWithToolSupport(WebSearchTool.buildToolsArray()).toList()

        val detected = events.single() as StreamEvent.ToolCallsDetected
        assertEquals("call_1", detected.calls.single().id)
        assertEquals("""{"query":"kotlin"}""", detected.calls.single().function.arguments)
        assertEquals(1, client.chatSession.messageCount())

        val body = server.takeRequest().json()
        assertEquals("auto", body.getString("tool_choice"))
        assertEquals("web_search", body.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name"))
    }

    @Test
    fun `tool stream without tool calls behaves like a normal answer`() = runTest {
        server.enqueue(sse(text("Paris"), """{"choices":[{"delta":{},"finish_reason":"stop"}]}"""))
        client.chatSession.addUserMessage("capital of France?")

        val events = client.streamCompletionWithToolSupport(WebSearchTool.buildToolsArray()).toList()

        assertEquals(listOf(StreamEvent.Content("Paris")), events)
        assertEquals("Paris", client.chatSession.getLastAssistantMessage())
    }

    @Test
    fun `incomplete tool calls fall back to the text answer`() = runTest {
        server.enqueue(
            sse(
                text("Let me think."),
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"web_search"}}]},"finish_reason":"tool_calls"}]}"""
            )
        )
        client.chatSession.addUserMessage("q")

        val events = client.streamCompletionWithToolSupport(WebSearchTool.buildToolsArray()).toList()

        assertFalse(events.any { it is StreamEvent.ToolCallsDetected })
        assertEquals("Let me think.", client.chatSession.getLastAssistantMessage())
    }

    @Test
    fun `explicit messages are sent as given and the session is untouched`() = runTest {
        server.enqueue(sse(text("final answer")))
        val messages = JSONArray()
            .put(JSONObject().put("role", "user").put("content", "q"))
            .put(buildToolResultMessage("call_1", "results"))

        val chunks = client.streamWithMessages(messages).toList()

        assertEquals(listOf("final answer"), chunks)
        assertEquals(2, server.takeRequest().json().getJSONArray("messages").length())
        assertEquals(0, client.chatSession.messageCount())
    }

    @Test
    fun `non-streaming completion returns the message and redacts personal data`() = runTest {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"Done."}}]}"""))

        val answer = client.completion("mail me at a@b.com, card 4111 1111 1111 1111")

        assertEquals("Done.", answer)
        val sent = server.takeRequest().json().getJSONArray("messages").getJSONObject(1).getString("content")
        assertEquals("mail me at [EMAIL], card [CARD]", sent)
    }

    @Test
    fun `non-streaming completion throws on http errors`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("rate limited"))

        try {
            client.completion("q")
            fail("expected OpenRouterException")
        } catch (e: OpenRouterException) {
            assertEquals(429, e.code)
        }
    }
}
