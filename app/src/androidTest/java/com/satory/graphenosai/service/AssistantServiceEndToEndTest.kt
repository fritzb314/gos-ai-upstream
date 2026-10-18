package com.satory.graphenosai.service

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import com.satory.graphenosai.AppStateSnapshot
import com.satory.graphenosai.AssistantApplication
import com.satory.graphenosai.llm.LlamaCppBridge
import com.satory.graphenosai.llm.LocalModelManager
import com.satory.graphenosai.llm.OpenRouterClient
import com.satory.graphenosai.search.BraveSearchClient
import com.satory.graphenosai.storage.ChatHistoryManager
import com.satory.graphenosai.ui.SettingsManager
import com.satory.graphenosai.weather.OpenMeteoClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/**
 * Drives the real AssistantService: text query in, streamed answer out.
 * Cloud answers come from a local HTTPS server posing as a self-hosted endpoint.
 */
@RunWith(AndroidJUnit4::class)
class AssistantServiceEndToEndTest {

    @get:Rule
    val serviceRule = ServiceTestRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val app get() = context.applicationContext as AssistantApplication
    private val settings by lazy { SettingsManager(context) }
    private val snapshot = AppStateSnapshot(context)
    private val server = MockWebServer()
    private lateinit var defaultSslFactory: SSLSocketFactory

    @Before
    fun setUp() {
        // Settings are read in onCreate(), so each test needs a fresh service instance.
        context.stopService(Intent(context, AssistantService::class.java))
        snapshot.save()

        val localhost = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(localhost).build().sslSocketFactory(), false)
        server.start(InetAddress.getByName("localhost"), 0)
        defaultSslFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        HttpsURLConnection.setDefaultSSLSocketFactory(
            HandshakeCertificates.Builder().addTrustedCertificate(localhost.certificate).build().sslSocketFactory()
        )

        settings.ttsEnabled = false
        app.secureKeyManager.apply {
            clearBraveApiKey()
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

    private fun useSelfHostedServer() {
        settings.apiProvider = SettingsManager.PROVIDER_HERMES
        settings.hermesBaseUrl = url("/v1")
        settings.hermesModel = "test-model"
        app.secureKeyManager.setHermesApiKey("test-key")
    }

    private fun sse(vararg chunks: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(
            chunks.joinToString("") { """data: {"choices":[{"delta":{"content":${JSONObject.quote(it)}}}]}""" + "\n\n" } +
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

    @Test
    fun answersThroughASelfHostedServer() {
        useSelfHostedServer()
        server.enqueue(sse("Hello", " from the server"))
        val service = startService()

        val state = ask(service, "Hi there")

        assertEquals(AssistantState.Complete, state)
        assertEquals("Hello from the server", service.response.value)
        assertEquals(listOf("user", "assistant"), service.chatMessages.value.map { it.role })

        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("test-model", body.getString("model"))
        val messages = body.getJSONArray("messages")
        assertEquals("Hi there", messages.getJSONObject(messages.length() - 1).getString("content"))
    }

    @Test
    fun serverErrorsAreShownToTheUser() {
        useSelfHostedServer()
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        val service = startService()

        ask(service, "Hi")

        assertTrue(service.response.value, service.response.value.contains("Error 500"))
    }

    @Test
    fun finishedChatIsSavedToHistory() {
        useSelfHostedServer()
        server.enqueue(sse("Saved answer"))
        val history = ChatHistoryManager(File(context.filesDir, "chat_history"))
        val before = history.getSavedChats().size
        val service = startService()

        ask(service, "Remember this")
        instrumentation.runOnMainSync { service.clearSession() }

        val chats = history.getSavedChats()
        assertEquals(before + 1, chats.size)
        assertTrue(chats.any { it.title == "Remember this" && it.preview == "Saved answer" })
    }

    @Test
    fun answersOfflineWithALocalModel() {
        assumeTrue("llama.cpp native library not built", LlamaCppBridge.isAvailable())
        val model = LocalModelManager.AVAILABLE_MODELS.first { it.id == "smollm3-3b" }
        val modelFile = File(context.filesDir, "local_models/${model.filename}")
        assumeFalse("a real ${model.filename} is downloaded; not replacing it", modelFile.exists())
        modelFile.parentFile!!.mkdirs()
        instrumentation.context.assets.open("stories260K.gguf").use { input ->
            modelFile.outputStream().use { input.copyTo(it) }
        }
        settings.apiProvider = SettingsManager.PROVIDER_LOCAL
        settings.localModelId = model.id
        app.secureKeyManager.setBraveApiKey("brave-key")
        val service = startService()
        // Every network client points at the local server, so any request would be counted.
        service.openRouterClient = OpenRouterClient(app.secureKeyManager, baseUrlOverride = url("/v1"), apiKeyOverride = "key")
        service.braveSearchClient = BraveSearchClient(app.secureKeyManager, url("/brave"))
        service.openMeteoClient = OpenMeteoClient(url("/geo"), url("/forecast"))

        val state = ask(service, "search for the latest news about the weather in Berlin")

        assertEquals(AssistantState.Complete, state)
        assertTrue(service.response.value.isNotBlank())
        assertFalse(service.response.value, service.response.value.startsWith("["))
        assertEquals(listOf("user", "assistant"), service.chatMessages.value.map { it.role })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun jsonNullContentIsNotShownAsText() {
        useSelfHostedServer()
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                """data: {"choices":[{"delta":{"role":"assistant","content":null}}]}""" + "\n\n" +
                    """data: {"choices":[{"delta":{"content":"Hello"}}]}""" + "\n\n" +
                    "data: [DONE]\n\n"
            )
        )
        val service = startService()

        ask(service, "Hi")

        assertEquals("Hello", service.response.value)
    }

    @Ignore("Known bug: the raw query is stored in the chat session and sent from there; the no-internet fallback also searches with it")
    @Test
    fun personalDataIsRedactedBeforeItLeavesTheDevice() {
        useSelfHostedServer()
        server.enqueue(sse("ok"))
        val service = startService()

        ask(service, "Mail me at jane@example.com")

        val messages = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
        assertEquals("Mail me at [EMAIL]", messages.getJSONObject(messages.length() - 1).getString("content"))
    }
}
