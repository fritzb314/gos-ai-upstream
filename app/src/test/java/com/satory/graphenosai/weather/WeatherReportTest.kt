package com.satory.graphenosai.weather

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherReportTest {

    private val report = WeatherReport(
        locationName = "Berlin",
        country = "Germany",
        timezone = "Europe/Berlin",
        currentTime = "2026-10-16T12:00",
        temperatureC = 21.0,
        apparentTemperatureC = 19.54,
        humidityPercent = 60,
        precipitationMm = null,
        cloudCoverPercent = 40,
        windSpeedKmh = 12.3,
        windGustsKmh = null,
        weatherCode = 2,
        dailySummary = listOf(DailyWeather("2026-10-16", maxTempC = 18.5, minTempC = 10.0, precipitationMm = 2.0, weatherCode = 61))
    )

    @Test
    fun `prompt context lists current conditions with readable numbers`() {
        val lines = report.toPromptContext().lines().map { it.trim() }

        assertTrue("Location: Berlin, Germany" in lines)
        assertTrue("Condition: partly cloudy" in lines)
        assertTrue("Temperature: 21°C" in lines)
        assertTrue("Feels like: 19.5°C" in lines)
        assertTrue("Humidity: 60%" in lines)
        assertTrue("Wind: 12.3 km/h" in lines)
        assertTrue("- 2026-10-16: 10-18.5°C, rain, precipitation 2 mm" in lines)
    }

    @Test
    fun `missing values are left out instead of printed as null`() {
        val text = report.toPromptContext()
        assertFalse(text.contains("Precipitation:"))
        assertFalse(text.contains("Wind gusts"))
        assertFalse(text.contains("null"))
    }

    @Test
    fun `blank country and unknown codes are handled`() {
        val lines = report.copy(country = "", weatherCode = 42).toPromptContext().lines().map { it.trim() }
        assertTrue("Location: Berlin" in lines)
        assertTrue("Condition: weather code 42" in lines)

        val noCode = report.copy(weatherCode = null).toPromptContext().lines().map { it.trim() }
        assertTrue("Condition: unknown" in noCode)
    }

    @Test
    fun `parses daily forecast arrays`() {
        val daily = JSONObject(
            """{"time":["2026-10-16","2026-10-17"],"temperature_2m_max":[18.5,20],"temperature_2m_min":[10,11],
                "precipitation_sum":[2.4,0],"weather_code":[61,0]}"""
        )

        assertEquals(
            listOf(
                DailyWeather("2026-10-16", 18.5, 10.0, 2.4, 61),
                DailyWeather("2026-10-17", 20.0, 11.0, 0.0, 0)
            ),
            parseDailyWeather(daily)
        )
    }

    @Test
    fun `missing daily data gives an empty forecast`() {
        assertTrue(parseDailyWeather(null).isEmpty())
        assertTrue(parseDailyWeather(JSONObject("""{"temperature_2m_max":[1]}""")).isEmpty())
    }

    @Test
    fun `missing precipitation defaults to zero`() {
        val daily = JSONObject("""{"time":["d"],"temperature_2m_max":[5],"temperature_2m_min":[1]}""")
        assertEquals(0.0, parseDailyWeather(daily).single().precipitationMm, 0.0)
    }
}
