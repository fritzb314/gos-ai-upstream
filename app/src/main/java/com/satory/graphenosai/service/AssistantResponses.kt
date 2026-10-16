package com.satory.graphenosai.service

private val OPEN_URL_PATTERNS = listOf(
    Regex("(?:open(?:ing)?|launch(?:ing)?|navigate to)\\s+(?:this )?(?:link|url|page)?:?\\s*(https?://[^\\s\\)\\]]+)", RegexOption.IGNORE_CASE),
    Regex("\\[OPEN_URL:(https?://[^]]+)]"),
    Regex("(?:открываю|открой)\\s+(?:ссылку)?:?\\s*(https?://[^\\s\\)\\]]+)", RegexOption.IGNORE_CASE)
)

private val NO_INTERNET_PHRASES = listOf(
    "don't have access to the internet",
    "do not have access to the internet",
    "don't have web access",
    "do not have web access",
    "can't browse the web",
    "cannot browse the web",
    "не имею доступа к интернету",
    "нет доступа к интернету",
    "не могу искать в интернете",
    "не могу просматривать интернет",
    "нет доступа к веб"
)

/** URLs the model asked to open, e.g. "opening https://…" or "[OPEN_URL:https://…]", without duplicates. */
internal fun detectUrlsToOpen(response: String): List<String> {
    val urls = mutableListOf<String>()
    for (pattern in OPEN_URL_PATTERNS) {
        pattern.findAll(response).forEach { match ->
            val url = match.groupValues[1].trimEnd('.', ',', '!', '?')
            if (url !in urls) urls.add(url)
        }
    }
    return urls
}

/** True when the model claims it has no internet access, so the answer should be retried with web search. */
internal fun shouldFallbackToSearch(response: String): Boolean {
    val lower = response.lowercase()
    return NO_INTERNET_PHRASES.any { lower.contains(it) }
}
