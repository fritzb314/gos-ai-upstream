package com.satory.graphenosai.llm

import android.util.Log
import org.json.JSONException
import org.json.JSONObject

private const val TAG = "StreamParsing"

/**
 * JSON payloads of an OpenAI-style server-sent event stream (`data: {...}` lines), up to `data: [DONE]`.
 * Other lines, empty payloads and malformed JSON are skipped.
 */
internal fun sseJsonEvents(lines: Sequence<String>): Sequence<JSONObject> =
    lines.filter { it.startsWith("data: ") }
        .map { it.removePrefix("data: ").trim() }
        .takeWhile { it != "[DONE]" }
        .filter { it.isNotEmpty() }
        .mapNotNull { data ->
            try {
                JSONObject(data)
            } catch (e: JSONException) {
                Log.w(TAG, "Parse error: $data")
                null
            }
        }

/**
 * Text of the first choice's delta, or "" when absent or not a string.
 * Checked by type because Android's optString() turns a JSON null into the text "null".
 */
internal fun deltaContent(event: JSONObject): String =
    event.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.opt("content") as? String ?: ""

/**
 * Merges a partial tool call delta into the accumulator. Streams deliver tool calls in chunks;
 * each chunk may carry only some fields, and `arguments` arrives in pieces that must be concatenated.
 */
internal fun mergeToolCallDelta(acc: JSONObject, delta: JSONObject) {
    if (delta.has("id") && !delta.isNull("id")) {
        acc.put("id", delta.getString("id"))
    }
    if (delta.has("type") && !delta.isNull("type")) {
        acc.put("type", delta.getString("type"))
    }
    if (delta.has("function")) {
        val fnDelta = delta.getJSONObject("function")
        val fnAcc = if (acc.has("function")) acc.getJSONObject("function") else JSONObject()
        if (fnDelta.has("name") && !fnDelta.isNull("name")) {
            fnAcc.put("name", fnDelta.getString("name"))
        }
        if (fnDelta.has("arguments") && !fnDelta.isNull("arguments")) {
            val prevArgs = fnAcc.optString("arguments", "")
            fnAcc.put("arguments", prevArgs + fnDelta.getString("arguments"))
        }
        acc.put("function", fnAcc)
    }
}

/** Complete tool calls from the accumulated deltas, keyed by stream index. Calls without id or name are dropped. */
internal fun assembleToolCalls(toolCallsByIndex: Map<Int, JSONObject>): List<ToolCall> {
    val calls = mutableListOf<ToolCall>()
    for (idx in 0 until toolCallsByIndex.size) {
        val tc = toolCallsByIndex[idx] ?: continue
        val callId = tc.optString("id", "")
        val funcName = tc.optJSONObject("function")?.optString("name", "") ?: ""
        val funcArgs = tc.optJSONObject("function")?.optString("arguments", "{}") ?: "{}"
        if (callId.isNotEmpty() && funcName.isNotEmpty()) {
            calls.add(ToolCall(callId, "function", ToolFunctionCall(funcName, funcArgs)))
        }
    }
    return calls
}
