package com.satory.graphenosai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Saves and restores the app state that on-device tests touch, so running them on a
 * developer's emulator or phone leaves their settings, keys, chats and models as they were.
 *
 * The backup lives on disk: if a run is killed before restore(), the next save() restores it first.
 * Chats are copied in full (tests may push old chats out of the 50-chat limit); models only by name.
 */
class AppStateSnapshot(private val context: Context) {

    private val prefsNames = listOf("assistant_settings", "assistant_secure_prefs")
    private val chatDir = File(context.filesDir, "chat_history")
    private val modelDir = File(context.filesDir, "local_models")
    private val backupDir = File(context.dataDir, "test_state_backup")
    private val manifest = File(backupDir, "manifest.json")

    fun save() {
        if (manifest.exists()) restore()
        backupDir.deleteRecursively()
        backupDir.mkdirs()
        chatDir.listFiles()?.forEach { it.copyTo(File(backupDir, "chats/${it.name}")) }
        val state = JSONObject()
            .put("prefs", JSONObject().apply { prefsNames.forEach { put(it, prefsToJson(it)) } })
            .put("chatDirExisted", chatDir.exists())
            .put("modelDirExisted", modelDir.exists())
            .put("models", JSONArray(modelDir.list()?.toList() ?: emptyList<String>()))
        manifest.writeText(state.toString())
    }

    fun restore() {
        if (!manifest.exists()) return
        val state = JSONObject(manifest.readText())
        val prefs = state.getJSONObject("prefs")
        prefsNames.forEach { restorePrefs(it, prefs.getJSONObject(it)) }
        restoreChats(state.getBoolean("chatDirExisted"))
        restoreModels(state.getJSONArray("models"), state.getBoolean("modelDirExisted"))
        backupDir.deleteRecursively()
    }

    private fun prefsToJson(name: String): JSONObject {
        val json = JSONObject()
        for ((key, value) in context.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
            val typed = when (value) {
                is String -> JSONObject().put("type", "string").put("value", value)
                is Boolean -> JSONObject().put("type", "boolean").put("value", value)
                is Int -> JSONObject().put("type", "int").put("value", value)
                is Long -> JSONObject().put("type", "long").put("value", value)
                is Float -> JSONObject().put("type", "float").put("value", value.toDouble())
                is Set<*> -> JSONObject().put("type", "set").put("value", JSONArray(value.toList()))
                else -> continue
            }
            json.put(key, typed)
        }
        return json
    }

    private fun restorePrefs(name: String, json: JSONObject) {
        val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
        for (key in json.keys()) {
            val typed = json.getJSONObject(key)
            when (typed.getString("type")) {
                "string" -> editor.putString(key, typed.getString("value"))
                "boolean" -> editor.putBoolean(key, typed.getBoolean("value"))
                "int" -> editor.putInt(key, typed.getInt("value"))
                "long" -> editor.putLong(key, typed.getLong("value"))
                "float" -> editor.putFloat(key, typed.getDouble("value").toFloat())
                "set" -> {
                    val array = typed.getJSONArray("value")
                    editor.putStringSet(key, (0 until array.length()).map { array.getString(it) }.toSet())
                }
            }
        }
        editor.commit()
    }

    private fun restoreChats(dirExisted: Boolean) {
        chatDir.deleteRecursively()
        val saved = File(backupDir, "chats").listFiles().orEmpty()
        if (!dirExisted && saved.isEmpty()) return
        chatDir.mkdirs()
        saved.forEach { it.copyTo(File(chatDir, it.name)) }
    }

    private fun restoreModels(names: JSONArray, dirExisted: Boolean) {
        val keep = (0 until names.length()).map { names.getString(it) }.toSet()
        modelDir.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
        if (!dirExisted) modelDir.delete()
    }
}
