package com.satory.graphenosai.llm

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalModelManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val content = ByteArray(1000) { (it % 251).toByte() }
    private lateinit var model: LocalModelInfo
    private lateinit var manager: LocalModelManager

    private val dir get() = File(tmp.root, "models")
    private val modelFile get() = File(dir, "test.gguf")
    private val tempFile get() = File(dir, "test.gguf.tmp")

    @Before
    fun setUp() {
        server.start()
        model = LocalModelInfo(
            id = "test",
            name = "Test model",
            description = "",
            sizeBytes = content.size.toLong(),
            downloadUrl = server.url("/test.gguf").toString(),
            filename = "test.gguf",
            contextSize = 2048
        )
        manager = LocalModelManager(dir, listOf(model))
    }

    @After
    fun tearDown() = server.shutdown()

    private fun body(bytes: ByteArray) = Buffer().write(bytes)

    private fun partialTempFile(length: Int) {
        dir.mkdirs()
        tempFile.writeBytes(content.copyOfRange(0, length))
    }

    private suspend fun download() = manager.downloadModel("test").toList()

    @Test
    fun `downloads a model and moves it into place`() = runTest {
        server.enqueue(MockResponse().setBody(body(content)))

        val events = download()

        assertEquals(DownloadProgress.Started("Test model"), events.first())
        assertEquals(DownloadProgress.Completed(modelFile.absolutePath), events.last())
        assertTrue(events.any { it is DownloadProgress.Downloading })
        assertArrayEquals(content, modelFile.readBytes())
        assertFalse(tempFile.exists())
        assertTrue(manager.isModelDownloaded("test"))
        assertEquals(modelFile.absolutePath, manager.getModelPath("test"))
    }

    @Test
    fun `resumes a partial download from where it stopped`() = runTest {
        partialTempFile(400)
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 400-999/1000")
                .setBody(body(content.copyOfRange(400, 1000)))
        )

        val events = download()

        assertEquals("bytes=400-", server.takeRequest().getHeader("Range"))
        assertEquals(DownloadProgress.Completed(modelFile.absolutePath), events.last())
        assertArrayEquals(content, modelFile.readBytes())
    }

    @Test
    fun `starts over when the server ignores the resume request`() = runTest {
        partialTempFile(400)
        server.enqueue(MockResponse().setBody(body(content)))

        download()

        assertArrayEquals(content, modelFile.readBytes())
    }

    @Test
    fun `starts over when the server resumes from the wrong offset`() = runTest {
        partialTempFile(400)
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-999/1000")
                .setBody(body(content))
        )
        server.enqueue(MockResponse().setBody(body(content)))

        download()

        server.takeRequest()
        assertNull(server.takeRequest().getHeader("Range"))
        assertArrayEquals(content, modelFile.readBytes())
    }

    @Test
    fun `finishes without downloading when the partial file is already complete`() = runTest {
        partialTempFile(1000)
        server.enqueue(MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */1000"))

        val events = download()

        assertEquals(DownloadProgress.Completed(modelFile.absolutePath), events.last())
        assertEquals(1, server.requestCount)
        assertArrayEquals(content, modelFile.readBytes())
    }

    @Test
    fun `starts over when the partial file no longer matches the remote file`() = runTest {
        partialTempFile(400)
        server.enqueue(MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */300"))
        server.enqueue(MockResponse().setBody(body(content)))

        download()

        assertEquals(2, server.requestCount)
        assertArrayEquals(content, modelFile.readBytes())
    }

    @Test
    fun `keeps an incomplete download for a later resume`() = runTest {
        partialTempFile(400)
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 400-999/1000")
                .setBody(body(content.copyOfRange(400, 700)))
        )

        val events = download()

        assertEquals(DownloadProgress.Error("Download incomplete: expected 1000 bytes, got 700"), events.last())
        assertFalse(modelFile.exists())
        assertEquals(700, tempFile.length())
    }

    @Test
    fun `reports http errors`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        assertEquals(DownloadProgress.Error("Download failed: HTTP 404"), download().last())
    }

    @Test
    fun `does not download again when the file has the expected size`() = runTest {
        dir.mkdirs()
        modelFile.writeBytes(content)

        assertEquals(listOf(DownloadProgress.Completed(modelFile.absolutePath)), download())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `unknown models are reported`() = runTest {
        assertEquals(
            listOf(DownloadProgress.Error("Model not found: nope")),
            manager.downloadModel("nope").toList()
        )
    }

    @Test
    fun `delete removes model and partial file, storage counts gguf files`() = runTest {
        dir.mkdirs()
        modelFile.writeBytes(content)
        tempFile.writeBytes(content)
        assertEquals(1000, manager.getTotalStorageUsed())

        assertTrue(manager.deleteModel("test"))
        assertFalse(modelFile.exists())
        assertFalse(tempFile.exists())
        assertFalse(manager.isModelDownloaded("test"))
    }

    @Test
    fun `formats model sizes for display`() {
        assertEquals("2.5 GB", model.copy(sizeBytes = 2_500_000_000).formattedSize())
        assertEquals("830.0 MB", model.copy(sizeBytes = 830_000_000).formattedSize())
    }
}
