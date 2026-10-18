package com.satory.graphenosai.llm

/**
 * Builds a ChatML or Gemma prompt: system prompt, history, an optional final user message,
 * and the opening of the model's turn.
 */
internal fun buildChatPrompt(
    format: String,
    systemPrompt: String,
    history: List<ChatSession.Message>,
    finalUserMessage: String? = null
): String {
    val gemma = format == "gemma"
    val sb = StringBuilder()
    fun turn(role: String, content: String) {
        if (gemma) {
            sb.append("<start_of_turn>$role\n").append(content).append("<end_of_turn>\n")
        } else {
            sb.append("<|im_start|>$role\n").append(content).append("<|im_end|>\n")
        }
    }

    turn("system", systemPrompt)
    for (msg in history) {
        when (msg.role) {
            "user" -> turn("user", msg.content)
            "assistant" -> turn(if (gemma) "model" else "assistant", msg.content)
        }
    }
    finalUserMessage?.let { turn("user", it) }
    sb.append(if (gemma) "<start_of_turn>model\n" else "<|im_start|>assistant\n")
    return sb.toString()
}

/**
 * Passes streamed model tokens through until the first stop token. Text that might be the start
 * of a stop token split across tokens is held back until it is clearly not one.
 */
internal class StopTokenFilter(private val stopTokens: List<String>) {
    private val buffer = StringBuilder()

    var stopped = false
        private set

    /** Returns the text from this token that is safe to show, possibly empty. */
    fun accept(token: String): String {
        if (stopped) return ""
        buffer.append(token)
        val text = buffer.toString()

        for (stopToken in stopTokens) {
            val idx = text.indexOf(stopToken)
            if (idx >= 0) {
                stopped = true
                buffer.clear()
                return text.substring(0, idx)
            }
        }

        var holdBack = 0
        for (stopToken in stopTokens) {
            for (i in 1 until minOf(stopToken.length, text.length + 1)) {
                if (text.endsWith(stopToken.substring(0, i))) {
                    holdBack = maxOf(holdBack, i)
                }
            }
        }

        if (text.length <= holdBack) return ""
        buffer.clear()
        buffer.append(text.substring(text.length - holdBack))
        return text.substring(0, text.length - holdBack)
    }
}
