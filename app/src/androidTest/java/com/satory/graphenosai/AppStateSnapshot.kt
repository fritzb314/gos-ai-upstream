package com.satory.graphenosai

import android.content.Context
import java.io.File

/**
 * Saves and restores the app state that on-device tests touch, so running them on a
 * developer's emulator or phone leaves their settings, keys, chats and models as they were.
 */
class AppStateSnapshot(private val context: Context) {

    private val prefsNames = listOf("assistant_settings", "assistant_secure_prefs")
    private val trackedDirs = listOf("chat_history", "local_models").map { File(context.filesDir, it) }
    private val savedPrefs = mutableMapOf<String, Map<String, *>>()
    private val savedFileNames = mutableMapOf<File, Set<String>>()
    private val existingDirs = mutableSetOf<File>()

    fun save() {
        for (name in prefsNames) {
            savedPrefs[name] = HashMap(context.getSharedPreferences(name, Context.MODE_PRIVATE).all)
        }
        for (dir in trackedDirs) {
            savedFileNames[dir] = dir.list()?.toSet() ?: emptySet()
            if (dir.exists()) existingDirs += dir
        }
    }

    fun restore() {
        for ((name, values) in savedPrefs) {
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            for ((key, value) in values) {
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            editor.commit()
        }
        for ((dir, names) in savedFileNames) {
            dir.listFiles()?.filter { it.name !in names }?.forEach { it.delete() }
            if (dir !in existingDirs) dir.delete()
        }
    }
}
