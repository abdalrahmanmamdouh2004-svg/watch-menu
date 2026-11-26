package com.example

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL

/** Reads a public playlist or album page and returns its songs. Best effort: sites change their pages. */
object Playlists {
    class Result(val name: String, val items: List<MenuItem>, val error: String? = null)

    fun isPlaylist(url: String): Boolean =
        (url.contains("youtube.com") && Regex("""[?&]list=[\w-]+""").containsMatchIn(url) && !url.contains("list=RD")) ||
            (url.contains("open.spotify.com") && Regex("""/(playlist|album)/\w+""").containsMatchIn(url)) ||
            (url.contains("music.apple.com") && Regex("""/(playlist|album)/""").containsMatchIn(url))

    private fun get(u: String): String? {
        val c = URL(u).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 8000
            c.readTimeout = 12000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64; rv:120.0) Gecko/20100101 Firefox/120.0")
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            c.setRequestProperty("Cookie", "SOCS=CAI; CONSENT=YES+1")
            if (c.responseCode !in 200..299) null
            else c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    /** The hint depends on which service the link came from, so it never suggests the wrong one. */
    private fun friendlyError(url: String): String = when {
        url.contains("youtube.com") -> "Couldn't read that YouTube Music playlist. Did you copy the playlist link (it contains list=), and is the playlist set to Public?"
        url.contains("spotify.com") -> "Couldn't read that Spotify playlist. Did you use Share > Copy link on the playlist, and is it public?"
        url.contains("apple.com") -> "Couldn't read that Apple Music playlist. Did you use Share > Copy Link on the playlist?"
        else -> "Couldn't read that link. Did you copy it from a playlist's Share menu?"
    }

    suspend fun fetch(url: String): Result = withContext(Dispatchers.IO) {
        try {
            val r = when {
                url.contains("youtube.com") -> youtube(url)
                url.contains("spotify.com") -> spotify(url)
                url.contains("apple.com") -> apple(url)
                else -> Result("", emptyList(), "Not a playlist link")
            }
            if (r.error == null || r.items.isNotEmpty()) r
            else Result(r.name, r.items, friendlyError(url))
        } catch (e: Exception) {
            AppLogger.log("Playlists", "fetch failed: ${e.javaClass.simpleName}: ${e.message}")
            Result("", emptyList(), friendlyError(url))
        }
    }

    private fun json(html: String, marker: String): Any? {
        val i = html.indexOf(marker)
        if (i < 0) return null
        val start = html.indexOf('{', i)
        if (start < 0) return null
        return JSONTokener(html.substring(start)).nextValue()
    }

    private fun walk(node: Any?, key: String, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                node.keys().forEach { k ->
                    val v = node.opt(k)
                    if (k == key && v is JSONObject) out.add(v) else walk(v, key, out)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), key, out)
        }
    }

    private fun youtube(url: String): Result {
        val id = Regex("""[?&]list=([\w-]+)""").find(url)?.groupValues?.get(1)
            ?: return Result("", emptyList(), "No playlist id")

        // YouTube Music podcast playlists are rendered with musicPlaylistShelfRenderer, not
        // YouTube's playlistVideoRenderer. Read the public WEB_REMIX browse response first;
        // this is the same unauthenticated endpoint used by open-source YT Music clients.
        val musicResult = youtubeMusicBrowse(id)
        if (musicResult != null && musicResult.items.isNotEmpty()) return musicResult

        // Keep the older HTML parser as a fallback for regular YouTube playlists.
        val html = get("https://www.youtube.com/playlist?list=$id")
            ?: return musicResult ?: Result("", emptyList(), "YouTube did not answer (the playlist may be private)")
        val data = json(html, "ytInitialData") as? JSONObject
            ?: return musicResult ?: Result("", emptyList(), "Could not read the playlist page")
        val vids = ArrayList<JSONObject>()
        walk(data, "playlistVideoRenderer", vids)
        val title = (data.optJSONObject("metadata")?.optJSONObject("playlistMetadataRenderer")?.optString("title"))
            ?.takeIf { it.isNotBlank() } ?: "Playlist"
        val items = vids.mapNotNull { v ->
            val vid = v.optString("videoId")
            val t = v.optJSONObject("title")
            val name = t?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: t?.optString("simpleText").orEmpty()
            val artist = v.optJSONObject("shortBylineText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text").orEmpty()
            if (vid.isBlank() || name.isBlank() || name.startsWith("[")) null
            else MenuItem(name = if (artist.isBlank()) name else "$name - $artist", value = "https://music.youtube.com/watch?v=$vid&list=$id")
        }
        return if (items.isEmpty()) musicResult ?: Result(title, items, "No episodes were returned. Check that the playlist is public.")
        else Result(title, items)
    }

    /** Read a public YouTube Music playlist without requiring a user API key or sign-in. */
    private fun youtubeMusicBrowse(playlistId: String): Result? {
        // The WEB_REMIX public browse request works without a developer API key or login.
        val endpoint = "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false"
        // Keep the client version current. A stale hard-coded version can make YouTube
        // return an empty/invalid response even when the playlist itself is public.
        val clientVersion = currentYouTubeMusicClientVersion()
        val context = JSONObject().put("client", JSONObject()
            .put("clientName", "WEB_REMIX")
            .put("clientVersion", clientVersion)
            .put("hl", "en")
            .put("gl", "US"))
            .put("user", JSONObject())
        val visitorData = youtubeMusicVisitorData()
        val deadline = System.currentTimeMillis() + 18_000L

        // Ordinary playlists use VL; YouTube Music podcast-show browse IDs use MPSP.
        for (browseId in listOf("VL$playlistId", "MPSP$playlistId")) {
            if (System.currentTimeMillis() >= deadline) break
            val items = ArrayList<MenuItem>()
            var playlistTitle = "YouTube Music podcast"
            var response = postYouTubeMusic(
                endpoint,
                JSONObject().put("browseId", browseId).put("context", context),
                clientVersion,
                visitorData
            ) ?: continue
            var page = 0
            try {
                while (page < 12 && items.size < 500 && System.currentTimeMillis() < deadline) {
                    if (page == 0) {
                        val headers = ArrayList<JSONObject>()
                        walk(response, "musicDetailHeaderRenderer", headers)
                        if (headers.isEmpty()) walk(response, "musicResponsiveHeaderRenderer", headers)
                        headers.firstOrNull()?.let { h ->
                            val t = textFromRenderer(h.optJSONObject("title"))
                            if (t.isNotBlank()) playlistTitle = t
                        }
                    }

                    val renderers = ArrayList<JSONObject>()
                    walk(response, "musicResponsiveListItemRenderer", renderers)
                    if (renderers.isEmpty()) walk(response, "musicMultiRowListItemRenderer", renderers)
                    for (renderer in renderers) {
                        val videoId = findString(renderer, "videoId").orEmpty()
                        val title = musicItemTitle(renderer)
                        if (videoId.isBlank() || title.isBlank() || items.any { it.value.contains("v=$videoId") }) continue
                        val subtitle = musicItemSubtitle(renderer)
                        items.add(MenuItem(
                            name = if (subtitle.isBlank()) title else "$title - $subtitle",
                            value = "https://music.youtube.com/watch?v=$videoId&list=$playlistId"
                        ))
                    }
                    val continuation = findContinuation(response).orEmpty()
                    if (continuation.isBlank() || items.size >= 500) break
                    response = postYouTubeMusic(
                        endpoint,
                        JSONObject().put("continuation", continuation).put("context", context),
                        clientVersion,
                        visitorData
                    ) ?: break
                    page++
                }
            } catch (e: Exception) {
                AppLogger.log("Playlists", "YouTube Music browse failed: ${e.javaClass.simpleName}: ${e.message}")
            }
            if (items.isNotEmpty()) return Result(playlistTitle, items)
        }
        return null
    }

    private fun currentYouTubeMusicClientVersion(): String {
        val formatter = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        return "1.${formatter.format(java.util.Date())}.01.00"
    }

    /** WEB_REMIX uses a public visitor id; it is not a user credential or API key. */
    private fun youtubeMusicVisitorData(): String? = runCatching {
        val c = URL("https://music.youtube.com/").openConnection() as HttpURLConnection
        val html = try {
            c.connectTimeout = 2500
            c.readTimeout = 3500
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36")
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            if (c.responseCode !in 200..299) return@runCatching null
            c.inputStream.use { it.readBytes().take(600_000).toByteArray().toString(Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
        // The ytcfg object is deeply nested, so extracting it with a non-greedy
        // brace match can truncate the JSON at the first inner object. Read the
        // visitor token directly instead; it is a plain string field.
        Regex("""[\"']?VISITOR_DATA[\"']?\s*:\s*[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun postYouTubeMusic(
        endpoint: String,
        body: JSONObject,
        clientVersion: String,
        visitorData: String?
    ): JSONObject? {
        val c = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            c.requestMethod = "POST"
            c.connectTimeout = 7000
            c.readTimeout = 10000
            c.doOutput = true
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36")
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            c.setRequestProperty("Accept", "*/*")
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            c.setRequestProperty("Origin", "https://music.youtube.com")
            c.setRequestProperty("X-Origin", "https://music.youtube.com")
            c.setRequestProperty("X-Youtube-Client-Name", "67")
            c.setRequestProperty("X-Youtube-Client-Version", clientVersion)
            if (!visitorData.isNullOrBlank()) c.setRequestProperty("X-Goog-Visitor-Id", visitorData)
            c.setRequestProperty("Cookie", "SOCS=CAI")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
            val raw = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (c.responseCode !in 200..299 || raw.isBlank()) null else JSONObject(raw)
        } finally {
            c.disconnect()
        }
    }

    private fun textFromRenderer(node: JSONObject?): String {
        if (node == null) return ""
        node.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
        val runs = node.optJSONArray("runs") ?: return ""
        return (0 until runs.length()).mapNotNull { runs.optJSONObject(it)?.optString("text")?.takeIf(String::isNotBlank) }.joinToString("")
    }

    private fun musicItemTitle(renderer: JSONObject): String {
        val columns = renderer.optJSONArray("flexColumns")
        if (columns != null && columns.length() > 0) {
            val col = columns.optJSONObject(0)
            val flex = col?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            val text = textFromRenderer(flex?.optJSONObject("text"))
            if (text.isNotBlank()) return text
        }
        return textFromRenderer(renderer.optJSONObject("title"))
            .ifBlank { findString(renderer, "title").orEmpty() }
    }

    private fun musicItemSubtitle(renderer: JSONObject): String {
        val columns = renderer.optJSONArray("flexColumns") ?: return ""
        if (columns.length() < 2) return ""
        val col = columns.optJSONObject(1)
        return textFromRenderer(col?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text"))
    }

    private fun findString(node: Any?, key: String): String? {
        return when (node) {
            is JSONObject -> {
                val direct = node.opt(key)
                if (direct is String && direct.isNotBlank() && key == "videoId") direct
                else {
                    val keys = node.keys()
                    var found: String? = null
                    while (keys.hasNext() && found == null) found = findString(node.opt(keys.next()), key)
                    found
                }
            }
            is JSONArray -> {
                var found: String? = null
                for (i in 0 until node.length()) { found = findString(node.opt(i), key); if (found != null) break }
                found
            }
            else -> null
        }
    }

    private fun findContinuation(node: Any?): String? {
        // Different YouTube Music shelves expose their next-page token in either a
        // continuationItemRenderer or nextContinuationData structure.
        fun search(value: Any?): String? = when (value) {
            is JSONObject -> {
                value.optJSONObject("continuationCommand")?.optString("token")
                    ?.takeIf { it.isNotBlank() }
                    ?: value.optJSONObject("nextContinuationData")?.optString("continuation")
                        ?.takeIf { it.isNotBlank() }
                    ?: value.optJSONObject("continuationEndpoint")?.let(::search)
                    ?: run {
                        val keys = value.keys()
                        var found: String? = null
                        while (keys.hasNext() && found == null) found = search(value.opt(keys.next()))
                        found
                    }
            }
            is JSONArray -> {
                var found: String? = null
                for (i in 0 until value.length()) {
                    found = search(value.opt(i))
                    if (!found.isNullOrBlank()) break
                }
                found
            }
            else -> null
        }
        return search(node)
    }

    private fun spotify(url: String): Result {
        val m = Regex("""/(playlist|album)/(\w+)""").find(url) ?: return Result("", emptyList(), "No playlist id")
        val kind = m.groupValues[1]
        val id = m.groupValues[2]
        val html = get("https://open.spotify.com/embed/$kind/$id") ?: return Result("", emptyList(), "Spotify did not answer")
        val data = json(html, "__NEXT_DATA__") as? JSONObject ?: return Result("", emptyList(), "Could not read the page")
        val entities = ArrayList<JSONObject>()
        walk(data, "entity", entities)
        val e = entities.firstOrNull { it.has("trackList") } ?: return Result("", emptyList(), "No songs found")
        val name = e.optString("name").ifBlank { e.optString("title") }.ifBlank { "Playlist" }
        val list = e.optJSONArray("trackList") ?: JSONArray()
        val items = (0 until list.length()).mapNotNull { i ->
            val t = list.optJSONObject(i) ?: return@mapNotNull null
            val tid = t.optString("uri").substringAfterLast(':')
            val title = t.optString("title")
            val artist = t.optString("subtitle")
            if (tid.isBlank() || title.isBlank()) null
            else MenuItem(
                name = if (artist.isBlank()) title else "$title - $artist",
                value = "https://open.spotify.com/track/$tid"
            )
        }
        return if (items.isEmpty()) Result(name, items, "No songs found (private playlist?)") else Result(name, items)
    }

    private fun apple(url: String): Result {
        val html = get(url) ?: return Result("", emptyList(), "Apple Music did not answer")
        val ld = Regex("""<script[^>]+type=["']application/ld\+json["'][^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html).map { it.groupValues[1] }.toList()
        for (block in ld) {
            val o = try { JSONObject(block) } catch (_: Exception) { continue }
            val tracks = o.optJSONArray("track") ?: continue
            val items = (0 until tracks.length()).mapNotNull { i ->
                val t = tracks.optJSONObject(i) ?: return@mapNotNull null
                val link = t.optString("url")
                val n = t.optString("name")
                val artist = t.optString("artistName")
                if (link.isBlank() || n.isBlank()) null
                else MenuItem(name = if (artist.isBlank()) n else "$n - $artist", value = link)
            }
            if (items.isNotEmpty()) return Result(o.optString("name").ifBlank { "Playlist" }, items)
        }
        return Result("", emptyList(), "No songs found (Apple changed its page?)")
    }
}
