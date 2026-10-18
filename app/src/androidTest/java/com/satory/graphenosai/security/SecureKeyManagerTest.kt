package com.satory.graphenosai.security

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureKeyManagerTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefsName = "secure_key_manager_test"
    private lateinit var keys: SecureKeyManager

    @Before
    fun setUp() {
        context.deleteSharedPreferences(prefsName)
        keys = SecureKeyManager(context, prefsName)
    }

    @After
    fun tearDown() {
        // commit() waits for the manager's pending apply() writes, so the file is not recreated after deletion.
        rawPrefs().edit().clear().commit()
        context.deleteSharedPreferences(prefsName)
    }

    private fun rawPrefs() = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    @Test
    fun storesAndReadsEveryProviderKey() {
        keys.setOpenRouterApiKey("sk-or-1")
        keys.setGroqApiKey("gsk-2")
        keys.setHermesApiKey("hermes-3")
        keys.setBraveApiKey("brave-4")
        keys.setExaApiKey("exa-5")
        keys.setLangSearchApiKey("lang-6")

        assertEquals("sk-or-1", keys.getOpenRouterApiKey())
        assertEquals("gsk-2", keys.getGroqApiKey())
        assertEquals("hermes-3", keys.getHermesApiKey())
        assertEquals("brave-4", keys.getBraveApiKey())
        assertEquals("exa-5", keys.getExaApiKey())
        assertEquals("lang-6", keys.getLangSearchApiKey())
        assertTrue(keys.hasOpenRouterApiKey() && keys.hasGroqApiKey() && keys.hasHermesApiKey())
        assertTrue(keys.hasBraveApiKey() && keys.hasExaApiKey() && keys.hasLangSearchApiKey())
    }

    @Test
    fun keysAreReadableByANewInstance() {
        keys.setOpenRouterApiKey("sk-or-persisted")
        assertEquals("sk-or-persisted", SecureKeyManager(context, prefsName).getOpenRouterApiKey())
    }

    @Test
    fun keysAreNeverStoredInPlainText() {
        keys.setOpenRouterApiKey("sk-or-very-secret")
        keys.setBraveApiKey("brave-very-secret")

        val stored = rawPrefs().all.values.joinToString(" ")
        assertFalse(stored.contains("very-secret"))
    }

    @Test
    fun clearRemovesKeys() {
        keys.setOpenRouterApiKey("a")
        keys.setHermesApiKey("b")
        keys.setBraveApiKey("c")

        keys.clearOpenRouterApiKey()
        keys.clearHermesApiKey()
        keys.clearBraveApiKey()

        assertNull(keys.getOpenRouterApiKey())
        assertNull(keys.getHermesApiKey())
        assertFalse(keys.hasBraveApiKey())
    }

    @Test
    fun missingKeysReadAsNull() {
        assertNull(keys.getOpenRouterApiKey())
        assertFalse(keys.hasExaApiKey())
    }

    @Test
    fun tamperedValueReadsAsNull() {
        rawPrefs().edit().putString("openrouter_api_key", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA").commit()
        assertNull(keys.getOpenRouterApiKey())
    }
}
