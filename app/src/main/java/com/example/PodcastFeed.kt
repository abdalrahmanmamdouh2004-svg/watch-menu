package com.example

import android.text.Html
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale

/** Public RSS/Atom podcast discovery. No API key or paid service is used. */
object PodcastFeed {
    data class Episode(
        val title: String,
        val link: String,
        val audioUrl: String = "",
        val guid: String = "",
        val published: String = "",
        val publishedAt: Long = 0L
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("title", title)
            .put("link", link)
            .put("audio", audioUrl)
            .put("guid", guid)
            .put("published", published)
            .put("publishedAt", publishedAt)

        companion object {
            fun fromJson(o: JSONObject) = Episode(
                title = o.optString("title"),
                link = o.optString("link"),
                audioUrl = o.optString("audio"),
                guid = o.optString("guid"),
                published = o.optString("published"),
                publishedAt = o.optLong("publishedAt")
            )
        }
    }

    data class Feed(
        val title: String,
        val feedUrl: String,
        val episodes: List<Episode>
    )

    data class LoadResult(val feed: Feed? = null, val error: String? = null)
    data class MatchResult(val index: Int, val titleFromLink: String? = null, val reason: String = "")

    suspend fun load(input: String): LoadResult = withContext(Dispatchers.IO) {
        try {
            val source = input.trim()
            if (source.isBlank()) return@withContext LoadResult(error = "Enter a podcast link first.")
            var direct = normalizeUrl(source) ?: return@withContext LoadResult(error = "Enter a valid link beginning with https://")
            // Spotify share links sometimes use spotify.link short URLs. Resolve those redirects
            // before trying the public oEmbed title and Apple RSS catalogue lookup.
            if (direct.contains("spotify.link", true)) direct = resolveRedirect(direct) ?: direct

            // YouTube podcasts are playlists of videos. Reuse the app's existing public playlist
            // reader so a podcast playlist can be tracked without an API key.
            if (isYouTubePlaylistUrl(direct)) {
                // First use the YouTube Music playlist reader so large playlists can be
                // imported with their full order when the public WEB_REMIX response works.
                val playlist = Playlists.fetch(direct)
                if (playlist.items.isNotEmpty()) {
                    // Podcast playlists list the newest episode first. The app keeps oldest first, so "next" means newer.
                    val episodes = playlist.items.map { Episode(it.name, it.value) }.reversed()
                    return@withContext LoadResult(Feed(
                        playlist.name.takeUnless { it.isBlank() || it.contains("youtube", true) || it.equals("playlist", true) }
                            ?: youtubePlaylistTitle(youtubePlaylistId(direct).orEmpty())
                            ?: "Podcast",
                        direct, episodes
                    ))
                }

                // A public playlist also has a keyless YouTube Atom feed. This is a robust
                // fallback for music.youtube.com share links when the web-client response
                // or HTML parser changes. YouTube currently exposes only a limited window
                // through this feed, so prefer the full playlist reader above when it works.
                val playlistId = youtubePlaylistId(direct)
                if (!playlistId.isNullOrBlank()) {
                    val publicFeedUrl = "https://www.youtube.com/feeds/videos.xml?playlist_id=$playlistId"
                    val rssResult = loadFeedUrl(publicFeedUrl)
                    if (rssResult.feed != null && rssResult.feed.episodes.isNotEmpty()) {
                        // Convert the feed's youtube.com watch URLs to YouTube Music URLs so
                        // later episodes open in the same player the user pasted initially.
                        val musicEpisodes = rssResult.feed.episodes.map { episode ->
                            val videoId = youtubeVideoId(episode.link).orEmpty().ifBlank { youtubeVideoId(episode.guid).orEmpty() }
                            if (videoId.isBlank()) episode
                            else episode.copy(link = "https://music.youtube.com/watch?v=$videoId&list=$playlistId")
                        }
                        AppLogger.log("PodcastFeed", "Loaded ${musicEpisodes.size} episodes from YouTube playlist feed")
                        val atomTitle = rssResult.feed.title.takeUnless { it.isBlank() || it.contains("youtube", true) } ?: youtubePlaylistTitle(playlistId) ?: "Podcast"
                        return@withContext LoadResult(rssResult.feed.copy(title = atomTitle, feedUrl = publicFeedUrl, episodes = musicEpisodes))
                    }
                }

                return@withContext LoadResult(error = "I recognized that YouTube Music playlist link, but YouTube did not return its episode list. Make sure the playlist is Public (not Private), then try again. If it still fails, the playlist may be temporarily unavailable.")
            }

            // Apple Podcasts exposes its RSS URL through Apple's public, keyless lookup endpoint.
            val appleFeed = appleFeedUrl(direct)
            if (!appleFeed.isNullOrBlank()) {
                val result = loadFeedUrl(appleFeed)
                if (result.feed != null) return@withContext result
            }

            // Spotify: recognise the show ID (ignoring ?si= / utm_ share parameters) and read
            // the episode list from Spotify's public embed page, then fall back to the RSS feed.
            if (direct.contains("spotify.com", true) || direct.startsWith("spotify:", true)) {
                return@withContext loadSpotify(direct)
            }

            val body = fetch(direct) ?: return@withContext LoadResult(
                error = when {
                    direct.contains("youtube.com", true) || direct.contains("youtu.be", true) ->
                        "Couldn't read that YouTube page. Did you copy a playlist link (it contains list=) and is the playlist set to Public?"
                    else -> "Couldn't open that link. Check the URL and your connection, or paste the podcast's RSS feed URL."
                }
            )
            if (looksLikeFeed(body)) {
                return@withContext parseFeed(body, direct)
            }

            val discovered = findFeedLinks(body, direct)
            for (candidate in discovered) {
                val xml = fetch(candidate) ?: continue
                val parsed = parseFeed(xml, candidate)
                if (parsed.feed != null) return@withContext parsed
            }

            // Common feed locations are a final, inexpensive discovery attempt for podcast websites.
            val base = runCatching { URL(direct) }.getOrNull()
            if (base != null && !isPlatformPage(direct)) {
                val root = "${base.protocol}://${base.host}"
                for (candidate in listOf("$root/feed", "$root/feed.xml", "$root/podcast.xml", "$root/rss")) {
                    if (candidate == direct) continue
                    val xml = fetch(candidate) ?: continue
                    val parsed = parseFeed(xml, candidate)
                    if (parsed.feed != null) return@withContext parsed
                }
            }

            LoadResult(error = when {
                direct.contains("youtube.com", true) || direct.contains("youtu.be", true) ->
                    "That YouTube link isn't a playlist. Open the podcast's playlist in YouTube Music, tap Share and paste that link."
                direct.contains("music.apple.com", true) || direct.contains("podcasts.apple.com", true) ->
                    "Couldn't read that Apple Podcasts link. Did you copy the show's page link, not a single episode?"
                else -> "I couldn't find a podcast feed at this link. Did you try the podcast's official website, or its RSS feed URL?"
            })
        } catch (e: Exception) {
            AppLogger.logError("PodcastFeed", "load", e)
            LoadResult(error = "Couldn't read this podcast: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ---- Spotify ---------------------------------------------------------------------------

    private fun spotifyShowId(url: String): String? =
        Regex("""spotify\.com/(?:intl-[a-zA-Z-]+/)?(?:embed/)?show/([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1)
            ?: Regex("""spotify:show:([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1)

    private suspend fun loadSpotify(url: String): LoadResult {
        var link = url
        if (link.contains("spotify.link", true)) link = resolveRedirect(link) ?: link
        val id = spotifyShowId(link)
        if (id == null) {
            val what = when {
                link.contains("/episode/", true) -> "That is a single episode link. In Spotify open the podcast's page (tap the show name), tap Share, and paste that link."
                link.contains("/playlist/", true) || link.contains("/album/", true) || link.contains("/track/", true) ->
                    "That Spotify link isn't a podcast. Did you copy it from a show's page (open.spotify.com/show/...)?"
                else -> "Couldn't find a podcast in that Spotify link. Did you copy it from the show's page with Share > Copy link?"
            }
            return LoadResult(error = what)
        }
        val canonical = "https://open.spotify.com/show/$id"

        // 1) Spotify's public embed page lists the latest episodes of a show.
        val embed = runCatching { fetch("https://open.spotify.com/embed/show/$id") }.getOrNull()
        if (embed != null) {
            val parsed = parseSpotifyEmbed(embed, id)
            if (parsed != null && parsed.episodes.isNotEmpty()) {
                AppLogger.log("PodcastFeed", "Spotify show $id: ${parsed.episodes.size} episodes from embed")
                val title = parsed.title.ifBlank { LinkInfo.title(canonical).orEmpty() }.ifBlank { "Podcast" }
                return LoadResult(parsed.copy(title = title, feedUrl = canonical))
            }
        }

        // 2) Match the show's title against Apple's catalogue and use its RSS feed.
        val showTitle = LinkInfo.title(canonical)
        if (!showTitle.isNullOrBlank()) {
            for (term in titleVariants(showTitle)) {
                val feedUrl = appleSearchFeedUrl(term) ?: continue
                val result = loadFeedUrl(feedUrl)
                if (result.feed != null) return result
            }
        }
        return LoadResult(error = "Couldn't read the episodes of that Spotify show. Is the show public, and did you copy the link from the show's page? You can also paste the podcast's RSS feed link instead.")
    }

    private fun titleVariants(title: String): List<String> {
        val out = linkedSetOf(title.trim())
        out.add(title.split(Regex("\\s[-\u2013\u2014|:]\\s|:\\s")).first().trim())
        return out.filter { it.length >= 3 }
    }

    private fun parseSpotifyEmbed(html: String, showId: String): Feed? {
        val json = Regex("""<script[^>]*id=["']__NEXT_DATA__["'][^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return null
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val episodes = LinkedHashMap<String, Episode>()
        var showTitle = ""
        fun walk(node: Any?) {
            when (node) {
                is JSONObject -> {
                    val uri = node.optString("uri")
                    if (uri.startsWith("spotify:episode:")) {
                        val epId = uri.substringAfterLast(':')
                        val title = node.optString("title").ifBlank { node.optString("name") }.trim()
                        if (title.isNotBlank() && epId !in episodes) {
                            val date = node.opt("releaseDate").let { d ->
                                when (d) {
                                    is JSONObject -> d.optString("isoString")
                                    is String -> d
                                    else -> ""
                                }
                            }.orEmpty()
                            episodes[epId] = Episode(title, "https://open.spotify.com/episode/$epId", "", uri, date, parseIsoDate(date))
                        }
                    } else if (showTitle.isBlank() && uri == "spotify:show:$showId") {
                        showTitle = node.optString("title").ifBlank { node.optString("name") }.trim()
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) walk(node.opt(keys.next()))
                }
                is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i))
            }
        }
        walk(root)
        if (episodes.isEmpty()) return null
        val list = episodes.values.toList()
        val ordered = if (list.count { it.publishedAt > 0L } >= list.size * 0.75) list.sortedBy { it.publishedAt } else list.reversed()
        return Feed(showTitle, "https://open.spotify.com/show/$showId", ordered)
    }

    private fun parseIsoDate(value: String): Long {
        if (value.isBlank()) return 0L
        for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd")) {
            val ms = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(value)?.time
            }.getOrNull()
            if (ms != null) return ms
        }
        return 0L
    }


    suspend fun matchEpisode(feed: Feed, episodeUrl: String): MatchResult = withContext(Dispatchers.IO) {
        val wantedUrl = normalizeComparableUrl(episodeUrl)
        val wantedVideoId = youtubeVideoId(episodeUrl)
        feed.episodes.forEachIndexed { index, episode ->
            val candidates = listOf(episode.link, episode.audioUrl, episode.guid).filter { it.isNotBlank() }
            if (candidates.any { candidate ->
                    normalizeComparableUrl(candidate) == wantedUrl ||
                        (wantedVideoId != null && youtubeVideoId(candidate) == wantedVideoId)
                }) {
                return@withContext MatchResult(index, reason = if (wantedVideoId != null) "YouTube video ID" else "URL")
            }
        }

        val title = LinkInfo.title(episodeUrl)
        if (!title.isNullOrBlank()) {
            val wanted = normalizeTitle(title)
            val exact = feed.episodes.indexOfFirst { normalizeTitle(it.title) == wanted }
            if (exact >= 0) return@withContext MatchResult(exact, title, "title")

            val close = feed.episodes.indexOfFirst {
                val candidate = normalizeTitle(it.title)
                candidate.length >= 8 && (candidate.contains(wanted) || wanted.contains(candidate))
            }
            if (close >= 0) return@withContext MatchResult(close, title, "similar title")
        }
        MatchResult(-1, title, "not found")
    }

    /** Merge a refreshed feed without duplicating already-saved episodes. */
    fun mergeEpisodes(existing: List<Episode>, refreshed: List<Episode>): List<Episode> {
        val merged = existing.toMutableList()
        for (fresh in refreshed) {
            val match = merged.indexOfFirst { sameEpisode(it, fresh) }
            if (match < 0) {
                merged.add(fresh)
            } else {
                val old = merged[match]
                // Keep the saved identity/order, but enrich old records if the feed now supplies
                // a publication date or a better episode/audio URL.
                merged[match] = old.copy(
                    link = fresh.link.ifBlank { old.link },
                    audioUrl = fresh.audioUrl.ifBlank { old.audioUrl },
                    guid = old.guid.ifBlank { fresh.guid },
                    published = old.published.ifBlank { fresh.published },
                    publishedAt = if (old.publishedAt > 0L) old.publishedAt else fresh.publishedAt
                )
            }
        }
        // Preserve the queue's source order. New episodes are appended in the order
        // received from the refreshed feed; re-sorting by publish date can reorder playlists.
        return merged
    }

    private fun sameEpisode(a: Episode, b: Episode): Boolean {
        if (a.guid.isNotBlank() && b.guid.isNotBlank() && a.guid.equals(b.guid, true)) return true
        val aUrls = listOf(a.link, a.audioUrl).filter { it.isNotBlank() }.map(::normalizeComparableUrl)
        val bUrls = listOf(b.link, b.audioUrl).filter { it.isNotBlank() }.map(::normalizeComparableUrl)
        if (aUrls.any { it in bUrls }) return true
        val titleMatches = normalizeTitle(a.title).isNotBlank() && normalizeTitle(a.title) == normalizeTitle(b.title)
        if (!titleMatches) return false
        return (a.publishedAt > 0L && a.publishedAt == b.publishedAt) ||
            (aUrls.isEmpty() && bUrls.isEmpty() && a.guid.isBlank() && b.guid.isBlank())
    }

    fun encodeEpisodes(episodes: List<Episode>): String = JSONArray().apply {
        episodes.forEach { put(it.toJson()) }
    }.toString()

    fun decodeEpisodes(raw: String): List<Episode> = try {
        val a = JSONArray(raw)
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            Episode.fromJson(o).takeIf { it.title.isNotBlank() }
        }
    } catch (_: Exception) { emptyList() }

    private fun loadFeedUrl(url: String): LoadResult {
        val body = fetch(url) ?: return LoadResult(error = "The podcast feed couldn't be opened.")
        return parseFeed(body, url)
    }

    /** The playlist's own name, from YouTube's public oEmbed answer. */
    private fun youtubePlaylistTitle(id: String): String? {
        if (id.isBlank()) return null
        return try {
            val body = fetch("https://www.youtube.com/oembed?url=https%3A%2F%2Fwww.youtube.com%2Fplaylist%3Flist%3D$id&format=json") ?: return null
            org.json.JSONObject(body).optString("title").trim().takeIf { it.isNotBlank() && !it.contains("youtube", true) }
        } catch (_: Exception) { null }
    }

    private fun isYouTubePlaylistUrl(url: String): Boolean =
        (url.contains("youtube.com", true) || url.contains("youtu.be", true)) && !youtubePlaylistId(url).isNullOrBlank()

    private fun youtubePlaylistId(url: String): String? =
        Regex("""[?&]list=([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1)

    private fun isYouTubePlaylistFeedUrl(url: String): Boolean =
        url.contains("youtube.com/feeds/videos.xml", true) && Regex("""[?&]playlist_id=[A-Za-z0-9_-]+""", RegexOption.IGNORE_CASE).containsMatchIn(url)

    private fun resolveRedirect(value: String): String? = runCatching {
        val connection = URL(value).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 7_000
            connection.readTimeout = 7_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) WatchMenu/1.0")
            connection.requestMethod = "GET"
            connection.responseCode
            connection.url.toExternalForm()
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun normalizeUrl(value: String): String? {
        val withScheme = if (value.startsWith("https://", true) || value.startsWith("http://", true)) value else "https://$value"
        return runCatching { URL(withScheme).toExternalForm() }.getOrNull()
    }

    private fun fetch(url: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) WatchMenu/1.0 PodcastFeed")
            connection.setRequestProperty("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, text/html, */*")
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.8")
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (total < 2_500_000) {
                    val count = stream.read(buffer, 0, minOf(buffer.size, 2_500_000 - total))
                    if (count <= 0) break
                    output.write(buffer, 0, count)
                    total += count
                }
                String(output.toByteArray(), Charsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun looksLikeFeed(text: String): Boolean =
        Regex("<(rss|feed|rdf:RDF)(\\s|>)", RegexOption.IGNORE_CASE).containsMatchIn(text.take(20_000))

    private fun findFeedLinks(html: String, pageUrl: String): List<String> {
        val out = linkedSetOf<String>()
        val linkTag = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
        for (tag in linkTag.findAll(html)) {
            val text = tag.value
            val type = attr(text, "type").orEmpty().lowercase(Locale.ROOT)
            val rel = attr(text, "rel").orEmpty().lowercase(Locale.ROOT)
            val href = attr(text, "href") ?: continue
            if (type.contains("rss") || type.contains("atom") || type.contains("xml") || rel.contains("alternate")) {
                runCatching { URL(URL(pageUrl), href).toString() }.getOrNull()?.let { out.add(it) }
            }
        }
        return out.toList()
    }

    private fun attr(tag: String, name: String): String? =
        Regex("\\b${Regex.escape(name)}\\s*=\\s*([\\\"'])(.*?)\\1", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.getOrNull(2)

    private fun isPlatformPage(url: String): Boolean =
        url.contains("spotify.com", true) || url.contains("spotify.link", true) ||
            url.contains("youtube.com", true) || url.contains("youtu.be", true) || url.contains("music.apple.com", true)

    private fun appleSearchFeedUrl(showTitle: String): String? {
        val term = showTitle.trim()
        if (term.length < 3) return null
        val json = fetch("https://itunes.apple.com/search?media=podcast&entity=podcast&limit=20&term=${URLEncoder.encode(term, "UTF-8")}")
            ?: return null
        val results = runCatching { JSONObject(json).optJSONArray("results") }.getOrNull() ?: return null
        val wanted = normalizeTitle(term)
        val candidates = (0 until results.length()).mapNotNull { i ->
            val result = results.optJSONObject(i) ?: return@mapNotNull null
            val name = result.optString("collectionName")
            val feed = result.optString("feedUrl")
            if (name.isBlank() || feed.isBlank()) null else name to feed
        }
        val exact = candidates.firstOrNull { normalizeTitle(it.first) == wanted }
        if (exact != null) return exact.second
        // Permit minor punctuation/subtitle variations, but avoid choosing an unrelated show
        // merely because Apple's search returned it near the top.
        val close = candidates.firstOrNull {
            val candidate = normalizeTitle(it.first)
            candidate.length >= 8 && wanted.length >= 8 &&
                (candidate.startsWith(wanted) || wanted.startsWith(candidate)) &&
                minOf(candidate.length, wanted.length).toDouble() / maxOf(candidate.length, wanted.length) >= 0.8
        }
        return close?.second
    }

    private fun appleFeedUrl(url: String): String? {
        if (!url.contains("podcasts.apple.com", true)) return null
        val id = Regex("[?&]i?d?=(\\d+)").find(url)?.groupValues?.getOrNull(1)
            ?: Regex("/id(\\d+)").find(url)?.groupValues?.getOrNull(1)
            ?: return null
        val json = fetch("https://itunes.apple.com/lookup?id=$id&entity=podcast") ?: return null
        val results = runCatching { JSONObject(json).optJSONArray("results") }.getOrNull() ?: return null
        for (i in 0 until results.length()) {
            val feedUrl = results.optJSONObject(i)?.optString("feedUrl")
            if (!feedUrl.isNullOrBlank()) return feedUrl
        }
        return null
    }

    private fun parseFeed(xml: String, feedUrl: String): LoadResult {
        if (!looksLikeFeed(xml)) return LoadResult(error = "That link isn't an RSS or Atom feed.")
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        var showTitle = ""
        val episodes = mutableListOf<Episode>()
        var inEpisode = false
        var itemTitle = ""
        var itemLink = ""
        var audio = ""
        var guid = ""
        var published = ""
        var capture = ""
        val value = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.substringAfter(':').lowercase(Locale.ROOT)
                    if (tag == "item" || tag == "entry") {
                        inEpisode = true
                        itemTitle = ""; itemLink = ""; audio = ""; guid = ""; published = ""
                    } else if (inEpisode && tag == "enclosure") {
                        audio = parser.getAttributeValue(null, "url")?.trim().orEmpty()
                    } else if (inEpisode && tag == "link") {
                        val href = parser.getAttributeValue(null, "href")?.trim().orEmpty()
                        val rel = parser.getAttributeValue(null, "rel")?.trim()?.lowercase(Locale.ROOT).orEmpty()
                        val mime = parser.getAttributeValue(null, "type")?.trim()?.lowercase(Locale.ROOT).orEmpty()
                        if (href.isNotBlank()) {
                            if (rel == "enclosure" || mime.startsWith("audio/")) {
                                if (audio.isBlank()) audio = href
                            } else if ((rel.isBlank() || rel == "alternate" || rel == "self") && itemLink.isBlank()) {
                                itemLink = href
                            }
                        }
                        capture = if (href.isBlank()) "link" else ""
                        value.setLength(0)
                    } else if (inEpisode && tag in setOf("title", "guid", "id", "pubdate", "published", "updated")) {
                        capture = tag
                        value.setLength(0)
                    } else if (!inEpisode && tag == "title" && showTitle.isBlank()) {
                        capture = "show_title"
                        value.setLength(0)
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (capture.isNotBlank()) value.append(parser.text)
                XmlPullParser.END_TAG -> {
                    val tag = parser.name.substringAfter(':').lowercase(Locale.ROOT)
                    if (capture == tag || (capture == "show_title" && tag == "title")) {
                        val text = cleanText(value.toString())
                        when (capture) {
                            "title" -> itemTitle = text
                            "link" -> if (itemLink.isBlank()) itemLink = text
                            "guid", "id" -> guid = text
                            "pubdate", "published", "updated" -> if (published.isBlank()) published = text
                            "show_title" -> showTitle = text
                        }
                        capture = ""
                        value.setLength(0)
                    }
                    if (inEpisode && (tag == "item" || tag == "entry")) {
                        if (itemTitle.isNotBlank()) {
                            val resolvedLink = resolveEpisodeUrl(feedUrl, itemLink)
                            val resolvedAudio = resolveEpisodeUrl(feedUrl, audio)
                            val resolvedGuid = if (guid.startsWith("http://", true) || guid.startsWith("https://", true)) {
                                resolveEpisodeUrl(feedUrl, guid)
                            } else guid
                            val link = resolvedLink.ifBlank { resolvedAudio.ifBlank { resolvedGuid } }
                            episodes.add(Episode(itemTitle, link, resolvedAudio, resolvedGuid, published, parseDate(published)))
                        }
                        inEpisode = false
                        capture = ""
                    }
                }
            }
            event = parser.nextToken()
        }
        if (episodes.isEmpty()) return LoadResult(error = "The feed opened, but it didn't contain any readable episodes.")
        // A YouTube playlist feed is already emitted in playlist order. Sorting it by
        // upload date would scramble a custom episode sequence (including older-first shows).
        val ordered = if (!isYouTubePlaylistFeedUrl(feedUrl) && episodes.count { it.publishedAt > 0L } >= episodes.size * 0.75) {
            episodes.sortedBy { it.publishedAt }
        } else episodes.reversed()   // undated feeds and playlists come newest first
        return LoadResult(Feed(showTitle.ifBlank { hostName(feedUrl) }, feedUrl, ordered))
    }

    private fun cleanText(raw: String): String =
        Html.fromHtml(raw.trim(), Html.FROM_HTML_MODE_LEGACY).toString().replace(Regex("\\s+"), " ").trim()

    private fun resolveEpisodeUrl(feedUrl: String, value: String): String = value.trim().let { raw ->
        if (raw.isBlank()) "" else runCatching { URL(URL(feedUrl), raw).toString() }.getOrDefault(raw)
    }

    private fun hostName(url: String): String = runCatching { URL(url).host.removePrefix("www.") }.getOrDefault("Podcast")

    private fun parseDate(value: String): Long {
        if (value.isBlank()) return 0L
        val formats = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss z", "EEE, d MMM yyyy HH:mm:ss z",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd"
        )
        for (format in formats) {
            val parsed = runCatching { SimpleDateFormat(format, Locale.US).parse(value)?.time }.getOrNull()
            if (parsed != null) return parsed
        }
        return 0L
    }

    private fun normalizeComparableUrl(value: String): String = runCatching {
        val u = URL(value.trim())
        (u.host.lowercase(Locale.ROOT) + u.path.trimEnd('/') + (u.query?.let { "?$it" } ?: ""))
            .removePrefix("www.")
    }.getOrDefault(value.trim().lowercase(Locale.ROOT).trimEnd('/'))


    private fun youtubeVideoId(value: String): String? {
        if (!value.contains("youtube.com", true) && !value.contains("youtu.be", true)) return null
        return Regex("""(?:[?&]v=|youtu\.be/)([A-Za-z0-9_-]{6,})""")
            .find(value)?.groupValues?.getOrNull(1)
    }

    private fun normalizeTitle(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "")
}
