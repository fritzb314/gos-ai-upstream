package com.satory.graphenosai.util

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivacyFilterTest {

    @Test
    fun `redacts device ids and keeps the label`() {
        assertEquals(
            "My device ID is [ID] and I need help",
            redactPersonalData("My device ID is abcdef1234567890 and I need help")
        )
    }

    @Test
    fun `redacts phone numbers with and without plus`() {
        assertEquals("Call me at [PHONE] please", redactPersonalData("Call me at +14155551234 please"))
        assertEquals("Call [PHONE]", redactPersonalData("Call 0415555123"))
    }

    @Test
    fun `keeps numbers too short to be phone numbers`() {
        assertEquals("Order 123456789 arrived", redactPersonalData("Order 123456789 arrived"))
    }

    @Test
    fun `redacts email addresses`() {
        assertEquals("Contact me at [EMAIL] for details", redactPersonalData("Contact me at user.name+tag@example.co.uk for details"))
    }

    @Test
    fun `redacts several kinds in one text`() {
        assertEquals("[EMAIL] or [PHONE]", redactPersonalData("a@b.de or +4915112345678"))
    }

    @Test
    fun `leaves ordinary text unchanged`() {
        val text = "What is the weather like today?"
        assertEquals(text, redactPersonalData(text))
    }

    @Test
    fun `redacts card numbers separated by spaces or dashes`() {
        assertEquals("card [CARD]", redactCardNumbers("card 4111 1111 1111 1111"))
        assertEquals("card [CARD]", redactCardNumbers("card 4111-1111-1111-1111"))
    }
}
