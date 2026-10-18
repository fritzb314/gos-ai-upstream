package com.satory.graphenosai.service

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import com.satory.graphenosai.AppStateSnapshot
import com.satory.graphenosai.AssistantApplication
import com.satory.graphenosai.llm.ChatSession.Message
import com.satory.graphenosai.search.BraveSearchClient
import com.satory.graphenosai.storage.ChatHistoryManager
import com.satory.graphenosai.ui.SettingsManager
import com.satory.graphenosai.weather.OpenMeteoClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/**
 * The AssistantService paths that combine the model with web search or weather:
 * direct search, tool calling, DSML tool calls written as text, the "no internet" fallback and weather.
 * One local HTTPS server plays the self-hosted model, Brave Search and Open-Meteo.
 */
@RunWith(AndroidJUnit4::class)
class AssistantServiceFlowsTest {

    @get:Rule
    val serviceRule = ServiceTestRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val app get() = context.applicationContext as AssistantApplication
    private val settings by lazy { SettingsManager(context) }
    private val snapshot = AppStateSnapshot(context)
    private val server = MockWebServer()
    private lateinit var defaultSslFactory: SSLSocketFactory

    private val chatResponses = ConcurrentLinkedQueue<MockResponse>()
    private val requests = ConcurrentLinkedQueue<Pair<String, String>>()

    private val braveResults = """{"web":{"results":[
        {"title":"GrapheneOS 2026 release","url":"https://grapheneos.org/releases","description":"Latest release notes"}]}}"""
    private val geocoding = """{"results":[{"name":"Berlin","country":"Germany","latitude":52.52,"longitude":13.41,"timezone":"Europe/Berlin"}]}"""
    private val forecast = """{"timezone":"Europe/Berlin","current":{"time":"2026-10-16T12:00","temperature_2m":14.2,"weather_code":61},
        "daily":{"time":["2026-10-16"],"temperature_2m_max":[15],"temperature_2m_min":[9],"precipitation_sum":[3.1],"weather_code":[61]}}"""

    @Before
    fun setUp() {
        // Settings are read in onCreate(), so each test needs a fresh service instance.
        context.stopService(Intent(context, AssistantService::class.java))
        snapshot.save()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!
                requests += path to request.body.readUtf8()
                return when {
                    path.startsWith("/v1/chat/completions") -> chatResponses.poll() ?: MockResponse().setResponseCode(500)
                    path.startsWith("/brave") -> MockResponse().setBody(braveResults)
                    path.startsWith("/geo") -> MockResponse().setBody(geocoding)
                    path.startsWith("/forecast") -> MockResponse().setBody(forecast)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val localhost = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(localhost).build().sslSocketFactory(), false)
        server.start(InetAddress.getByName("localhost"), 0)
        defaultSslFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        HttpsURLConnection.setDefaultSSLSocketFactory(
            HandshakeCertificates.Builder().addTrustedCertificate(localhost.certificate).build().sslSocketFactory()
        )

        settings.ttsEnabled = false
        settings.apiProvider = SettingsManager.PROVIDER_HERMES
        settings.hermesBaseUrl = url("/v1")
        settings.hermesModel = "test-model"
        settings.searchEngine = SettingsManager.SEARCH_BRAVE
        app.secureKeyManager.apply {
            setHermesApiKey("test-key")
            setBraveApiKey("brave-key")
            clearExaApiKey()
            clearLangSearchApiKey()
        }
    }

    @After
    fun tearDown() {
        HttpsURLConnection.setDefaultSSLSocketFactory(defaultSslFactory)
        server.shutdown()
        snapshot.restore()
    }

    private fun url(path: String) = "https://localhost:${server.port}$path"

    private fun textStream(vararg chunks: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(
            chunks.joinToString("") { """data: {"choices":[{"delta":{"content":${JSONObject.quote(it)}}}]}""" + "\n\n" } +
                "data: [DONE]\n\n"
        )

    private fun toolCallStream(query: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"web_search","arguments":${JSONObject.quote("""{"query":"$query"}""")}}}]}}]}""" + "\n\n" +
                """data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""" + "\n\n" +
                "data: [DONE]\n\n"
        )

    private fun startService(): AssistantService {
        val service = (serviceRule.bindService(Intent(context, AssistantService::class.java)) as AssistantService.AssistantBinder).getService()
        // Android may hand back the previous test's instance (unbinding destroys it asynchronously),
        // so apply this test's settings and start from an empty chat explicitly.
        instrumentation.runOnMainSync {
            service.reloadSettings()
            service.clearSession(saveToHistory = false)
        }
        service.braveSearchClient = BraveSearchClient(app.secureKeyManager, url("/brave"))
        service.openMeteoClient = OpenMeteoClient(url("/geo"), url("/forecast"))
        return service
    }

    private fun ask(service: AssistantService, query: String): AssistantState {
        instrumentation.runOnMainSync { service.processTextQuery(query) }
        return runBlocking {
            withTimeout(60_000) {
                service.assistantState.first { it is AssistantState.Complete || it is AssistantState.Error }
            }
        }
    }

    private fun roles(service: AssistantService) = service.chatMessages.value.map { it.role }

    private fun waitFor(service: AssistantService, condition: (AssistantService) -> Boolean) = runBlocking {
        withTimeout(30_000) { service.response.first { condition(service) } }
    }

    private fun requestsTo(prefix: String) = requests.filter { it.first.startsWith(prefix) }

    private fun chatMessages(index: Int): JSONArray =
        JSONObject(requestsTo("/v1/chat/completions")[index].second).getJSONArray("messages")

    private fun JSONArray.last(): JSONObject = getJSONObject(length() - 1)

    @Test
    fun clearSearchQuestionSearchesFirstAndCitesSources() {
        chatResponses += textStream("The latest release is from 2026.")
        val service = startService()

        val state = ask(service, "search for the latest GrapheneOS release")

        assertEquals(AssistantState.Complete, state)
        assertTrue(requestsTo("/brave").single().first.contains("q=search+for+the+latest+GrapheneOS+release"))
        val prompt = chatMessages(0).last().getString("content")
        assertTrue(prompt.contains("https://grapheneos.org/releases"))
        assertTrue(prompt.contains("search for the latest GrapheneOS release"))
        assertEquals(
            "The latest release is from 2026.\n\n📚 Sources:\n1. https://grapheneos.org/releases",
            service.response.value
        )
        assertEquals(listOf("user", "assistant"), roles(service))
        assertEquals("search for the latest GrapheneOS release", service.chatMessages.value.first().content)
    }

    @Test
    fun modelCanRequestASearchAndGetsTheResultsInAFollowUp() {
        chatResponses += toolCallStream("GrapheneOS release")
        chatResponses += textStream("Here is what I found.")
        val service = startService()

        val state = ask(service, "Tell me something about GrapheneOS")

        assertEquals(AssistantState.Complete, state)
        val first = JSONObject(requestsTo("/v1/chat/completions")[0].second)
        assertEquals("web_search", first.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name"))
        assertTrue(requestsTo("/brave").single().first.contains("q=GrapheneOS+release"))

        val followUp = chatMessages(1)
        val toolRequest = followUp.getJSONObject(followUp.length() - 2)
        assertEquals("call_1", toolRequest.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        val toolResult = followUp.last()
        assertEquals("tool", toolResult.getString("role"))
        assertEquals("call_1", toolResult.getString("tool_call_id"))
        assertTrue(toolResult.getString("content").contains("https://grapheneos.org/releases"))

        assertTrue(service.response.value.startsWith("Here is what I found.\n\n📚 Sources:"))
        assertEquals(listOf("user", "assistant"), roles(service))
        assertEquals("Here is what I found.", service.chatMessages.value.last().content)
    }

    @Test
    fun modelThatDoesNotNeedASearchAnswersDirectly() {
        chatResponses += textStream("Kotlin is a programming language.")
        val service = startService()

        val state = ask(service, "Tell me something about Kotlin")

        assertEquals(AssistantState.Complete, state)
        assertEquals("Kotlin is a programming language.", service.response.value)
        assertTrue(requestsTo("/brave").isEmpty())
        assertEquals(1, requestsTo("/v1/chat/completions").size)
    }

    @Test
    fun toolCallWrittenAsDsmlTextIsExecuted() {
        chatResponses += textStream(
            """Let me look that up.<|DSML|tool_calls><|DSML|invoke name="web_search"><|DSML|parameter name="query" string="true">GrapheneOS news<|DSML|end>"""
        )
        chatResponses += textStream(" Found it.")
        val service = startService()

        val state = ask(service, "Tell me something about GrapheneOS")

        assertEquals(AssistantState.Complete, state)
        assertTrue(requestsTo("/brave").single().first.contains("q=GrapheneOS+news"))
        val followUpPrompt = chatMessages(1).last().getString("content")
        assertTrue(followUpPrompt.contains("Search results:"))
        assertTrue(followUpPrompt.contains("https://grapheneos.org/releases"))
        assertTrue(service.response.value.contains("Found it."))
        assertTrue(!service.response.value.contains("<|"))
    }

    @Ignore("Known bug: the raw DSML answer stays in the chat next to the follow-up answer")
    @Test
    fun dsmlFlowStoresOnlyTheFinalAnswer() {
        chatResponses += textStream(
            """Let me look that up.<|DSML|tool_calls><|DSML|invoke name="web_search"><|DSML|parameter name="query" string="true">GrapheneOS news<|DSML|end>"""
        )
        chatResponses += textStream(" Found it.")
        val service = startService()

        ask(service, "Tell me something about GrapheneOS")

        assertEquals(listOf("user", "assistant"), roles(service))
    }

    @Test
    fun modelClaimingNoInternetTriggersADirectSearch() {
        chatResponses += textStream("I'm sorry, I don't have access to the internet.")
        chatResponses += textStream("According to the search, it is out.")
        val service = startService()

        val state = ask(service, "Is the new Pixel out?")

        assertEquals(AssistantState.Complete, state)
        assertTrue(requestsTo("/brave").single().first.contains("q=Is+the+new+Pixel+out%3F"))
        assertTrue(chatMessages(1).last().getString("content").contains("https://grapheneos.org/releases"))
        assertTrue(service.response.value.startsWith("According to the search, it is out."))
    }

    @Ignore("Known bug: \"I don't have access to the internet\" stays in the chat next to the searched answer")
    @Test
    fun noInternetFallbackStoresOnlyTheSearchedAnswer() {
        chatResponses += textStream("I'm sorry, I don't have access to the internet.")
        chatResponses += textStream("According to the search, it is out.")
        val service = startService()

        ask(service, "Is the new Pixel out?")

        assertEquals(listOf("user", "assistant"), roles(service))
    }

    @Test
    fun weatherQuestionUsesOpenMeteoData() {
        chatResponses += textStream("It's 14°C and raining in Berlin.")
        val service = startService()

        val state = ask(service, "weather in Berlin tomorrow")

        assertEquals(AssistantState.Complete, state)
        assertTrue(requestsTo("/geo").single().first.contains("name=Berlin"))
        assertEquals(1, requestsTo("/forecast").size)
        val prompt = chatMessages(0).last().getString("content")
        assertTrue(prompt.contains("Current weather from Open-Meteo"))
        assertTrue(prompt.contains("Location: Berlin, Germany"))
        assertEquals("It's 14°C and raining in Berlin.\n\n📚 Sources:\n1. https://open-meteo.com/", service.response.value)
        assertEquals(listOf("user", "assistant"), roles(service))
    }

    @Test
    fun stoppingKeepsThePartialAnswerMarkedAsStopped() {
        chatResponses += textStream("Partial answer", " that never finishes", " at all").throttleBody(80, 1, TimeUnit.SECONDS)
        val service = startService()
        instrumentation.runOnMainSync { service.processTextQuery("Hi") }
        waitFor(service) { it.response.value.contains("Partial answer") }

        instrumentation.runOnMainSync { service.stopResponse() }

        assertEquals(AssistantState.Complete, service.assistantState.value)
        assertEquals("", service.response.value)
        assertEquals(listOf("user", "assistant"), roles(service))
        assertTrue(service.chatMessages.value.last().content.endsWith("\n\n[Stopped]"))
    }

    @Test
    fun continuingASavedChatSendsItsHistoryAndUpdatesThatChat() {
        val history = ChatHistoryManager(File(context.filesDir, "chat_history"))
        val chatId = history.saveChat(listOf(Message("user", "Earlier question"), Message("assistant", "Earlier answer")))
        val chatCount = history.getSavedChats().size
        chatResponses += textStream("New answer")
        val service = startService()

        instrumentation.runOnMainSync { service.loadChatFromHistory(chatId) }
        ask(service, "Follow-up")
        instrumentation.runOnMainSync { service.clearSession() }

        val sent = chatMessages(0)
        val sentContents = (0 until sent.length()).map { sent.getJSONObject(it).optString("content") }
        assertTrue(sentContents.containsAll(listOf("Earlier question", "Earlier answer", "Follow-up")))
        assertEquals(chatCount, history.getSavedChats().size)
        assertEquals(
            listOf("Earlier question", "Earlier answer", "Follow-up", "New answer"),
            history.loadChat(chatId)!!.map { it.content }
        )
    }

    @Test
    fun activationSavesTheCurrentChatAndStartsFresh() {
        val history = ChatHistoryManager(File(context.filesDir, "chat_history"))
        val chatCount = history.getSavedChats().size
        chatResponses += textStream("An answer")
        val service = startService()
        ask(service, "A question")

        try {
            serviceRule.startService(
                Intent(context, AssistantService::class.java)
                    .setAction(AssistantService.ACTION_ACTIVATE)
                    .putExtra(AssistantService.EXTRA_LAUNCH_OVERLAY, false)
            )
            runBlocking { withTimeout(10_000) { service.chatMessages.first { it.isEmpty() } } }

            assertEquals(chatCount + 1, history.getSavedChats().size)
            assertEquals(AssistantState.Idle, service.assistantState.value)
        } finally {
            // A started service outlives the binding; stop it so the next test gets a fresh instance.
            context.stopService(Intent(context, AssistantService::class.java))
        }
    }

    @Ignore("Known bug: reloadSettings() creates a new client with an empty session while the chat still shows the old messages")
    @Test
    fun changingSettingsKeepsTheConversation() {
        chatResponses += textStream("First answer")
        chatResponses += textStream("Second answer")
        val service = startService()
        ask(service, "First question")

        instrumentation.runOnMainSync { service.reloadSettings() }
        ask(service, "Second question")

        val sent = chatMessages(1)
        val sentContents = (0 until sent.length()).map { sent.getJSONObject(it).optString("content") }
        assertTrue(sentContents.containsAll(listOf("First question", "First answer", "Second question")))
    }

    @Test
    fun searchQuestionWithoutASearchKeyExplainsWhatIsMissing() {
        app.secureKeyManager.clearBraveApiKey()
        val service = startService()

        val state = ask(service, "search for the latest GrapheneOS release")

        assertTrue(state is AssistantState.Error)
        assertTrue(service.response.value.startsWith("Web search is enabled, but Brave Search is not configured."))
        assertTrue(requests.isEmpty())
    }
}
