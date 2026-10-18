package com.satory.graphenosai.service

import com.satory.graphenosai.ui.SettingsManager

internal enum class VoiceRecognizer { WHISPER, VOSK, SYSTEM, NONE }

/**
 * Picks the speech recognizer for the user's preferred input method.
 * Note: when Vosk is preferred but its model is missing, this falls back to the system recognizer,
 * which may be an online service on devices with Google apps installed.
 */
internal fun chooseVoiceRecognizer(method: String, voskInstalled: Boolean, systemAvailable: Boolean): VoiceRecognizer {
    val preferVosk = method == SettingsManager.VOICE_INPUT_VOSK
    val preferWhisper = method == SettingsManager.VOICE_INPUT_WHISPER
    return when {
        preferWhisper -> VoiceRecognizer.WHISPER
        preferVosk && voskInstalled -> VoiceRecognizer.VOSK
        systemAvailable -> VoiceRecognizer.SYSTEM
        else -> VoiceRecognizer.NONE
    }
}
