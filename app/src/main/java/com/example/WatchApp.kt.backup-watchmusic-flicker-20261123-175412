package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/** Connection state shared with the Home and Watch pages. */
object WatchAppState {
    /** The "Watch Music" app is open on the watch right now. */
    val open = MutableStateFlow(false)
    val status = MutableStateFlow("")
}

/** The colours of a song's pill on the watch. */
object WatchArt {
    /** A dark colour and a bright one (both RGB565) from a cover. */
    fun gradient(src: Bitmap): Pair<Int, Int> {
        val small = Bitmap.createScaledBitmap(src, 16, 16, true)
        val hsv = FloatArray(3)
        var r = 0L; var g = 0L; var b = 0L
        var bestScore = -1f
        var bright = 0
        for (y in 0 until 16) for (x in 0 until 16) {
            val c = small.getPixel(x, y)
            r += Color.red(c); g += Color.green(c); b += Color.blue(c)
            Color.colorToHSV(c, hsv)
            val score = hsv[1] * hsv[2]
            if (score > bestScore) { bestScore = score; bright = c }
        }
        val avg = Color.rgb((r / 256).toInt(), (g / 256).toInt(), (b / 256).toInt())
        val a = FloatArray(3)
        Color.colorToHSV(avg, a)
        val dark = Color.HSVToColor(floatArrayOf(a[0], minOf(1f, a[1] + 0.1f), a[2] * 0.35f))
        Color.colorToHSV(bright, hsv)
        val vivid = Color.HSVToColor(floatArrayOf(hsv[0], hsv[1].coerceIn(0.45f, 1f), hsv[2].coerceIn(0.6f, 0.9f)))
        return rgb565(dark) to rgb565(vivid)
    }

    /** The cover's four quarters (top-left, top-right, bottom-left, bottom-right), each as its average colour (RGB565). */
    fun quarters(src: Bitmap): IntArray {
        val small = Bitmap.createScaledBitmap(src, 16, 16, true)
        val out = IntArray(4)
        for (q in 0 until 4) {
            val x0 = (q % 2) * 8
            val y0 = (q / 2) * 8
            var r = 0; var g = 0; var b = 0
            for (y in y0 until y0 + 8) for (x in x0 until x0 + 8) {
                val c = small.getPixel(x, y)
                r += Color.red(c); g += Color.green(c); b += Color.blue(c)
            }
            out[q] = rgb565(Color.rgb(r / 64, g / 64, b / 64))
        }
        return out
    }

    /** For a song without a cover: the two name colours in the four quarters. */
    fun fallbackQuarters(c: Pair<Int, Int>): IntArray = intArrayOf(c.first, c.second, c.second, c.first)

    /** For a song without a cover: colours from its name. */
    fun fallback(title: String): Pair<Int, Int> {
        val hue = ((title.hashCode() and 0x7FFFFFFF) % 360).toFloat()
        return rgb565(Color.HSVToColor(floatArrayOf(hue, 0.6f, 0.3f))) to
            rgb565(Color.HSVToColor(floatArrayOf((hue + 30f) % 360f, 0.8f, 0.85f)))
    }

    private fun rgb565(c: Int): Int = ((Color.red(c) shr 3) shl 11) or ((Color.green(c) shr 2) shl 5) or (Color.blue(c) shr 3)
}

/**
 * Talks to the "Watch Music" app on the watch (wp2-apps ABI 1.1 host channel).
 * Frames: 0x90A5 MSG phone -> app (acked), 0xB0A6 EVENT app -> phone, 0x90A7 PING.
 * The message formats are listed at the top of watch-music/src/model.rs.
 */
object WatchApp {
    private const val MSG = 0x90A5
    private const val PING = 0x90A7
    private const val MAX_TRACKS = 50
    private const val TEXT_BYTES = 40
    private const val MODE_EXTERNAL = 2
    private const val MODE_CUSTOM = 3
    private const val MODE_NONE = 0xFF
    /** messages in flight at once; the watch app's inbox holds 4 */
    private const val WINDOW = 3
    /** how long an unacknowledged message waits before it is sent again */
    private const val ACK_MS = 1200L

    private val handler = Handler(Looper.getMainLooper())
    private val ui = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var app: Context
    private var started = false

    /** Current watch category: -1 Home, 0 Music, 1 Podcasts, 2 external controls, 3 Custom. */
    private var mode = -1
    private var tracks: List<MenuItem> = emptyList()
    private var current = -1
    private var currentMode = -2
    private var playing = false
    private var wasConnected = false
    private var seenEpoch = -1
    private var helloSeen = false
    private var lastHelloAt = 0L
    /** bumped whenever the list or the link changes: work started before that is dropped */
    private var gen = 0
    private var lastProgress = 0L
    /** Palette identity/cache for the generic live media session. */
    private var externalColourIdentity: String? = null
    private var externalColourSignature: String? = null
    private var externalColourPending: String? = null

    private val imageBytes = ConcurrentHashMap<String, ByteArray>()
    private val gradients = ConcurrentHashMap<String, Pair<Int, Int>>()
    private val quarters = ConcurrentHashMap<String, IntArray>()
    private val netLimit = Semaphore(4)
    private val colourSent = HashSet<Int>()
    private var colourPending = false

    // up to WINDOW messages in flight, oldest first; acks come back in the same order
    private val out = ArrayDeque<ByteArray>()
    private val window = ArrayDeque<ByteArray>()
    private var tries = 0
    private var retrying = false
    private val timeout = Runnable { onTimeout() }

    private fun prefs() = app.getSharedPreferences("watch_app", Context.MODE_PRIVATE)

    /** Safe to call more than once. Starts the polling loop. */
    fun start(ctx: Context) {
        app = ctx.applicationContext
        if (started) return
        started = true
        // The Watch Music bridge is a permanent core service in this release.
        prefs().edit().putBoolean("enabled", true).apply()
        handler.post(poll)
    }

    /** Refresh category availability and the currently-open list from the phone's saved groups. */
    fun refreshCatalog(ctx: Context) {
        start(ctx)
        MenuStore.reload(app)
        prefs().edit().putInt("version", (prefs().getInt("version", 0) + 1) and 0xFF).apply()
        if (!WatchAppState.open.value) {
            WatchAppState.status.value = "Open Watch Music to refresh"
            return
        }
        gen++
        clearQueue()
        colourSent.clear()
        enqueue(byteArrayOf('R'.code.toByte(), catalogFlags().toByte()))
        when (mode) {
            0, 1, MODE_CUSTOM -> {
                refreshList()
                sendCurrentList(clearFirst = false)
            }
            MODE_EXTERNAL -> sendExternalSnapshot()
            else -> WatchAppState.status.value = "Watch categories refreshed"
        }
    }

    /** The watch-side Home page uses these bits to show only categories with actual entries. */
    private fun catalogFlags(): Int {
        MenuStore.init(app)
        val groups = MenuStore.groups.value
        var flags = 0
        if (groups.any { it.type == GroupType.MUSIC && it.items.isNotEmpty() }) flags = flags or 0x01
        if (groups.any { it.type == GroupType.PODCAST && it.items.isNotEmpty() }) flags = flags or 0x02
        if (groups.any { it.type == GroupType.CUSTOM && it.items.isNotEmpty() }) flags = flags or 0x04
        val live = MediaMenuService.instance?.watchAppMediaSnapshot()
        if (live != null && live.title.isNotBlank() && !live.title.equals("Nothing playing", true)) flags = flags or 0x08
        return flags
    }

    private fun version() = prefs().getInt("version", 0) and 0xFF

    // ---- polling ----

    private val poll = object : Runnable {
        override fun run() {
            val linked = WatchLink.connectedNow()
            val epoch = WatchLink.epoch
            // a link that dropped and came back between two polls is a new session too
            if ((wasConnected && !linked) || (linked && epoch != seenEpoch)) reset()
            seenEpoch = epoch
            wasConnected = linked
            if (linked) WatchLink.appFrame(PING, byteArrayOf(0))
            handler.postDelayed(this, if (WatchAppState.open.value) 250L else 1500L)
            if (WatchAppState.open.value && mode >= 0 && System.currentTimeMillis() - lastProgress > 2000) {
                lastProgress = System.currentTimeMillis()
                sendProgress()
            }
        }
    }

    private fun clearQueue() {
        out.clear()
        window.clear()
        tries = 0
        retrying = false
        handler.removeCallbacks(timeout)
    }

    private fun reset() {
        gen++
        clearQueue()
        mode = -1
        current = -1
        helloSeen = false
        externalColourIdentity = null
        externalColourSignature = null
        externalColourPending = null
        if (WatchAppState.open.value) WatchAppState.open.value = false
    }

    // ---- frames from WatchLink ----

    fun onPing(p: ByteArray) {
        if (p.size < 3) return
        if (Replies.onPing(p)) return // the Watch Replies app is the one running
        val slot = p[1].toInt() and 0xFF
        val flags = p[2].toInt() and 0xFF
        val open = slot != 0xFF && (flags and 1) != 0
        if (open == WatchAppState.open.value) return
        if (open) {
            markOpen()
        } else {
            // a ping that was answered just before the app started must not close an app that already said hello
            if (SystemClock.elapsedRealtime() - lastHelloAt < 2500L) return
            WatchAppState.open.value = false
            mode = -1
            current = -1
            helloSeen = false
            gen++
            clearQueue()
            WatchAppState.status.value = ""
        }
    }

    /**
     * The watch app is open. Its own hello proves that as well as the slower ping does, and it can arrive first:
     * state that the hello already set up (restored song, list) must survive the ping catching up.
     */
    private fun markOpen() {
        if (WatchAppState.open.value) return
        WatchAppState.open.value = true
        if (helloSeen) return
        mode = -1
        current = -1
        WatchAppState.status.value = "Watch app open"
    }

    fun onAck(p: ByteArray) {
        if (Replies.onAck(p)) return // it is for the Watch Replies app
        if (window.isEmpty()) return
        when (if (p.isNotEmpty()) p[0].toInt() and 0xFF else 0) {
            0 -> {
                handler.removeCallbacks(timeout)
                window.removeFirst()
                tries = 0
                if (window.isNotEmpty()) handler.postDelayed(timeout, ACK_MS)
                pump()
            }
            8, 9 -> { // inbox full or a popup covers the app: send the window again shortly
                handler.removeCallbacks(timeout)
                if (retrying) return
                if (++tries > 60) { clearQueue(); return }
                retrying = true
                handler.postDelayed({ retrying = false; resendWindow() }, 70)
            }
            else -> clearQueue() // no app / bad request
        }
    }

    fun onEvent(p: ByteArray) {
        if (p.size < 3) return
        val d = p.copyOfRange(2, p.size)
        if (d.size >= 3 && d[0] == 'r'.code.toByte() && d[1] == '1'.code.toByte()) {
            Replies.onEvent(p[1].toInt() and 0xFF, d) // Watch Replies (the separate app)
            return
        }
        when (d[0].toInt().toChar()) {
            'h' -> if (d.size >= 3) { Replies.musicHello(p[1].toInt() and 0xFF); onHello(
                d[1].toInt() and 0xFF,
                d[2].toInt() and 0xFF,
                if (d.size >= 4) d[3].toInt() and 0xFF else 0,
                freshLaunch = d.size >= 5 && d[4].toInt() != 0,
                playerResume = d.size >= 6 && d[5].toInt() != 0
            ) }
            's' -> if (d.size >= 2) select(d[1].toInt() and 0xFF)
            'p' -> toggle()
            'n' -> skip(true)
            'b' -> skip(false)
            'v' -> if (d.size >= 2) setVolume(d[1].toInt() and 0xFF)
            'k' -> if (d.size >= 3) seek((d[1].toInt() and 0xFF) or ((d[2].toInt() and 0xFF) shl 8))
            'm' -> volume {
                val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0)
            }
        }
    }

    // ---- sending ----

    private fun transmit(m: ByteArray) {
        WatchLink.appFrame(MSG, byteArrayOf(0xFF.toByte()) + m)
    }

    private fun resendWindow() {
        if (window.isEmpty()) return
        for (m in window) transmit(m)
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, ACK_MS)
    }

    private fun onTimeout() {
        if (window.isEmpty()) return
        if (++tries > 3) clearQueue() else resendWindow()
    }

    private fun pump() {
        while (window.size < WINDOW) {
            val m = out.removeFirstOrNull()
            if (m == null) break
            val first = window.isEmpty()
            window.addLast(m)
            transmit(m)
            if (first) { handler.removeCallbacks(timeout); handler.postDelayed(timeout, ACK_MS) }
        }
    }

    private fun enqueue(m: ByteArray) {
        out.addLast(m)
        pump()
    }

    /** Messages that jump the queue (what the person just picked), in the order given. */
    private fun enqueueFirst(list: List<ByteArray>) {
        for (m in list.asReversed()) out.addFirst(m)
        pump()
    }

    private fun refreshList() {
        MenuStore.init(app)
        val type = when (mode) {
            0 -> GroupType.MUSIC
            1 -> GroupType.PODCAST
            MODE_CUSTOM -> GroupType.CUSTOM
            else -> null
        }
        tracks = if (type == null) emptyList() else MenuStore.groups.value
            .filter { it.type == type }
            .flatMap { it.items }
            .take(MAX_TRACKS)
        countParts()
    }

    /** Every category can span multiple groups; an item is routed through the group that owns it. */
    private fun groupOf(item: MenuItem): MenuGroup? {
        val type = when (mode) {
            0 -> GroupType.MUSIC
            1 -> GroupType.PODCAST
            MODE_CUSTOM -> GroupType.CUSTOM
            else -> return null
        }
        return MenuStore.groups.value.firstOrNull { g -> g.type == type && g.items.any { it.id == item.id } }
    }

    /** The saved state of a podcast show (its episode and queue move while it plays). */
    private fun freshShow(item: MenuItem): MenuItem =
        MenuStore.groups.value.asSequence().flatMap { it.items.asSequence() }.firstOrNull { it.id == item.id } ?: item

    /** Send a single live-media row; X is deliberately separate from T so it is never saved as a playlist. */
    private fun liveTextBytes(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size <= TEXT_BYTES) return bytes
        var end = TEXT_BYTES
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return bytes.copyOf(end)
    }

    private fun sendExternalInfo(title: String, artist: String, isPlaying: Boolean) {
        val t = liveTextBytes(title.ifBlank { "Nothing playing" })
        val a = liveTextBytes(artist)
        enqueue(byteArrayOf('X'.code.toByte(), (if (isPlaying) 1 else 0).toByte(), t.size.toByte()) + t + byteArrayOf(a.size.toByte()) + a)
    }

    /** Sends active album-art colours through the same K/Q pipeline used by saved Music and Podcasts. */
    private fun sendExternalColours(snapshot: WatchAppMediaSnapshot) {
        val title = snapshot.title.ifBlank { "Nothing playing" }
        val artist = snapshot.artist
        val identity = "$title\u0000$artist\u0000${snapshot.artworkUri.orEmpty()}"

        fun sendPalette(pair: Pair<Int, Int>, qs: IntArray) {
            val signature = buildString {
                append(pair.first).append(':').append(pair.second).append(':')
                qs.forEach { append(it).append(',') }
            }
            if (identity == externalColourIdentity && signature == externalColourSignature) return
            externalColourIdentity = identity
            externalColourSignature = signature
            externalColourPending = null

            val gradientBytes = byteArrayOf(
                'K'.code.toByte(), 0, 1,
                (pair.first and 0xFF).toByte(), ((pair.first shr 8) and 0xFF).toByte(),
                (pair.second and 0xFF).toByte(), ((pair.second shr 8) and 0xFF).toByte(),
            )
            val quarterBytes = java.io.ByteArrayOutputStream(11).apply {
                write('Q'.code); write(0); write(1)
                qs.forEach { write(it and 0xFF); write((it shr 8) and 0xFF) }
            }.toByteArray()
            enqueue(gradientBytes)
            enqueue(quarterBytes)
        }

        snapshot.artwork?.let { bitmap ->
            val pair = WatchArt.gradient(bitmap)
            sendPalette(pair, WatchArt.quarters(bitmap))
            return
        }

        val uriText = snapshot.artworkUri
        if (!uriText.isNullOrBlank()) {
            if (identity == externalColourIdentity && externalColourSignature != null) return
            if (externalColourPending == identity) return
            externalColourPending = identity
            val requestGeneration = gen
            ui.launch {
                val bitmap = withContext(Dispatchers.IO) { loadExternalArtwork(uriText) }
                val pair = bitmap?.let { WatchArt.gradient(it) } ?: WatchArt.fallback(title)
                val qs = bitmap?.let { WatchArt.quarters(it) } ?: WatchArt.fallbackQuarters(pair)
                handler.post {
                    if (requestGeneration != gen || mode != MODE_EXTERNAL || externalColourPending != identity) return@post
                    sendPalette(pair, qs)
                }
            }
            return
        }

        val pair = WatchArt.fallback(title)
        sendPalette(pair, WatchArt.fallbackQuarters(pair))
    }

    /** Supports artwork URIs exposed by media sessions, including content:// and HTTPS sources. */
    private fun loadExternalArtwork(uriText: String): Bitmap? = try {
        val uri = Uri.parse(uriText)
        val bytes = if (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
            get(uriText)
        } else {
            app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }
        bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    } catch (_: Exception) {
        null
    }

    private fun sendExternalSnapshot() {
        if (!WatchAppState.open.value || mode != MODE_EXTERNAL) return
        val snapshot = MediaMenuService.instance?.watchAppMediaSnapshot()
        if (snapshot != null) {
            sendExternalInfo(snapshot.title, snapshot.artist, snapshot.playing)
            sendExternalColours(snapshot)
            val p = (snapshot.positionMs / 1000L).coerceIn(0L, 65535L).toInt()
            val d = (snapshot.durationMs / 1000L).coerceIn(0L, 65535L).toInt()
            enqueue(byteArrayOf('G'.code.toByte(), (p and 0xFF).toByte(), (p shr 8).toByte(), (d and 0xFF).toByte(), (d shr 8).toByte()))
            playing = snapshot.playing
            WatchAppState.status.value = "Controlling ${snapshot.title.take(36)}"
        } else {
            sendExternalInfo(MediaMenuState.title.value.ifBlank { "Nothing playing" }, MediaMenuState.artist.value, false)
            WatchAppState.status.value = "No active media session"
        }
    }

    /** Called from the home menu and when the watch page resumes in external-control mode. */
    private fun enterExternalControls() {
        if (!WatchAppState.open.value) return
        mode = MODE_EXTERNAL
        current = -1
        currentMode = MODE_EXTERNAL
        tracks = emptyList()
        playing = false
        gen++
        clearQueue()
        colourSent.clear()
        externalColourIdentity = null
        externalColourSignature = null
        externalColourPending = null
        sendVolume(false)
        sendExternalSnapshot()
    }

    private fun externalCommand(token: String) {
        val service = MediaMenuService.instance
        if (service != null) {
            service.watchAppMediaCommand(token)
        } else {
            val (code, name) = when (token) {
                "PLAY", "PAUSE" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to "PLAY_PAUSE"
                "NEXT" -> KeyEvent.KEYCODE_MEDIA_NEXT to "NEXT"
                "PREV" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS to "PREVIOUS"
                else -> return
            }
            TestEngine.sendMediaKeyAudioManager(app, code, name)
        }
        handler.postDelayed({ sendExternalSnapshot() }, if (token == "NEXT" || token == "PREV") 750L else 350L)
    }

    /** Remember the actual saved item ID chosen on the watch, not only its transient list index. */
    private fun rememberWatchSelection(index: Int) {
        if (mode != 0 && mode != 1) return
        val item = tracks.getOrNull(index) ?: return
        prefs().edit()
            .putInt("last_watch_selection_mode", mode)
            .putString("last_watch_selection_item_id", item.id)
            .apply()
    }

    /** Match the active player's title back to the same saved Music/Podcast row so its cover colours stay correct. */
    private fun matchRestoredTrack(title: String): Int {
        val wanted = title.trim()
        if (wanted.isBlank()) return -1
        val exact = tracks.indexOfFirst { rowTexts(it).first.trim().equals(wanted, ignoreCase = true) }
        if (exact >= 0) return exact
        return tracks.indexOfFirst {
            val candidate = rowTexts(it).first.trim()
            candidate.isNotBlank() && (candidate.startsWith(wanted, ignoreCase = true) || wanted.startsWith(candidate, ignoreCase = true))
        }
    }

    /**
     * Restore the track-specific Watch Music player after a cold app launch or a page resume.
     * This does not launch/restart media: it restores the selected Music/Podcast row, sends its playlist metadata,
     * matching live playback state/progress when available, and cover colours, then opens that row's own player.
     */
    private fun restoreLastWatchSelection(expectedMode: Int? = null): Boolean {
        val savedMode = prefs().getInt("last_watch_selection_mode", -1)
        val savedItemId = prefs().getString("last_watch_selection_item_id", "").orEmpty()
        if (savedMode !in 0..1 || savedItemId.isBlank() || (expectedMode != null && savedMode != expectedMode)) return false

        MenuStore.init(app)
        mode = savedMode
        refreshList()
        var index = tracks.indexOfFirst { it.id == savedItemId }
        if (index !in tracks.indices) {
            prefs().edit()
                .remove("last_watch_selection_mode")
                .remove("last_watch_selection_item_id")
                .apply()
            mode = -1
            current = -1
            currentMode = -2
            tracks = emptyList()
            return false
        }

        // If the selected app advanced while Watch Music was away, follow the live title only when it
        // maps back to one of this category's saved rows. An unrelated video/session must not replace it.
        val snapshot = MediaMenuService.instance?.watchAppMediaSnapshot()
        val liveIndex = snapshot?.let { matchRestoredTrack(it.title) } ?: -1
        val matchedLiveItem = liveIndex in tracks.indices
        if (matchedLiveItem) {
            index = liveIndex
            rememberWatchSelection(index)
        }
        current = index
        currentMode = mode
        playing = matchedLiveItem && snapshot?.playing == true
        gen++
        clearQueue()
        colourSent.clear()
        lastProgress = System.currentTimeMillis()

        // W tells the watch to rebuild this exact Music/Podcast player after receiving the fresh list.
        // It is deliberately not the generic external-session (Return to controls) mode.
        // Order matters: the watch opens the player as soon as the row it was told to restore has arrived, so that row,
        // its state and its colours go first and the rest of the list follows behind it.
        enqueue(byteArrayOf('W'.code.toByte(), mode.toByte(), index.toByte(), if (playing) 1 else 0))
        enqueue(byteArrayOf('C'.code.toByte(), tracks.size.toByte(), version().toByte()))
        enqueue(rowMessage(index))
        sendNowPlaying()
        loadCachedArt(tracks[index])
        sendCurrentColours()
        sendVolume(false)
        if (matchedLiveItem && snapshot != null) {
            val pos = (snapshot.positionMs / 1000L).coerceIn(0L, 65535L).toInt()
            val dur = (snapshot.durationMs / 1000L).coerceIn(0L, 65535L).toInt()
            enqueue(byteArrayOf('G'.code.toByte(), (pos and 0xFF).toByte(), (pos shr 8).toByte(), (dur and 0xFF).toByte(), (dur shr 8).toByte()))
        }
        sendListRows()
        startCovers()
        WatchAppState.status.value = "Restoring ${if (mode == 1) "podcast" else "song"}: ${tracks[index].name.take(32)}"
        pump()
        return true
    }

    /** True when Android has a playing session that is not the saved song/show the watch would restore. */
    private fun livePlayingElsewhere(): Boolean {
        val snap = MediaMenuService.instance?.watchAppMediaSnapshot() ?: return false
        if (!snap.playing || snap.title.isBlank() || snap.title.equals("Nothing playing", true)) return false
        val savedMode = prefs().getInt("last_watch_selection_mode", -1)
        if (savedMode in 0..1) {
            val keep = mode
            mode = savedMode
            refreshList()
            val matched = matchRestoredTrack(snap.title) >= 0
            mode = keep
            if (matched) return false
        }
        return true
    }

    private fun onHello(count: Int, ver: Int, watchMode: Int, freshLaunch: Boolean, playerResume: Boolean) {
        lastHelloAt = SystemClock.elapsedRealtime()
        helloSeen = true
        markOpen()
        if (watchMode == MODE_EXTERNAL) {
            enterExternalControls()
            return
        }
        // MODE_NONE + freshLaunch means a new app instance. playerResume means the existing page was
        // paused while the user used another watch screen. In both cases restore only the last Music/Podcast
        // selection; never substitute the generic active Android media session.
        // Something plays that is not one of the saved rows: a cold launch goes straight to its controls.
        val jump = watchMode == MODE_NONE && freshLaunch && livePlayingElsewhere()
        if (!jump && ((watchMode == MODE_NONE && freshLaunch) || (playerResume && watchMode in 0..1))) {
            if (restoreLastWatchSelection(if (playerResume) watchMode else null)) return
        }
        if (watchMode == MODE_NONE || watchMode !in 0..1 && watchMode != MODE_CUSTOM) {
            if (mode != -1) { mode = -1; current = -1; gen++; clearQueue() }
            WatchAppState.status.value = "Watch app open"
            enqueue(byteArrayOf('H'.code.toByte(), (catalogFlags() or (if (jump) 0x10 else 0)).toByte()))
            return
        }
        if (watchMode != mode) { mode = watchMode; current = -1; playing = false }
        refreshList()
        gen++
        if (tracks.isEmpty()) {
            WatchAppState.status.value = when (mode) {
                1 -> "Add a podcast first"
                MODE_CUSTOM -> "Add a Custom item first"
                else -> "Add a Music item first"
            }
            clearQueue()
            enqueue(byteArrayOf('C'.code.toByte(), 0, version().toByte()))
            return
        }
        clearQueue()
        colourSent.clear()
        // Music is aggregated from every Music group now; never trust an older one-group cache.
        // Podcasts and Custom are also sent from the phone on every entry.
        sendList()
        sendNowPlaying()
        if (mode != MODE_CUSTOM) {
            sendVolume(false)
            sendProgress()
            if (mode == 0 || mode == 1) startCovers()
        }
        WatchAppState.status.value = if (mode == MODE_CUSTOM) "${tracks.size} custom actions" else "${tracks.size} items"
        pump()
    }

    private fun sendEverything() {
        if (mode < 0) {
            WatchAppState.status.value = "Choose a category on the watch"
            return
        }
        refreshList()
        if (tracks.isEmpty()) {
            WatchAppState.status.value = when (mode) {
                1 -> "Add a podcast first"
                MODE_CUSTOM -> "Add a Custom item first"
                else -> "Add a Music item first"
            }
            return
        }
        gen++
        clearQueue()
        colourSent.clear()
        sendCurrentList(clearFirst = false)
    }

    /** Sends the selected category after an optional R catalog message has already been queued. */
    private fun sendCurrentList(clearFirst: Boolean) {
        if (mode < 0) return
        if (tracks.isEmpty()) {
            enqueue(byteArrayOf('C'.code.toByte(), 0, version().toByte()))
            return
        }
        if (clearFirst) {
            gen++
            clearQueue()
            colourSent.clear()
        }
        sendList()
        sendNowPlaying()
        if (mode != MODE_CUSTOM) {
            sendVolume(false)
            sendProgress()
            if (mode == 0 || mode == 1) startCovers()
        }
        WatchAppState.status.value = if (mode == MODE_CUSTOM) "${tracks.size} custom actions sent" else "${tracks.size} items sent"
        pump()
    }

    /** "A - B" names are split in two; anything else is a title. */
    private fun split(name: String): Pair<String, String> {
        val at = name.indexOf(" - ")
        return if (at > 0) name.substring(0, at).trim() to name.substring(at + 3).trim() else name to ""
    }

    private fun low(s: String) = s.trim().lowercase()

    /** How often each name occurs as a part of a "A - B" name in the list: an artist has several songs. */
    private var partCounts: Map<String, Int> = emptyMap()

    private fun countParts() {
        val m = HashMap<String, Int>()
        for (t in tracks) {
            val (a, b) = split(t.name)
            if (b.isEmpty()) continue
            m[low(a)] = (m[low(a)] ?: 0) + 1
            m[low(b)] = (m[low(b)] ?: 0) + 1
        }
        partCounts = m
    }

    /**
     * (song, artist) of a music row. The part that comes back in other rows is the artist; when that does not decide
     * it, "Artist - Song" (the usual way) is assumed.
     */
    private fun musicTexts(item: MenuItem): Pair<String, String> {
        val (a, b) = split(item.name)
        if (b.isEmpty()) return a to ""
        val ca = partCounts[low(a)] ?: 0
        val cb = partCounts[low(b)] ?: 0
        return if (cb > ca) a to b else b to a
    }

    /**
     * The two texts of a row, in the order the watch expects. Music: "Song - Artist" split as before (the watch swaps
     * them, see SWAP_NAMES in model.rs). Podcasts: the last episode first (the big line), the show second.
     */
    private fun rowTexts(item: MenuItem): Pair<String, String> {
        if (mode == MODE_CUSTOM) return item.name to ""
        if (mode != 1) return musicTexts(item)
        val show = freshShow(item)
        val episode = show.episode.ifBlank {
            PodcastFeed.decodeEpisodes(show.podcastEpisodes).getOrNull(show.podcastIndex)?.title.orEmpty()
        }.ifBlank { "Latest episode" }
        return episode to show.name
    }

    /** One list entry: title length, title, artist length, artist. */
    private fun entry(i: Int): ByteArray {
        val (title, artist) = rowTexts(tracks[i])
        val t = watchText(title, i, true).toByteArray(Charsets.UTF_8)
        val a = watchText(artist, i, false).toByteArray(Charsets.UTF_8)
        return byteArrayOf(t.size.toByte()) + t + byteArrayOf(a.size.toByte()) + a
    }

    /** `T` for a single row (a podcast moved to another episode). */
    private fun rowMessage(i: Int): ByteArray = byteArrayOf('T'.code.toByte(), i.toByte(), 1) + entry(i)

    private fun sendList() {
        enqueue(byteArrayOf('C'.code.toByte(), tracks.size.toByte(), version().toByte()))
        sendListRows()
    }

    private fun sendListRows() {
        var i = 0
        while (i < tracks.size) {
            val buf = java.io.ByteArrayOutputStream()
            val first = i
            var n = 0
            while (i < tracks.size) {
                val e = entry(i)
                // 3 header bytes + entries must stay within 128
                if (3 + buf.size() + e.size > 128) break
                buf.write(e)
                i++; n++
            }
            if (n == 0) { i++; continue }
            enqueue(byteArrayOf('T'.code.toByte(), first.toByte(), n.toByte()) + buf.toByteArray())
        }
    }

    private fun sendNowPlaying() {
        enqueue(byteArrayOf('N'.code.toByte(), (if (current in tracks.indices) current else 0xFF).toByte(), if (playing) 1 else 0))
    }

    private fun sendVolume(show: Boolean) {
        try {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val level = am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
            val muted = am.isStreamMute(AudioManager.STREAM_MUSIC)
            enqueue(byteArrayOf('V'.code.toByte(), level.toByte(), if (muted) 1 else 0, if (show) 1 else 0))
        } catch (e: Exception) { }
    }

    private val volumeEcho = Runnable { if (WatchAppState.open.value && mode >= 0) sendVolume(false) }

    private fun setVolume(level: Int) {
        try {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, (level.coerceIn(0, 100) * max + 50) / 100, 0)
        } catch (e: Exception) { }
        // the crown on the watch can send many levels a second: tell the watch the real level once, when it stops
        handler.removeCallbacks(volumeEcho)
        handler.postDelayed(volumeEcho, 600)
    }

    /** `k`: seek to a thousandth of the song. */
    private fun seek(permille: Int) {
        if (mode == MODE_EXTERNAL) {
            MediaMenuService.instance?.watchAppMediaCommand("SEEK", permille)
            handler.postDelayed({ sendExternalSnapshot() }, 250L)
            return
        }
        try {
            val msm = app.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val list = msm.getActiveSessions(MediaListenerService.getComponentName(app)).filter { !MediaMenuConfig.isIgnored(app, it.packageName) }
            val pkg = MediaMenuConfig.targetPackage(app)
            val c = list.firstOrNull { it.packageName == pkg } ?: list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: return
            val dur = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            if (dur > 0) c.transportControls.seekTo(dur * permille.coerceIn(0, 1000) / 1000)
        } catch (e: Exception) { }
    }

    private fun volume(change: () -> Unit) {
        change()
        handler.postDelayed({ if (WatchAppState.open.value) sendVolume(true) }, 120)
    }

    private fun artPrefs() = app.getSharedPreferences("watch_art", Context.MODE_PRIVATE)

    /** Covers already seen before (even in an earlier run of the app) need no download: their colours are on disk. */
    private fun loadCachedArt(item: MenuItem) {
        if (gradients.containsKey(item.value)) return
        val parts = artPrefs().getString(item.value, null)?.split(',') ?: return
        if (parts.size != 6) return
        val v = IntArray(6)
        for (k in 0 until 6) v[k] = parts[k].toIntOrNull() ?: return
        gradients[item.value] = v[0] to v[1]
        quarters[item.value] = intArrayOf(v[2], v[3], v[4], v[5])
    }

    private fun saveCachedArt(item: MenuItem, pair: Pair<Int, Int>, qs: IntArray) {
        try {
            val sp = artPrefs()
            if (sp.all.size > 300) sp.edit().clear().apply()
            sp.edit().putString(item.value, "${pair.first},${pair.second},${qs[0]},${qs[1]},${qs[2]},${qs[3]}").apply()
        } catch (_: Exception) { }
    }

    /** The `K` and `Q` messages of one song. */
    private fun colourMessages(i: Int): List<ByteArray> {
        val item = tracks[i]
        val gr = gradients[item.value] ?: return emptyList()
        val k = java.io.ByteArrayOutputStream()
        k.write('K'.code); k.write(i); k.write(1)
        for (v in intArrayOf(gr.first, gr.second)) { k.write(v and 0xFF); k.write((v shr 8) and 0xFF) }
        val q = java.io.ByteArrayOutputStream()
        q.write('Q'.code); q.write(i); q.write(1)
        for (v in quarters[item.value] ?: intArrayOf(0, 0, 0, 0)) { q.write(v and 0xFF); q.write((v shr 8) and 0xFF) }
        return listOf(k.toByteArray(), q.toByteArray())
    }

    /** The song that plays now gets its colours first, ahead of the rest of the list. */
    private fun sendCurrentColours(jumpQueue: Boolean = false) {
        val i = current
        if (i !in tracks.indices || i in colourSent) return
        val msgs = colourMessages(i)
        if (msgs.isEmpty()) return
        colourSent.add(i)
        if (jumpQueue) enqueueFirst(msgs) else msgs.forEach { enqueue(it) }
    }

    /** Colours from disk go out at once; the missing covers are downloaded (four at a time), the playing one first. */
    private fun startCovers() {
        val g = gen
        for (t in tracks) loadCachedArt(t)
        sendColours()
        val order = tracks.indices.sortedBy { if (it == current) -1 else it }
        for (i in order) {
            val item = tracks[i]
            if (gradients.containsKey(item.value)) continue
            ui.launch {
                if (g != gen) return@launch
                netLimit.acquire()
                try {
                    withContext(Dispatchers.IO) {
                        val bmp = source(item)
                        val pair = bmp?.let { WatchArt.gradient(it) } ?: WatchArt.fallback(split(item.name).first)
                        val qs = bmp?.let { WatchArt.quarters(it) } ?: WatchArt.fallbackQuarters(pair)
                        quarters[item.value] = qs
                        gradients[item.value] = pair
                        // only a real cover is kept on disk; a stand-in colour is tried again next time
                        if (bmp != null) saveCachedArt(item, pair, qs)
                    }
                } finally { netLimit.release() }
                if (g != gen) return@launch
                if (i == current) {
                    sendCurrentColours(jumpQueue = true)
                } else if (!colourPending) {
                    colourPending = true
                    handler.postDelayed({ colourPending = false; if (g == gen) sendColours() }, 200)
                }
            }
        }
    }

    /** One `K` message per run of songs whose colours are known and not yet on the watch. */
    private fun sendColours() {
        var i = 0
        while (i < tracks.size) {
            if (i in colourSent || gradients[tracks[i].value] == null) { i++; continue }
            val buf = java.io.ByteArrayOutputStream()
            val first = i
            var n = 0
            while (i < tracks.size && n < 30 && i !in colourSent) {
                val gr = gradients[tracks[i].value] ?: break
                for (v in intArrayOf(gr.first, gr.second)) { buf.write(v and 0xFF); buf.write((v shr 8) and 0xFF) }
                colourSent.add(i)
                i++; n++
            }
            if (n > 0) enqueue(byteArrayOf('K'.code.toByte(), first.toByte(), n.toByte()) + buf.toByteArray())
            // `Q`: the cover's four quarter colours for the player's background, at most 14 songs per message
            var j = first
            while (j < first + n) {
                val m = minOf(14, first + n - j)
                val qb = java.io.ByteArrayOutputStream()
                for (k in j until j + m) {
                    val qs = quarters[tracks[k].value] ?: intArrayOf(0, 0, 0, 0)
                    for (v in qs) { qb.write(v and 0xFF); qb.write((v shr 8) and 0xFF) }
                }
                enqueue(byteArrayOf('Q'.code.toByte(), j.toByte(), m.toByte()) + qb.toByteArray())
                j += m
            }
        }
    }

    /** `G`: seconds played and song length, read from the player. */
    private fun sendProgress() {
        if (!WatchAppState.open.value || mode < 0 || mode == MODE_CUSTOM) return
        if (mode == MODE_EXTERNAL) {
            // Also clears stale metadata if Android no longer exposes an active media session.
            sendExternalSnapshot()
            return
        }
        try {
            val msm = app.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val list = msm.getActiveSessions(MediaListenerService.getComponentName(app)).filter { !MediaMenuConfig.isIgnored(app, it.packageName) }
            val pkg = MediaMenuConfig.targetPackage(app)
            val c: MediaController = list.firstOrNull { it.packageName == pkg }
                ?: list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: return
            val st = c.playbackState ?: return
            var pos = st.position.coerceAtLeast(0L)
            if (st.state == PlaybackState.STATE_PLAYING) {
                pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime).coerceAtLeast(0L) * st.playbackSpeed).toLong()
            }
            val dur = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            val p = (pos / 1000).coerceIn(0L, 65535L).toInt()
            val d = (dur / 1000).coerceIn(0L, 65535L).toInt()
            enqueueFirst(listOf(byteArrayOf('G'.code.toByte(), (p and 0xFF).toByte(), (p shr 8).toByte(), (d and 0xFF).toByte(), (d shr 8).toByte())))
        } catch (e: Exception) { }
    }

    // ---- what the watch asks for ----

    private fun select(index: Int) {
        if (mode < 0) return
        refreshList()
        val item = tracks.getOrNull(index) ?: return
        val g = groupOf(item) ?: return
        if (mode == MODE_CUSTOM) {
            current = index
            currentMode = mode
            ui.launch {
                val result = ActionRunner.run(app, g, item)
                WatchAppState.status.value = if (result.startsWith("SUCCESS")) "Opened ${item.name.take(30)}" else result.take(80)
                AppLogger.log("WatchApp", "custom ${item.name}: ${result.take(80)}")
            }
            return
        }
        rememberWatchSelection(index)
        // the same song or episode is already loaded in the player: only open the watch's player, do not restart it
        val state = activeState()
        // For a podcast, any media session is not enough: the player must really show this show's episode,
        // otherwise a second tap on a podcast that never started would only open the watch's player page.
        val loadedHere = state != null && (mode != 1 || podcastIsLoaded(item))
        if (current == index && currentMode == mode && loadedHere) {
            playing = state == PlaybackState.STATE_PLAYING
            enqueueFirst(listOf(byteArrayOf('N'.code.toByte(), index.toByte(), if (playing) 1 else 0)))
            handler.postDelayed({ sendProgress() }, 300)
            return
        }
        current = index
        currentMode = mode
        playing = true
        enqueueFirst(listOf(byteArrayOf('N'.code.toByte(), index.toByte(), 1)))
        handler.postDelayed({ sendProgress() }, 1200)
        ui.launch {
            val r = ActionRunner.run(app, g, item)
            AppLogger.log("WatchApp", "play ${item.name}: ${r.take(120)}")
            if (r.startsWith("SUCCESS")) {
                WatchAppState.status.value = "Playing ${item.name.take(30)}"
            } else {
                // let the next tap try again instead of treating the item as loaded, and show why on the phone
                if (current == index && currentMode == mode) current = -1
                WatchAppState.status.value = r.take(100)
            }
        }
    }

    /** True when the live player shows the episode this podcast row is on. */
    private fun podcastIsLoaded(item: MenuItem): Boolean {
        val show = freshShow(item)
        val episode = PodcastFeed.decodeEpisodes(show.podcastEpisodes).getOrNull(show.podcastIndex)?.title ?: show.episode
        val live = MediaMenuService.instance?.watchAppMediaSnapshot()?.title ?: return false
        val want = episode.lowercase().filter { it.isLetterOrDigit() }
        val actual = live.lowercase().filter { it.isLetterOrDigit() }
        return want.isNotEmpty() && actual.isNotEmpty() && (actual.contains(want) || want.contains(actual))
    }

    /** The playback state of the media session that carries the music (playing, paused, buffering), or null. */
    private fun activeState(): Int? = try {
        val msm = app.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val list = msm.getActiveSessions(MediaListenerService.getComponentName(app)).filter { !MediaMenuConfig.isIgnored(app, it.packageName) }
        val pkg = MediaMenuConfig.targetPackage(app)
        val c = list.firstOrNull { it.packageName == pkg } ?: list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val st = c?.playbackState?.state
        if (st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_PAUSED || st == PlaybackState.STATE_BUFFERING) st else null
    } catch (e: Exception) { null }

    private fun mediaKey(code: Int, name: String) {
        // the key goes to the player that the watch controls, never to an app on the ignore list
        val preferred = MediaMenuConfig.targetPackage(app)
        val pkg = try {
            val msm = app.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val list = msm.getActiveSessions(MediaListenerService.getComponentName(app)).filter { !MediaMenuConfig.isIgnored(app, it.packageName) }
            (list.firstOrNull { it.packageName == preferred } ?: list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING })?.packageName ?: preferred
        } catch (e: Exception) { preferred }
        val r = TestEngine.sendMediaKeySession(app, pkg, code, name)
        if (!r.startsWith("SUCCESS")) TestEngine.sendMediaKeyAudioManager(app, code, name)
    }

    private fun toggle() {
        if (mode == MODE_EXTERNAL) {
            val snapshot = MediaMenuService.instance?.watchAppMediaSnapshot()
            externalCommand(if (snapshot?.playing == true) "PAUSE" else "PLAY")
            return
        }
        playing = !playing
        mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "PLAY_PAUSE")
        handler.postDelayed({ sendProgress() }, 400)
    }

    private fun skip(forward: Boolean) {
        if (mode == MODE_EXTERNAL) { externalCommand(if (forward) "NEXT" else "PREV"); return }
        if (mode == 1) { podcastSkip(forward); return }
        if (mode < 0 || mode == MODE_CUSTOM) return
        mediaKey(if (forward) KeyEvent.KEYCODE_MEDIA_NEXT else KeyEvent.KEYCODE_MEDIA_PREVIOUS, if (forward) "NEXT" else "PREVIOUS")
        if (tracks.isEmpty()) return
        current = if (current !in tracks.indices) 0
        else if (forward) (current + 1) % tracks.size else (current + tracks.size - 1) % tracks.size
        rememberWatchSelection(current)
        playing = true
        enqueueFirst(listOf(byteArrayOf('N'.code.toByte(), current.toByte(), 1)))
        handler.postDelayed({ sendProgress() }, 1200)
    }

    /** Podcasts: previous / next episode of the show that plays, then the watch gets the new episode's name. */
    private fun podcastSkip(forward: Boolean) {
        val index = current
        val shown = tracks.getOrNull(index) ?: return
        val show = freshShow(shown)
        val target = show.podcastIndex + if (forward) 1 else -1
        val queue = PodcastFeed.decodeEpisodes(show.podcastEpisodes)
        if (target !in queue.indices) {
            AppLogger.log("WatchApp", "No ${if (forward) "next" else "previous"} episode of ${show.name}")
            return
        }
        val g = gen
        ui.launch {
            PodcastPlayback.playEpisode(app, show.id, target)
            if (g != gen || mode != 1) return@launch
            refreshList()
            if (index in tracks.indices) {
                rememberWatchSelection(index)
                enqueueFirst(listOf(rowMessage(index), byteArrayOf('N'.code.toByte(), index.toByte(), 1)))
                playing = true
            }
            handler.postDelayed({ sendProgress() }, 1500)
            AppLogger.log("WatchApp", "podcast ${if (forward) "next" else "previous"}: ${show.name} -> episode ${target + 1}")
        }
    }

    /** From WatchLink.pushMusic: keeps the watch app's title and picture in step when the player moves on by itself. */
    fun onMusic(state: Int, title: String, artist: String) {
        if (!WatchAppState.open.value || mode < 0 || mode == MODE_CUSTOM) return
        val nowPlaying = state == 2
        val changed = nowPlaying != playing
        playing = nowPlaying
        if (mode == MODE_EXTERNAL) {
            // Re-read the selected Android media session so title, artist, playback state and
            // position all come from the same player (including video/browser sessions).
            handler.postDelayed({ sendExternalSnapshot() }, 60L)
            return
        }
        if (tracks.isEmpty()) return
        // podcasts: the player's titles are the episode names, not the saved rows; only play / pause is followed
        if (mode == 1) {
            if (changed) enqueue(byteArrayOf('S'.code.toByte(), if (playing) 1 else 0))
            return
        }
        val t = title.trim()
        if (t.isNotEmpty()) {
            val idx = tracks.indexOfFirst { val n = it.name.trim(); n.startsWith(t, true) || t.startsWith(n.substringBefore(" - "), true) }
            if (idx >= 0 && idx != current) {
                current = idx
                rememberWatchSelection(idx)
                enqueueFirst(listOf(byteArrayOf('N'.code.toByte(), idx.toByte(), if (playing) 1 else 0)))
                handler.postDelayed({ sendProgress() }, 1200)
                return
            }
        }
        if (changed) enqueue(byteArrayOf('S'.code.toByte(), if (playing) 1 else 0))
    }

    // ---- pictures ----

    private fun get(u: String): ByteArray? = try {
        val c = URL(u).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 6000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64; rv:120.0) Gecko/20100101 Firefox/120.0")
            if (c.responseCode in 200..299) c.inputStream.use { it.readBytes() } else null
        } finally { c.disconnect() }
    } catch (e: Exception) { null }

    private fun pictureUrl(item: MenuItem): String? {
        val v = item.value
        val yt = Regex("""[?&]v=([\w-]{11})""").find(v)?.groupValues?.get(1)
            ?: Regex("""youtu\.be/([\w-]{11})""").find(v)?.groupValues?.get(1)
        if (yt != null) return "https://i.ytimg.com/vi/$yt/mqdefault.jpg"
        if (v.contains("open.spotify.com")) {
            val o = get("https://open.spotify.com/oembed?url=" + java.net.URLEncoder.encode(v, "UTF-8")) ?: return null
            return try { JSONObject(String(o)).optString("thumbnail_url").ifBlank { null } } catch (e: Exception) { null }
        }
        return null
    }

    /** The downloaded cover as a bitmap, or the placeholder. Each cover is downloaded once. */
    private fun source(item: MenuItem): Bitmap? {
        var bytes = imageBytes[item.value]
        if (bytes == null) {
            bytes = pictureUrl(item)?.let { get(it) }
            if (bytes != null) imageBytes[item.value] = bytes
        }
        return bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }

    // ---- text the watch font can draw: ASCII and Cyrillic ----

    private fun drawable(ch: Char) = ch.code in 32..126 || ch.code in 0x0410..0x044F || ch == 'Ё' || ch == 'ё'

    internal fun watchText(name: String, index: Int, isTitle: Boolean): String {
        val plain = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val sb = StringBuilder()
        for (ch in plain) {
            val c = if (drawable(ch)) ch else '?'
            if (c == '?' && sb.isNotEmpty() && sb.last() == '?') continue
            sb.append(c)
        }
        var s = sb.toString().trim()
        if (s.none { it != '?' && it != ' ' && it != '-' }) s = if (isTitle) "Song ${index + 1}" else ""
        // at most TEXT_BYTES bytes, never cutting a character in half
        while (s.toByteArray(Charsets.UTF_8).size > TEXT_BYTES) s = s.dropLast(1)
        return s
    }
}
