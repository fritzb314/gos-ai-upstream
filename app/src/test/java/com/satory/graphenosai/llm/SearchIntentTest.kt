package com.satory.graphenosai.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchIntentTest {

    @Test
    fun `should search web detects search queries`() {
        assertTrue(shouldUseWebSearch("search for kotlin tutorials"))
        assertTrue(shouldUseWebSearch("find the latest news"))
        assertTrue(shouldUseWebSearch("look up android security"))
        assertTrue(shouldUseWebSearch("what is the latest GrapheneOS news today?"))
        assertTrue(shouldUseWebSearch("current Pixel 10 price in 2026"))
        assertTrue(shouldUseWebSearch("what is the current Android version?"))
    }

    @Test
    fun `should search web ignores non-search queries`() {
        assertFalse(shouldUseWebSearch("tell me a joke"))
        assertFalse(shouldUseWebSearch("write a poem"))
        assertFalse(shouldUseWebSearch("calculate 2+2"))
        assertFalse(shouldUseWebSearch("explain the difference between List and Set"))
        assertFalse(shouldUseWebSearch("translate hello world to Spanish"))
        assertFalse(shouldUseWebSearch("write Kotlin function to sort a list"))
    }

    @Test
    fun `retrieval intent detects live data queries`() {
        assertEquals(RetrievalIntent.WEB_SEARCH, classifyRetrievalIntent("search for latest OpenAI model"))
        assertEquals(RetrievalIntent.WEB_SEARCH, classifyRetrievalIntent("find current bitcoin price today"))
        assertEquals(RetrievalIntent.WEB_SEARCH, classifyRetrievalIntent("check the latest GrapheneOS release"))
        assertEquals(RetrievalIntent.WEB_SEARCH, classifyRetrievalIntent("Android security patch version 2026"))
        assertEquals(RetrievalIntent.WEB_SEARCH, classifyRetrievalIntent("who is the current US president"))
        assertEquals(RetrievalIntent.WEB_SEARCH, classifyRetrievalIntent("is Pixel 10 available to buy now"))
    }

    @Test
    fun `weather intent detects weather queries`() {
        assertTrue(isWeatherQuery("what is the weather today?"))
        assertTrue(isWeatherQuery("weather in Warsaw"))
        assertTrue(isWeatherQuery("weather in Berlin tomorrow"))
        assertTrue(isWeatherQuery("will it rain today?"))
        assertTrue(isWeatherQuery("forecast for New York this weekend"))
        assertTrue(isWeatherQuery("temperature in London now"))
        assertEquals(RetrievalIntent.WEATHER, classifyRetrievalIntent("weather in Warsaw tomorrow"))
        assertFalse(isWeatherQuery("what causes a rainbow?"))
    }

    @Test
    fun `weather location extraction handles english queries`() {
        assertEquals("Warsaw", extractWeatherLocation("weather in Warsaw tomorrow"))
        assertEquals("Berlin", extractWeatherLocation("weather in Berlin tomorrow"))
        assertEquals("New York", extractWeatherLocation("weather in New York tomorrow"))
        assertNull(extractWeatherLocation("what is the weather today?"))
    }
}
