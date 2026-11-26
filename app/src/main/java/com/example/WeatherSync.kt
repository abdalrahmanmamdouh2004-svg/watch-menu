package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A city returned by Open-Meteo's free geocoding endpoint. */
data class CitySearchResult(
    val name: String,
    val country: String,
    val admin1: String = "",
    val latitude: Double,
    val longitude: Double,
    val timezone: String
)

data class HourlyPreviewItem(
    val timeIso: String,
    val hourLabel: String,
    val tempC: Double,
    val wmo: Int,
    val isDay: Boolean,
    val iconCode: Int,
    val conditionName: String
)

data class DailyPreviewItem(
    val dateIso: String,
    val dayLabel: String,
    val tempMaxC: Double,
    val tempMinC: Double,
    val wmo: Int,
    val iconCode: Int,
    val sunriseIso: String,
    val sunsetIso: String
)

/**
 * Weather synchronization for the CMF Watch Pro 2. The watch payload is 199 bytes:
 * 7 daily records (9 bytes), 24 hourly records (2 bytes), 32-byte place name,
 * and 7 sunrise/sunset records (8 bytes).
 *
 * Default provider is Open-Meteo. Custom endpoints must return the same JSON field layout
 * as Open-Meteo's forecast endpoint. We use actual hourly weather_code + is_day values;
 * daily weather codes are used only for the daily records.
 */
object WeatherSync {
    val status = MutableStateFlow("")
    val lastSyncTime = MutableStateFlow("")
    val hourlyPreview = MutableStateFlow<List<HourlyPreviewItem>>(emptyList())
    val dailyPreview = MutableStateFlow<List<DailyPreviewItem>>(emptyList())
    val lastPayloadBytes = MutableStateFlow<ByteArray?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var loop = false
    @Volatile private var celsiusNumbersForPayload = true

    private fun prefs(c: Context) = c.getSharedPreferences("watch_link", Context.MODE_PRIVATE)
    fun city(c: Context): String = prefs(c).getString("weather_city", "") ?: ""
    fun setCity(c: Context, v: String) = prefs(c).edit().putString("weather_city", v.trim()).apply()
    fun selectedCountry(c: Context): String = prefs(c).getString("weather_country", "") ?: ""
    fun setCountry(c: Context, v: String) = prefs(c).edit().putString("weather_country", v).apply()
    fun latitude(c: Context): Double = java.lang.Double.longBitsToDouble(prefs(c).getLong("weather_lat", java.lang.Double.doubleToRawLongBits(0.0)))
    fun longitude(c: Context): Double = java.lang.Double.longBitsToDouble(prefs(c).getLong("weather_lon", java.lang.Double.doubleToRawLongBits(0.0)))
    fun setCoordinates(c: Context, lat: Double, lon: Double) {
        require(lat in -90.0..90.0 && lon in -180.0..180.0)
        prefs(c).edit().putLong("weather_lat", java.lang.Double.doubleToRawLongBits(lat))
            .putLong("weather_lon", java.lang.Double.doubleToRawLongBits(lon)).apply()
    }
    fun timezone(c: Context): String = prefs(c).getString("weather_tz", "auto") ?: "auto"
    fun setTimezone(c: Context, tz: String) = prefs(c).edit().putString("weather_tz", tz).apply()
    fun useLocation(c: Context): Boolean = prefs(c).getBoolean("weather_use_location", false)
    fun setUseLocation(c: Context, v: Boolean) = prefs(c).edit().putBoolean("weather_use_location", v).apply()

    fun useCustomApi(c: Context) = prefs(c).getBoolean("weather_custom_enabled", false)
    fun customApiUrl(c: Context) = prefs(c).getString("weather_custom_url", "") ?: ""
    fun customApiKey(c: Context) = prefs(c).getString("weather_custom_key", "") ?: ""
    fun setCustomApi(c: Context, enabled: Boolean, endpoint: String, key: String) {
        prefs(c).edit().putBoolean("weather_custom_enabled", enabled)
            .putString("weather_custom_url", endpoint.trim())
            .putString("weather_custom_key", key.trim()).apply()
    }

    /** Celsius on sends the Celsius number unchanged; Fahrenheit off converts C to F once. */
    fun celsiusNumbers(c: Context) = prefs(c).getBoolean("weather_c_numbers", true)
    fun setCelsiusNumbers(c: Context, v: Boolean) {
        prefs(c).edit().putBoolean("weather_c_numbers", v).apply()
        sendLive(c)
    }
    private fun encodeTemp(tempC: Double, celsius: Boolean): Byte {
        val value = if (celsius) tempC else tempC * 1.8 + 32.0
        return ((value.toIntRounded() + 100) and 0xFF).toByte()
    }
    private fun Double.toIntRounded(): Int = kotlin.math.round(this).toInt()

    fun nightClear(c: Context) = prefs(c).getInt("night_clear2", 23)
    fun nightPartly(c: Context) = prefs(c).getInt("night_partly2", 22)
    fun setNight(c: Context, clear: Int, partly: Int) = prefs(c).edit()
        .putInt("night_clear2", clear.coerceIn(0, 255)).putInt("night_partly2", partly.coerceIn(0, 255)).apply()

    fun hourSwap(c: Context) = prefs(c).getBoolean("hour_swap", false)
    fun setHourSwap(c: Context, v: Boolean) = prefs(c).edit().putBoolean("hour_swap", v).apply()
    fun unitF(c: Context) = prefs(c).getBoolean("weather_f", false)
    fun setUnitF(c: Context, v: Boolean) { prefs(c).edit().putBoolean("weather_f", v).apply(); pushUnit(c) }
    fun pushUnit(c: Context) = sendUnitByte(if (unitF(c)) 1 else 0)
    fun sendUnitByte(b: Int) {
        status.value = if (WatchLink.sendTempUnit(b)) "Unit byte $b sent" else "Unit saved, watch not connected"
    }

    /** Map WMO codes to the exact icon codes identified on this user's watch. */
    fun cond(wmo: Int, isDay: Boolean = true, c: Context? = null, windKmh: Double = 0.0, tempC: Double? = null): Byte {
        val clearNight = c?.let { nightClear(it) } ?: 23
        val partlyNight = c?.let { nightPartly(it) } ?: 22
        val code = when {
            wmo in 95..99 -> 9                                  // thunder
            wmo in 71..77 || wmo == 85 || wmo == 86 -> if (wmo == 75 || wmo == 86) 20 else 14
            wmo == 56 || wmo == 57 || wmo == 66 || wmo == 67 -> 5 // freezing precipitation
            wmo in 51..55 || wmo in 61..65 || wmo in 80..82 -> 4 // rain
            wmo == 45 || wmo == 48 -> 21                         // mist / fog: cloud icon
            windKmh >= 40.0 -> 6                                 // windy
            tempC != null && tempC >= 40.0 && wmo in 0..2 -> 19  // very hot
            wmo == 0 -> if (isDay) 1 else clearNight             // clear
            wmo == 1 -> if (isDay) 25 else partlyNight           // mainly clear / small cloud at night
            wmo == 2 -> if (isDay) 26 else 24                    // partly cloudy / large cloud at night
            wmo == 3 -> 21                                      // cloudy
            else -> 7                                            // no data / unsupported
        }
        return code.toByte()
    }

    fun conditionLabel(wmo: Int, isDay: Boolean): String = when (wmo) {
        0 -> if (isDay) "Sunny" else "Clear night"
        1 -> if (isDay) "Mostly clear" else "Clear night"
        2 -> if (isDay) "Partly cloudy" else "Partly cloudy night"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61, 63, 65 -> "Rain"
        66, 67 -> "Freezing rain"
        71, 73, 75, 77 -> "Snow"
        80, 81, 82 -> "Rain showers"
        85, 86 -> "Snow showers"
        95, 96, 99 -> "Thunderstorm"
        else -> "Unknown"
    }

    private fun httpGet(url: String, apiKey: String = ""): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 12_000
        conn.readTimeout = 12_000
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "WatchMenu/1.0")
        if (apiKey.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("X-API-Key", apiKey)
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("Weather API HTTP $code: ${body.take(180)}")
            return body
        } finally { conn.disconnect() }
    }

    suspend fun searchCities(query: String): List<CitySearchResult> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.length < 2) return@withContext emptyList()
        try {
            val url = "https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(q, "UTF-8")}&count=10&language=en&format=json"
            val results = JSONObject(httpGet(url)).optJSONArray("results") ?: return@withContext emptyList()
            (0 until results.length()).mapNotNull { i ->
                val item = results.optJSONObject(i) ?: return@mapNotNull null
                val lat = item.optDouble("latitude", Double.NaN)
                val lon = item.optDouble("longitude", Double.NaN)
                if (!lat.isFinite() || !lon.isFinite()) null else CitySearchResult(
                    item.optString("name"), item.optString("country"), item.optString("admin1"),
                    lat, lon, item.optString("timezone", "auto")
                )
            }
        } catch (e: Exception) {
            AppLogger.logError("Weather", "searchCities", e)
            emptyList()
        }
    }

    private fun cityBytes(name: String): ByteArray {
        val bytes = name.toByteArray(Charsets.UTF_8)
        var end = minOf(31, bytes.size)
        while (end > 0 && end < bytes.size && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return ByteArray(32).also { System.arraycopy(bytes, 0, it, 0, end) }
    }

    private fun build(days: List<ByteArray>, hours: List<ByteArray>, name: String, sun: List<ByteArray>): ByteArray {
        val out = ByteBuffer.allocate(199)
        repeat(7) { i -> out.put(days.getOrNull(i)?.takeIf { it.size == 9 } ?: ByteArray(9)) }
        repeat(24) { i -> out.put(hours.getOrNull(i)?.takeIf { it.size == 2 } ?: ByteArray(2)) }
        out.put(cityBytes(name))
        repeat(7) { i -> out.put(sun.getOrNull(i)?.takeIf { it.size == 8 } ?: ByteArray(8)) }
        return out.array()
    }

    fun iconTestPayload(start: Int): ByteArray {
        val days = (0 until 7).map { d -> ByteArray(9) { (start + d).toByte() } }
        val hours = (0 until 24).map { h -> byteArrayOf(120.toByte(), (start + h).toByte()) }
        return build(days, hours, "ICON $start", List(7) { ByteArray(8) })
    }
    fun sendIconTest(c: Context, start: Int) {
        status.value = if (WatchFeatureSwitches.isEnabled(c, WatchFeature.WEATHER) && WatchLink.sendWeather(iconTestPayload(start)))
            "Icon test sent: day 1 = code $start, day 2 = ${start + 1} ..." else "Weather disabled or watch not connected"
    }
    fun testPayload(name: String): ByteArray {
        val days = (0 until 7).map { d -> ByteArray(9) { k -> ((10 * (k + 1) + d + 100) and 0xFF).toByte() } }
        val hours = (0 until 24).map { h -> byteArrayOf(((50 + h + 100) and 0xFF).toByte(), ((70 + h + 100) and 0xFF).toByte()) }
        return build(days, hours, name.ifBlank { "TEST" }, List(7) { ByteArray(8) })
    }

    fun detectedCity(c: Context): String = prefs(c).getString("weather_detected_city", "") ?: ""

    /** Finds the city for the device's current location (blocking; call off the main thread). */
    fun detectCity(c: Context): String? {
        val loc = location(c) ?: return detectedCity(c).ifBlank { null }
        val lat = loc.latitude
        val lon = loc.longitude
        var name: String? = null
        try {
            if (android.location.Geocoder.isPresent()) {
                @Suppress("DEPRECATION")
                val list = android.location.Geocoder(c, Locale.ENGLISH).getFromLocation(lat, lon, 1)
                val a = list?.firstOrNull()
                name = a?.locality ?: a?.subAdminArea ?: a?.adminArea
            }
        } catch (_: Exception) {}
        if (name.isNullOrBlank()) {
            try {
                val json = JSONObject(httpGet("https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=$lat&longitude=$lon&localityLanguage=en"))
                name = json.optString("city").ifBlank { json.optString("locality") }.ifBlank { json.optString("principalSubdivision") }
            } catch (_: Exception) {}
        }
        if (!name.isNullOrBlank()) prefs(c).edit().putString("weather_detected_city", name).apply()
        return name?.takeIf { it.isNotBlank() } ?: detectedCity(c).ifBlank { null }
    }

    fun hasLocation(c: Context): Boolean =
        c.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            c.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun location(c: Context): Location? {
        if (!hasLocation(c)) return null
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val last = lm.getProviders(true).mapNotNull { provider ->
            try { lm.getLastKnownLocation(provider) } catch (_: Exception) { null }
        }.maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 6 * 60 * 60 * 1000L) return last
        if (Build.VERSION.SDK_INT >= 30) {
            val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
                .firstOrNull { try { lm.isProviderEnabled(it) } catch (_: Exception) { false } }
            if (provider != null) {
                val executor = Executors.newSingleThreadExecutor()
                try {
                    val latch = CountDownLatch(1)
                    var found: Location? = null
                    lm.getCurrentLocation(provider, null, executor) { found = it; latch.countDown() }
                    latch.await(12, TimeUnit.SECONDS)
                    if (found != null) return found
                } finally { executor.shutdown() }
            }
        }
        return last
    }

    private fun requestUrl(lat: Double, lon: Double, tz: String, c: Context): Pair<String, String> {
        if (useCustomApi(c)) {
            val raw = customApiUrl(c).trim()
            require(raw.startsWith("https://", true) || raw.startsWith("http://", true)) { "Custom weather URL must start with https:// or http://" }
            val url = raw.replace("{latitude}", lat.toString(), true)
                .replace("{longitude}", lon.toString(), true)
                .replace("{timezone}", URLEncoder.encode(tz, "UTF-8"), true)
            return url to customApiKey(c)
        }
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,is_day,wind_speed_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset" +
            "&hourly=temperature_2m,weather_code,is_day,wind_speed_10m" +
            "&timezone=${URLEncoder.encode(tz, "UTF-8")}&forecast_days=7"
        return url to ""
    }

    private fun parseLocalDateTime(value: String, timezone: TimeZone): Date? {
        val patterns = listOf("yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd'T'HH:mm:ss")
        for (pattern in patterns) {
            try {
                return SimpleDateFormat(pattern, Locale.US).apply { isLenient = false; timeZone = timezone }.parse(value)
            } catch (_: Exception) { }
        }
        return null
    }

    /**
     * Converts a forecast JSON response into the watch's 199-byte payload. The current-hour
     * index is matched in the forecast's declared timezone; missing hours are never silently
     * replaced with array element zero. Hourly condition codes are independent per hour.
     */
    fun parseAndSerialize(jsonStr: String, cityLabel: String, applyFix: Boolean = celsiusNumbersForPayload): ByteArray {
        val json = JSONObject(jsonStr)
        val tzName = json.optString("timezone", "UTC").ifBlank { "UTC" }
        val tz = TimeZone.getTimeZone(tzName)
        val celsius = applyFix
        val current = json.optJSONObject("current")
        val currentTemp = current?.optDouble("temperature_2m", Double.NaN)?.takeIf { it.isFinite() }
        val currentCode = current?.optInt("weather_code", Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }
        val currentIsDay = current?.optInt("is_day", -1)?.let { if (it == 1) true else if (it == 0) false else null }
        val currentWind = current?.optDouble("wind_speed_10m", 0.0) ?: 0.0
        val daily = json.optJSONObject("daily") ?: throw IllegalArgumentException("Weather response is missing daily data")
        val hourly = json.optJSONObject("hourly") ?: throw IllegalArgumentException("Weather response is missing hourly data")

        val dailyTimes = daily.optJSONArray("time") ?: throw IllegalArgumentException("Weather response is missing daily.time")
        val dailyCodes = daily.optJSONArray("weather_code") ?: throw IllegalArgumentException("Weather response is missing daily.weather_code")
        val dailyMax = daily.optJSONArray("temperature_2m_max") ?: throw IllegalArgumentException("Weather response is missing daily.temperature_2m_max")
        val dailyMin = daily.optJSONArray("temperature_2m_min") ?: throw IllegalArgumentException("Weather response is missing daily.temperature_2m_min")
        val sunrise = daily.optJSONArray("sunrise") ?: throw IllegalArgumentException("Weather response is missing daily.sunrise")
        val sunset = daily.optJSONArray("sunset") ?: throw IllegalArgumentException("Weather response is missing daily.sunset")

        val times = hourly.optJSONArray("time") ?: throw IllegalArgumentException("Weather response is missing hourly.time")
        val temps = hourly.optJSONArray("temperature_2m") ?: throw IllegalArgumentException("Weather response is missing hourly.temperature_2m")
        val codes = hourly.optJSONArray("weather_code") ?: throw IllegalArgumentException("Weather response is missing hourly.weather_code")
        val isDay = hourly.optJSONArray("is_day") ?: throw IllegalArgumentException("Weather response is missing hourly.is_day")
        val winds = hourly.optJSONArray("wind_speed_10m")
        val count = minOf(times.length(), temps.length(), codes.length(), isDay.length())
        if (count < 24) throw IllegalArgumentException("Hourly forecast has fewer than 24 complete hours")

        val nowLabel = SimpleDateFormat("yyyy-MM-dd'T'HH:00", Locale.US).apply { timeZone = tz }.format(Date())
        var start = -1
        for (i in 0 until count) {
            if (times.optString(i).startsWith(nowLabel)) { start = i; break }
        }
        if (start < 0) {
            val now = System.currentTimeMillis()
            start = (0 until count).firstOrNull { i ->
                val parsed = parseLocalDateTime(times.optString(i), tz)
                parsed != null && parsed.time >= now - 15 * 60_000L
            } ?: throw IllegalArgumentException("The forecast does not include the current local hour")
        }
        if (start + 23 >= count) throw IllegalArgumentException("The forecast does not contain 24 hours from the current local time")

        val hourlyItems = ArrayList<HourlyPreviewItem>(24)
        val hourBytes = (0 until 24).map { offset ->
            val index = start + offset
            val time = times.getString(index)
            val temp = temps.getDouble(index)
            val wmo = codes.getInt(index)
            val day = isDay.getInt(index) == 1
            val wind = if (winds != null && index < winds.length()) winds.optDouble(index, 0.0) else 0.0
            val icon = cond(wmo, day, null, wind, temp)
            val parsed = parseLocalDateTime(time, tz)
            val label = parsed?.let { SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = tz }.format(it) }
                ?: time.substringAfter('T').take(5)
            hourlyItems += HourlyPreviewItem(time, label, temp, wmo, day, icon.toInt(), conditionLabel(wmo, day))
            byteArrayOf(encodeTemp(temp, celsius), icon)
        }
        hourlyPreview.value = hourlyItems
        // The first daily slot is also used by the watch as its current-condition tile.
        // Use the provider's actual current weather_code + is_day there, so a clear 9 PM
        // does not show a daytime sun merely because the daily forecast describes the day.
        val currentIcon = if (currentCode != null && currentIsDay != null) {
            cond(currentCode, currentIsDay, null, currentWind, currentTemp).toInt()
        } else {
            hourBytes.firstOrNull()?.getOrNull(1)?.toInt()?.and(0xFF) ?: 7
        }

        val dayBytes = (0 until 7).map { i ->
            val hasDay = i < minOf(dailyTimes.length(), dailyCodes.length(), dailyMax.length(), dailyMin.length())
            val date = if (hasDay) dailyTimes.getString(i) else ""
            val hi = if (hasDay) dailyMax.getDouble(i) else (currentTemp ?: 20.0)
            val lo = if (hasDay) dailyMin.getDouble(i) else (currentTemp ?: 20.0)
            val wmo = if (hasDay) dailyCodes.getInt(i) else 7
            val currentValue = if (i == 0) currentTemp ?: (hi + lo) / 2.0 else (hi + lo) / 2.0
            ByteArray(9).also { b ->
                b[0] = (if (i == 0) currentIcon else cond(wmo, true).toInt()).toByte()
                b[1] = encodeTemp(currentValue, celsius)
                b[2] = encodeTemp(hi, celsius)
                b[3] = encodeTemp(lo, celsius)
            }
        }

        val dailyItems = ArrayList<DailyPreviewItem>(7)
        val sunBytes = (0 until 7).map { i ->
            val date = if (i < dailyTimes.length()) dailyTimes.getString(i) else ""
            val riseText = if (i < sunrise.length()) sunrise.optString(i) else ""
            val setText = if (i < sunset.length()) sunset.optString(i) else ""
            val rise = parseLocalDateTime(riseText, tz)?.time?.div(1000L)?.toInt() ?: 0
            val set = parseLocalDateTime(setText, tz)?.time?.div(1000L)?.toInt() ?: 0
            if (i < dailyCodes.length() && i < dailyMax.length() && i < dailyMin.length()) {
                val wmo = dailyCodes.getInt(i)
                dailyItems += DailyPreviewItem(
                    date, date, dailyMax.getDouble(i), dailyMin.getDouble(i), wmo,
                    if (i == 0) currentIcon else cond(wmo, true).toInt(), riseText, setText
                )
            }
            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(rise).putInt(set).array()
        }
        dailyPreview.value = dailyItems

        val payload = build(dayBytes, hourBytes, cityLabel.ifBlank { "Here" }, sunBytes)
        lastPayloadBytes.value = payload
        lastSyncTime.value = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        return payload
    }

    private fun fetchLive(c: Context): ByteArray? {
        celsiusNumbersForPayload = celsiusNumbers(c)
        var lat = latitude(c)
        var lon = longitude(c)
        var tz = timezone(c)
        var name = city(c)
        if (useLocation(c)) {
            val loc = location(c) ?: run {
                status.value = if (hasLocation(c)) "Waiting for a location fix" else "Location permission needed"
                return null
            }
            lat = loc.latitude
            lon = loc.longitude
            tz = "auto"
            name = detectCity(c) ?: "Current location"
        } else if (name.isBlank() && lat == 0.0 && lon == 0.0) {
            if (!hasLocation(c)) {
                status.value = "Choose a city or enable current location"
                return null
            }
            val loc = location(c) ?: run { status.value = "No location fix available"; return null }
            lat = loc.latitude
            lon = loc.longitude
            tz = "auto"
            name = "Current location"
        }
        val (url, key) = requestUrl(lat, lon, tz, c)
        val json = httpGet(url, key)
        val place = listOf(name, selectedCountry(c)).filter { it.isNotBlank() }.joinToString(", ")
            .ifBlank { if (lat != 0.0 || lon != 0.0) "${"%.2f".format(Locale.US, lat)}, ${"%.2f".format(Locale.US, lon)}" else "Here" }
        return parseAndSerialize(json, place, celsiusNumbersForPayload)
    }

    fun sendLive(c: Context) {
        if (!WatchFeatureSwitches.isEnabled(c, WatchFeature.WEATHER)) {
            status.value = "Weather is off"
            return
        }
        val app = c.applicationContext
        scope.launch {
            try {
                status.value = "Getting weather…"
                val payload = fetchLive(app) ?: return@launch
                if (!WatchFeatureSwitches.isEnabled(app, WatchFeature.WEATHER)) {
                    status.value = "Weather is off"
                    return@launch
                }
                status.value = if (WatchLink.sendWeather(payload)) "Weather sent" else "Watch not connected"
            } catch (e: Exception) {
                AppLogger.logError("Weather", "sendLive", e)
                status.value = "Weather failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun sendTest(c: Context) {
        if (!WatchFeatureSwitches.isEnabled(c, WatchFeature.WEATHER)) {
            status.value = "Weather is off"
            return
        }
        status.value = if (WatchLink.sendWeather(testPayload(city(c)))) "Test sent - read the watch" else "Watch not connected"
    }

    fun onConnected(c: Context) {
        if (!WatchFeatureSwitches.isEnabled(c, WatchFeature.WEATHER) || loop) return
        val app = c.applicationContext
        loop = true
        scope.launch {
            try {
                while (WatchLinkState.connected.value && WatchFeatureSwitches.isEnabled(app, WatchFeature.WEATHER)) {
                    sendLive(app)
                    delay(3_600_000L)
                }
            } finally { loop = false }
        }
    }
}

