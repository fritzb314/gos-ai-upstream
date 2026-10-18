package com.satory.graphenosai.llm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs the real llama.cpp native library with a 1.2 MB model. Skipped when the app was built with the stub. */
@RunWith(AndroidJUnit4::class)
class LlamaCppSmokeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val cacheDir = instrumentation.targetContext.cacheDir
    private val modelFile = File(cacheDir, "stories260K.gguf")
    // The default system prompt alone is ~700 tokens with this model's tiny vocabulary.
    private val client = LlamaCppClient().apply {
        contextSize = 2048
        maxTokens = 32
    }

    @Before
    fun setUp() {
        assumeTrue("llama.cpp native library not built", LlamaCppBridge.isAvailable())
        instrumentation.context.assets.open("stories260K.gguf").use { input ->
            modelFile.outputStream().use { input.copyTo(it) }
        }
    }

    @After
    fun tearDown() {
        client.unloadModel()
        modelFile.delete()
    }

    @Test
    fun loadsATinyModelAndGeneratesText() = runBlocking {
        assertTrue(client.initialize())
        assertTrue(client.loadModel(modelFile.absolutePath, "stories260K").isSuccess)
        assertTrue(client.isModelLoaded())

        client.chatSession.addUserMessage("Once upon a time")
        val text = client.streamCompletion("Once upon a time").toList().joinToString("")

        assertTrue("model produced no text", text.isNotBlank())
        assertEquals(listOf("user", "assistant"), client.chatSession.getAllMessages().map { it.role })

        client.unloadModel()
        assertFalse(client.isModelLoaded())
    }

    @Test
    fun missingModelFileFailsCleanly() = runBlocking {
        client.initialize()
        assertTrue(client.loadModel(File(cacheDir, "does-not-exist.gguf").absolutePath).isFailure)
        assertFalse(client.isModelLoaded())
    }

    @Test
    fun corruptModelFileFailsCleanly() = runBlocking {
        val junk = File(cacheDir, "junk.gguf").apply { writeText("this is not a model") }
        try {
            client.initialize()
            assertTrue(client.loadModel(junk.absolutePath).isFailure)
        } finally {
            junk.delete()
        }
    }

    @Test
    fun streamingWithoutAModelExplainsWhatToDo() = runBlocking {
        client.unloadModel()
        val text = client.streamCompletion("hi").toList().joinToString("")
        assertEquals("[No local model loaded. Please download a model in Settings.]", text)
    }
}
