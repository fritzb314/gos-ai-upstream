package com.satory.graphenosai.service

import com.satory.graphenosai.llm.RetrievalIntent
import org.junit.Assert.assertEquals
import org.junit.Test

class QueryRouteTest {

    private fun route(
        webSearchEnabled: Boolean = true,
        hasImage: Boolean = false,
        isLocal: Boolean = false,
        intent: RetrievalIntent = RetrievalIntent.NONE,
        searchConfigured: Boolean = true
    ) = chooseQueryRoute(webSearchEnabled, hasImage, isLocal, intent, searchConfigured)

    @Test
    fun `answers directly when web search is off, an image is attached or the model is local`() {
        for (intent in RetrievalIntent.values()) {
            assertEquals(QueryRoute.DIRECT, route(webSearchEnabled = false, intent = intent))
            assertEquals(QueryRoute.DIRECT, route(hasImage = true, intent = intent))
            assertEquals(QueryRoute.DIRECT, route(isLocal = true, intent = intent))
        }
    }

    @Test
    fun `weather questions use the weather service even without a search key`() {
        assertEquals(QueryRoute.WEATHER, route(intent = RetrievalIntent.WEATHER))
        assertEquals(QueryRoute.WEATHER, route(intent = RetrievalIntent.WEATHER, searchConfigured = false))
    }

    @Test
    fun `clear search questions search directly, or explain that search is not set up`() {
        assertEquals(QueryRoute.DIRECT_SEARCH, route(intent = RetrievalIntent.WEB_SEARCH))
        assertEquals(QueryRoute.SEARCH_NOT_CONFIGURED, route(intent = RetrievalIntent.WEB_SEARCH, searchConfigured = false))
    }

    @Test
    fun `other questions let the model decide via tool calling when search is set up`() {
        assertEquals(QueryRoute.FUNCTION_CALLING, route())
        assertEquals(QueryRoute.DIRECT, route(searchConfigured = false))
    }
}
