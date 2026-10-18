package com.satory.graphenosai.service

import com.satory.graphenosai.llm.RetrievalIntent

internal enum class QueryRoute { WEATHER, DIRECT_SEARCH, SEARCH_NOT_CONFIGURED, FUNCTION_CALLING, DIRECT }

/** Decides how a query is answered. Web lookups are skipped for images and for local models. */
internal fun chooseQueryRoute(
    webSearchEnabled: Boolean,
    hasImage: Boolean,
    isLocal: Boolean,
    intent: RetrievalIntent,
    searchConfigured: Boolean
): QueryRoute {
    if (!webSearchEnabled || hasImage || isLocal) return QueryRoute.DIRECT
    return when (intent) {
        RetrievalIntent.WEATHER -> QueryRoute.WEATHER
        RetrievalIntent.WEB_SEARCH -> if (searchConfigured) QueryRoute.DIRECT_SEARCH else QueryRoute.SEARCH_NOT_CONFIGURED
        RetrievalIntent.NONE -> if (searchConfigured) QueryRoute.FUNCTION_CALLING else QueryRoute.DIRECT
    }
}
