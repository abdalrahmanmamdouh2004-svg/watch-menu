package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WeatherSyncTest {
    @Test
    fun userVerifiedWatchIconMappingIsPreserved() {
        assertEquals(1, WeatherSync.cond(0, true).toInt())      // clear day
        assertEquals(23, WeatherSync.cond(0, false).toInt())    // clear night
        assertEquals(25, WeatherSync.cond(1, true).toInt())     // sun + small cloud
        assertEquals(22, WeatherSync.cond(1, false).toInt())    // moon + small cloud
        assertEquals(26, WeatherSync.cond(2, true).toInt())     // sun + big cloud
        assertEquals(24, WeatherSync.cond(2, false).toInt())    // moon + big cloud
        assertEquals(21, WeatherSync.cond(3, true).toInt())     // cloudy
        assertEquals(4, WeatherSync.cond(61, true).toInt())     // rain
        assertEquals(5, WeatherSync.cond(56, true).toInt())     // freezing precipitation
        assertEquals(9, WeatherSync.cond(95, true).toInt())     // thunder
        assertEquals(14, WeatherSync.cond(71, true).toInt())    // snow
        assertEquals(20, WeatherSync.cond(75, true).toInt())    // heavy snow
        assertEquals(6, WeatherSync.cond(0, true, windKmh = 40.0).toInt())
        assertEquals(19, WeatherSync.cond(0, true, tempC = 40.0).toInt())
        assertEquals(7, WeatherSync.cond(999, true).toInt())    // unknown / no data
    }

    @Test
    fun forecastUsesActualHourlyCodesAndDayNightAndCelsiusExactlyOnce() {
        val utc = TimeZone.getTimeZone("UTC")
        val base = Calendar.getInstance(utc).apply {
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:00", Locale.US).apply { timeZone = utc }
        val dailyIso = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = utc }

        val hours = (0 until 48).map { offset ->
            (base.clone() as Calendar).apply { add(Calendar.HOUR_OF_DAY, offset) }.time
        }
        val hourTimes = hours.joinToString(",") { "\"${iso.format(it)}\"" }
        val dailyTimes = (0 until 7).map { offset ->
            (base.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, offset) }.time
        }
        val dayDates = dailyTimes.joinToString(",") { "\"${dailyIso.format(it)}\"" }
        val sunrises = dailyTimes.joinToString(",") { "\"${dailyIso.format(it)}T06:00\"" }
        val sunsets = dailyTimes.joinToString(",") { "\"${dailyIso.format(it)}T18:00\"" }
        val temps = List(48) { "15" }.joinToString(",")
        val codes = (0 until 48).map { when (it % 4) { 0 -> 0; 1 -> 1; 2 -> 2; else -> 3 } }.joinToString(",")
        val night = List(48) { "0" }.joinToString(",")
        val wind = List(48) { "0" }.joinToString(",")
        val json = """
            {
              "timezone":"UTC",
              "current":{"temperature_2m":15,"weather_code":0,"is_day":0},
              "hourly":{"time":[$hourTimes],"temperature_2m":[$temps],"weather_code":[$codes],"is_day":[$night],"wind_speed_10m":[$wind]},
              "daily":{"time":[$dayDates],"weather_code":[2,3,0,1,2,3,0],"temperature_2m_max":[22,23,24,25,26,27,28],"temperature_2m_min":[10,11,12,13,14,15,16],"sunrise":[$sunrises],"sunset":[$sunsets]}
            }
        """.trimIndent()

        val celsiusPayload = WeatherSync.parseAndSerialize(json, "Test City", true)
        assertEquals(199, celsiusPayload.size)
        // Current-condition tile uses current weather_code + is_day, not the daily daytime code.
        assertEquals(23, celsiusPayload[0].toInt() and 0xFF)
        assertEquals(21, celsiusPayload[9].toInt() and 0xFF) // following day keeps its daily icon
        assertEquals(122, celsiusPayload[2].toInt() and 0xFF) // high 22 C + 100
        assertEquals(110, celsiusPayload[3].toInt() and 0xFF) // low 10 C + 100
        // Hourly record starts at byte 63: [temperature, icon]. Each hour has its own code.
        assertEquals(115, celsiusPayload[63].toInt() and 0xFF) // 15 C + 100; no second conversion
        assertEquals(23, celsiusPayload[64].toInt() and 0xFF)  // clear night
        assertEquals(22, celsiusPayload[66].toInt() and 0xFF)  // partly clear night
        assertEquals(24, celsiusPayload[68].toInt() and 0xFF)  // partly cloudy night
        assertEquals(21, celsiusPayload[70].toInt() and 0xFF)  // cloudy

        val fahrenheitPayload = WeatherSync.parseAndSerialize(json, "Test City", false)
        assertEquals(159, fahrenheitPayload[63].toInt() and 0xFF) // 59 F + 100
        assertTrue(WeatherSync.hourlyPreview.value.take(4).all { !it.isDay })
    }
}
