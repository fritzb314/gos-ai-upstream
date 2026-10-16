package com.satory.graphenosai.util

private val DEVICE_ID = Regex("(?i)(device\\s*id|android\\s*id|id\\s*is)\\s*[:=]?\\s*\\b[a-f0-9]{16}\\b")
private val PHONE = Regex("\\+?\\d{10,15}")
private val EMAIL = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
private val CARD = Regex("\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}")

/** Replaces device IDs, phone numbers and email addresses with placeholders before text leaves the device. */
fun redactPersonalData(text: String): String =
    text.replace(DEVICE_ID, "$1 [ID]")
        .replace(PHONE, "[PHONE]")
        .replace(EMAIL, "[EMAIL]")

/** Replaces card-number-like digit groups (4x4, optionally separated by spaces or dashes). */
fun redactCardNumbers(text: String): String = text.replace(CARD, "[CARD]")
