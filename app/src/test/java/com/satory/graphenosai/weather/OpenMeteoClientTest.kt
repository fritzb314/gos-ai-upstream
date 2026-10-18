package com.satory.graphenosai.weather

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class OpenMeteoClientTest {

    private val server = MockWebServer()
    private var geocodingBody = """{"results":[{"name":"Berlin","country":"Germany","latitude":52.52,"longitude":13.41,"timezone":"Europe/Berlin"}]}"""
    private var forecastCode = 200

    private val forecastBody = """{
        "timezone":"Europe/Berlin",
        "current":{"time":"2026-10-16T12:00","temperature_2m":21.3,"apparent_temperature":19.0,"relative_humidity_2m":60,
                   "precipitation":0.0,"weather_code":3,"cloud_cover":90,"wind_speed_10m":12.5,"wind_gusts_10m":null},
        "daily":{"time":["2026-10-16"],"temperature_2m_max":[22],"temperature_2m_min":[11],"precipitation_sum":[0.4],"weather_code":[61]}
    }"""

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/geo") -> MockResponse().setBody(geocodingBody)
                request.path!!.startsWith("/forecast") -> MockResponse().setResponseCode(forecastCode).setBody(forecastBody)
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun client() = OpenMeteoClient(server.url("/geo").toString(), server.url("/forecast").toString())

    @Test
    fun `geocodes the location and returns the parsed forecast`() = runTest {
        val report = client().getWeather("Berlin")!!

        assertEquals("Berlin", report.locationName)
        assertEquals("Germany", report.country)
        assertEquals(21.3, report.temperatureC!!, 0.0)
        assertEquals(60, report.humidityPercent)
        assertEquals(3, report.weatherCode)
        assertNull(report.windGustsKmh)
        assertEquals(listOf(DailyWeather("2026-10-16", 22.0, 11.0, 0.4, 61)), report.dailySummary)
    }

    @Test
    fun `asks for the place by name, then the forecast for its coordinates`() = runTest {
        client().getWeather("New York")

        val geocode = server.takeRequest()
        assertEquals("/geo?name=New+York&count=1&language=en&format=json", geocode.path)
        val forecast = server.takeRequest().path!!
        assertEquals(true, forecast.startsWith("/forecast?latitude=52.52&longitude=13.41"))
    }

    @Test
    fun `unknown places give no report`() = runTest {
        geocodingBody = """{"results":[]}"""
        assertNull(client().getWeather("Atlantis"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `server errors give no report`() = runTest {
        forecastCode = 500
        assertNull(client().getWeather("Berlin"))
    }
}
