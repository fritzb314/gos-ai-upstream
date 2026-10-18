package com.satory.graphenosai.service

import com.satory.graphenosai.ui.SettingsManager.Companion.VOICE_INPUT_SYSTEM
import com.satory.graphenosai.ui.SettingsManager.Companion.VOICE_INPUT_VOSK
import com.satory.graphenosai.ui.SettingsManager.Companion.VOICE_INPUT_WHISPER
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceInputChoiceTest {

    private val bools = listOf(true, false)

    @Test
    fun `whisper is used whenever it is chosen`() {
        for (vosk in bools) for (system in bools) {
            assertEquals(VoiceRecognizer.WHISPER, chooseVoiceRecognizer(VOICE_INPUT_WHISPER, vosk, system))
        }
    }

    @Test
    fun `vosk is used when chosen and its model is installed`() {
        for (system in bools) {
            assertEquals(VoiceRecognizer.VOSK, chooseVoiceRecognizer(VOICE_INPUT_VOSK, voskInstalled = true, systemAvailable = system))
        }
    }

    @Test
    fun `missing vosk model falls back to the system recognizer when there is one`() {
        // Privacy-relevant: the system recognizer may be online. This documents current behaviour.
        assertEquals(VoiceRecognizer.SYSTEM, chooseVoiceRecognizer(VOICE_INPUT_VOSK, voskInstalled = false, systemAvailable = true))
        assertEquals(VoiceRecognizer.NONE, chooseVoiceRecognizer(VOICE_INPUT_VOSK, voskInstalled = false, systemAvailable = false))
    }

    @Test
    fun `system recognizer is used when chosen and available`() {
        for (vosk in bools) {
            assertEquals(VoiceRecognizer.SYSTEM, chooseVoiceRecognizer(VOICE_INPUT_SYSTEM, vosk, systemAvailable = true))
            assertEquals(VoiceRecognizer.NONE, chooseVoiceRecognizer(VOICE_INPUT_SYSTEM, vosk, systemAvailable = false))
        }
    }
}
