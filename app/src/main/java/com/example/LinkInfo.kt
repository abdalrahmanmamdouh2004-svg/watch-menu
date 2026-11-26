package com.example

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Finds a readable name for a song link. */
object LinkInfo {
    private val urlRegex = Regex("""https?://[^\s<>"]+""")

    /** First link in shared text, e.g. "Song name https://music.youtube.com/watch?v=...". */
    fun extractUrl(text: String): String? = urlRegex.find(text)?.value?.trimEnd('.', ',', ')')

    private fun videoId(url: String): String? {
        Regex("""youtu\.be/([\w-]{6,})""").find(url)?.let { return it.groupValues[1] }
        if (url.contains("youtube.com")) Regex("""[?&]v=([\w-]{6,})""").find(url)?.let { return it.groupValues[1] }
        return null
    }

    private fun get(u: String, limit: Int = 200_000): String? {
        val c = URL(u).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 6000
            c.readTimeout = 6000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) WatchMenu")
            c.setRequestProperty("Accept-Language", "en")
            if (c.responseCode !in 200..299) null
            else c.inputStream.use { st -> st.readBytes().let { b -> String(b, 0, minOf(b.size, limit), Charsets.UTF_8) } }
        } finally {
            c.disconnect()
        }
    }

    private fun clean(t: String): String {
        var s = t.replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"").replace("&#x27;", "'").trim()
        for (suffix in listOf(" | Podcast on Spotify", " - Podcast on Spotify", " - YouTube Music", " - YouTube", " | Spotify", " - Spotify", " | Apple Music")) {
            if (s.endsWith(suffix, ignoreCase = true)) s = s.dropLast(suffix.length).trim()
        }
        return s
    }

    suspend fun title(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val id = videoId(url)
            if (id != null) {
                val watch = URLEncoder.encode("https://www.youtube.com/watch?v=$id", "UTF-8")
                get("https://www.youtube.com/oembed?format=json&url=$watch")?.let {
                    val t = JSONObject(it).optString("title")
                    if (t.isNotBlank()) return@withContext clean(t)
                }
            }
            if (url.contains("spotify.com")) {
                get("https://open.spotify.com/oembed?url=" + URLEncoder.encode(url, "UTF-8"))?.let {
                    val t = JSONObject(it).optString("title")
                    if (t.isNotBlank()) return@withContext clean(t)
                }
            }
            val html = get(url) ?: return@withContext null
            Regex("""<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(html)?.let { return@withContext clean(it.groupValues[1]) }
            Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE)
                .find(html)?.let { return@withContext clean(it.groupValues[1]) }
            null
        } catch (e: Exception) {
            AppLogger.log("LinkInfo", "title lookup failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }
}
