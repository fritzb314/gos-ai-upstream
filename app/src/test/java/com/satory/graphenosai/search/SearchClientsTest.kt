package com.satory.graphenosai.search

import com.satory.graphenosai.security.SecureKeyManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SearchClientsTest {

    private val server = MockWebServer()
    private val keyManager = mockk<SecureKeyManager> {
        every { getBraveApiKey() } returns "brave-key"
        every { getExaApiKey() } returns "exa-key"
        every { getLangSearchApiKey() } returns "lang-key"
    }

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    private fun url(path: String) = server.url(path).toString()

    @Test
    fun `brave sends query and key and parses web results`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"web":{"results":[{"title":"Kotlin","url":"https://kotlinlang.org","description":"A language"}]}}"""
            )
        )

        val results = BraveSearchClient(keyManager, url("/search")).search("kotlin lang", maxResults = 3)

        assertEquals(listOf(SearchResult("Kotlin", "https://kotlinlang.org", "A language")), results)
        val request = server.takeRequest()
        assertEquals("/search?q=kotlin+lang&count=3&safesearch=moderate", request.path)
        assertEquals("brave-key", request.getHeader("X-Subscription-Token"))
    }

    @Test
    fun `brave returns at most max results`() = runTest {
        val items = (1..5).joinToString(",") { """{"title":"t$it","url":"u$it","description":"d$it"}""" }
        server.enqueue(MockResponse().setBody("""{"web":{"results":[$items]}}"""))

        assertEquals(2, BraveSearchClient(keyManager, url("/search")).search("q", maxResults = 2).size)
    }

    @Test
    fun `brave without key makes no request`() = runTest {
        every { keyManager.getBraveApiKey() } returns null

        assertTrue(BraveSearchClient(keyManager, url("/search")).search("q").isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `brave errors and bad json give no results`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setBody("not json"))
        val client = BraveSearchClient(keyManager, url("/search"))

        assertTrue(client.search("q").isEmpty())
        assertTrue(client.search("q").isEmpty())
    }

    @Test
    fun `exa posts the query and uses text or summary as snippet`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"results":[
                    {"title":"A","url":"https://a","text":"${"x".repeat(600)}"},
                    {"title":"B","url":"https://b","text":"","summary":"short summary"}]}"""
            )
        )

        val results = ExaSearchClient(keyManager, url("/search")).search("kotlin", maxResults = 4)

        assertEquals("x".repeat(500), results[0].snippet)
        assertEquals("short summary", results[1].snippet)
        val request = server.takeRequest()
        assertEquals("exa-key", request.getHeader("x-api-key"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("kotlin", body.getString("query"))
        assertEquals(4, body.getInt("numResults"))
    }

    @Test
    fun `exa errors give no results`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("bad key"))
        assertTrue(ExaSearchClient(keyManager, url("/search")).search("q").isEmpty())
    }

    @Test
    fun `langsearch posts the query with bearer key and parses results`() = runTest {
        server.enqueue(MockResponse().setBody("""{"results":[{"title":"A","url":"https://a","snippet":"s"}]}"""))

        val results = LangSearchClient(keyManager, url("/search")).search("kotlin", maxResults = 2)

        assertEquals(listOf(SearchResult("A", "https://a", "s")), results)
        val request = server.takeRequest()
        assertEquals("Bearer lang-key", request.getHeader("Authorization"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("kotlin", body.getString("q"))
        assertEquals(2, body.getInt("max_results"))
    }

    @Test
    fun `langsearch also understands the bing style format`() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"webPages":{"value":[{"name":"A","url":"https://a","snippet":"","summary":"sum"}]}}""")
        )

        assertEquals(
            listOf(SearchResult("A", "https://a", "sum")),
            LangSearchClient(keyManager, url("/search")).search("q")
        )
    }

    @Test
    fun `langsearch unknown format gives no results`() = runTest {
        server.enqueue(MockResponse().setBody("""{"something":"else"}"""))
        assertTrue(LangSearchClient(keyManager, url("/search")).search("q").isEmpty())
    }
}
