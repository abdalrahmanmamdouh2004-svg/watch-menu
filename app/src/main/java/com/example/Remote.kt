package com.example

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Serves the separate watch app "Watch Remote" (messages start with "c1", see its main.rs). The phone acts as a
 * Bluetooth keyboard + mouse (HID) for a PC. Watch presses are mapped to actions here, so shortcuts are changed on
 * the phone. Presses only act while the watch app is open (heartbeat), and the phone is
 * an ordinary paired phone otherwise. Besides keyboard and mouse it sends media / TV keys (volume, play, home, back) as a HID consumer-control report.
 */
@SuppressLint("MissingPermission")
object Remote {
    enum class Act(val label: String) {
        NONE("Nothing"), UP("Up"), DOWN("Down"), LEFT("Left"), RIGHT("Right"), ENTER("Enter"), ESC("Esc"), TAB("Tab"),
        SPACE("Space"), BACKSPACE("Backspace"), LEFT_CLICK("Left click"), RIGHT_CLICK("Right click"),
        MIDDLE_CLICK("Middle click"), DOUBLE_CLICK("Double click"),
        SCROLL_UP("Scroll up"), SCROLL_DOWN("Scroll down"),
        // media / TV keys, sent as a standard HID "consumer control" (what a TV or PC sees from a media remote)
        BACK("Back"), HOME("Home"), MENU("Menu"), SEARCH("Search"),
        PLAY_PAUSE("Play / Pause"), NEXT_TRACK("Next"), PREV_TRACK("Previous"),
        VOL_UP("Volume up"), VOL_DOWN("Volume down"), MUTE("Mute"),
    }

    /** Every key of the watch, in the order the phone screen shows them (ids are what the watch sends in `c1k`). */
    val PAGE1 = listOf(1 to "Up", 2 to "Down", 3 to "Left", 4 to "Right", 5 to "Centre")
    val PAGE2 = listOf(10 to "Back", 11 to "Home", 13 to "Previous", 12 to "Play / Pause", 14 to "Next", 15 to "Mute", 20 to "Volume up", 21 to "Volume down")
    val BUTTONS = PAGE1 + PAGE2

    private const val EXPIRE_MS = 9_000L
    private const val CONSUMER_HOLD_MS = 15L
    /** A held key is let go if the watch's "still held" pings (every 250 ms) stop for this long. */
    private const val HOLD_WATCHDOG_MS = 1_000L
    /** Non-keyboard actions on a held button (clicks, scroll, media) repeat at this pace after a first pause. */
    private const val REPEAT_DELAY_MS = 400L
    private const val REPEAT_MS = 150L
    /** While the watch app is open, an empty keyboard report this often keeps the PC/TV link out of sniff mode. */
    private const val KEEPALIVE_MS = 400L
    private const val PREFS = "remote"

    val status = MutableStateFlow("Off")
    val hostName = MutableStateFlow<String?>(null)
    val watchOpen = MutableStateFlow(false)
    val enabledFlow = MutableStateFlow(false)

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    /** HID jobs waiting on [executor]; keep-alives are skipped while anything real is queued, so they never pile up. */
    private val queued = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var lastReportAt = 0L

    /** Runs [block] on the HID thread, logging when a press waited there (a sign the PC/TV link is backed up). */
    private fun hid(rxAt: Long = SystemClock.elapsedRealtime(), what: String = "", block: () -> Unit) {
        queued.incrementAndGet()
        executor.execute {
            val wait = SystemClock.elapsedRealtime() - rxAt
            try { block() } finally { queued.decrementAndGet() }
            if (what.isNotEmpty() && wait > 150) AppLogger.log("Remote", "$what waited ${wait} ms for the PC/TV link")
        }
    }
    private var ctx: Context? = null
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var registered = false
    private var slot = -1
    private var lastBeat = 0L

    // ---- settings ----

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun context(): Context? = ctx ?: MediaListenerService.instance?.applicationContext
    fun enabled(c: Context) = prefs(c).getBoolean("enabled", false)
    fun lastHost(c: Context) = prefs(c).getString("host", null)

    fun default(button: Int, gesture: Int): Act = when (button to gesture) {
        1 to 0, 1 to 1 -> Act.UP
        2 to 0, 2 to 1 -> Act.DOWN
        3 to 0, 3 to 1 -> Act.LEFT
        4 to 0, 4 to 1 -> Act.RIGHT
        5 to 0, 5 to 1, 5 to 2, 5 to 3 -> Act.ENTER
        10 to 0 -> Act.BACK
        11 to 0 -> Act.HOME
        12 to 0 -> Act.PLAY_PAUSE
        13 to 0 -> Act.PREV_TRACK
        14 to 0 -> Act.NEXT_TRACK
        15 to 0 -> Act.MUTE
        20 to 0 -> Act.VOL_UP
        21 to 0 -> Act.VOL_DOWN
        else -> Act.NONE
    }

    fun action(c: Context, button: Int, gesture: Int): Act {
        val name = prefs(c).getString("n_${button}_$gesture", null) ?: return default(button, gesture)
        return Act.values().firstOrNull { it.name == name } ?: default(button, gesture)
    }

    fun setAction(c: Context, button: Int, gesture: Int, a: Act) =
        prefs(c).edit().putString("n_${button}_$gesture", a.name).apply()

    /** The phone screen sets one action per key: for the four arrows it also covers the long press. */
    fun setKey(c: Context, button: Int, a: Act) {
        setAction(c, button, 0, a)
        if (button <= 4) setAction(c, button, 1, a)
    }

    fun resetActions(c: Context) {
        val e = prefs(c).edit()
        prefs(c).all.keys.filter { it.startsWith("n_") }.forEach { e.remove(it) }
        e.apply()
    }

    // ---- Bluetooth HID ----

    private val descriptor = byteArrayOf(
        // keyboard, report id 1
        0x05, 0x01, 0x09, 0x06, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x01, 0x05, 0x07, 0x19, 0xE0.toByte(), 0x29, 0xE7.toByte(),
        0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95.toByte(), 0x08, 0x81.toByte(), 0x02, 0x95.toByte(), 0x01, 0x75, 0x08,
        0x81.toByte(), 0x01, 0x95.toByte(), 0x05, 0x75, 0x01, 0x05, 0x08, 0x19, 0x01, 0x29, 0x05, 0x91.toByte(), 0x02,
        0x95.toByte(), 0x01, 0x75, 0x03, 0x91.toByte(), 0x01, 0x95.toByte(), 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
        0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81.toByte(), 0x00, 0xC0.toByte(),
        // mouse, report id 2: 3 buttons, x, y, wheel
        0x05, 0x01, 0x09, 0x02, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x02, 0x09, 0x01, 0xA1.toByte(), 0x00, 0x05, 0x09,
        0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95.toByte(), 0x03, 0x75, 0x01, 0x81.toByte(), 0x02,
        0x95.toByte(), 0x01, 0x75, 0x05, 0x81.toByte(), 0x01, 0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38,
        0x15, 0x81.toByte(), 0x25, 0x7F, 0x75, 0x08, 0x95.toByte(), 0x03, 0x81.toByte(), 0x06, 0xC0.toByte(), 0xC0.toByte(),
        // consumer control (media keys, volume, home/back), report id 3: one 16-bit usage
        0x05, 0x0C, 0x09, 0x01, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x03, 0x15, 0x00, 0x26, 0xFF.toByte(), 0x03,
        0x19, 0x00, 0x2A, 0xFF.toByte(), 0x03, 0x75, 0x10, 0x95.toByte(), 0x01, 0x81.toByte(), 0x00, 0xC0.toByte(),
    )

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registeredNow: Boolean) {
            registered = registeredNow
            handler.post {
                status.value = if (registeredNow) "Ready, pair from the PC" else "Not registered"
                if (registeredNow) context()?.let { c -> lastHost(c)?.let { addr -> connectTo(c, addr) } }
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            handler.post {
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        host = device
                        hostName.value = device?.name ?: device?.address
                        status.value = "Connected"
                        context()?.let { prefs(it).edit().putString("host", device?.address).apply() }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (host?.address == device?.address) { host = null; hostName.value = null }
                        if (registered) status.value = "Not connected to a PC"
                    }
                }
            }
        }
    }

    fun setEnabled(c: Context, on: Boolean) {
        ctx = c.applicationContext
        prefs(c).edit().putBoolean("enabled", on).apply()
        enabledFlow.value = on
        if (on) start(c) else stop()
    }

    /** Starts the HID service if it is enabled (safe to call repeatedly). */
    fun start(c: Context) {
        ctx = c.applicationContext
        enabledFlow.value = enabled(c)
        if (!enabledFlow.value || hid != null) return
        if (android.os.Build.VERSION.SDK_INT < 28) { status.value = "Needs Android 9+"; return }
        val adapter = (c.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) { status.value = "Bluetooth is off"; return }
        try {
            status.value = "Starting…"
            adapter.getProfileProxy(c.applicationContext, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    val h = proxy as BluetoothHidDevice
                    hid = h
                    val sdp = BluetoothHidDeviceAppSdpSettings(
                        "Watch Menu Remote", "Watch remote", "Watch Menu", BluetoothHidDevice.SUBCLASS1_COMBO, descriptor
                    )
                    val ok = h.registerApp(sdp, null, null, executor, callback)
                    if (!ok) status.value = "Not supported on this phone"
                }

                override fun onServiceDisconnected(profile: Int) {
                    hid = null; registered = false; host = null; hostName.value = null
                    status.value = "Stopped"
                }
            }, BluetoothProfile.HID_DEVICE)
        } catch (e: SecurityException) {
            status.value = "Bluetooth permission needed"
        } catch (e: Exception) {
            AppLogger.logError("Remote", "start", e); status.value = "Error: ${e.message}"
        }
    }

    fun stop() {
        try { hid?.unregisterApp() } catch (_: Exception) {}
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            hid?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
        } catch (_: Exception) {}
        hid = null; registered = false; host = null; hostName.value = null
        status.value = "Off"
    }

    fun bonded(): List<BluetoothDevice> = try {
        BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.toList().orEmpty()
    } catch (_: SecurityException) { emptyList() }

    fun connectTo(c: Context, address: String) {
        val d = bonded().firstOrNull { it.address == address } ?: return
        try { hid?.connect(d) } catch (e: Exception) { AppLogger.logError("Remote", "connect", e) }
    }

    fun disconnect() { try { host?.let { hid?.disconnect(it) } } catch (_: Exception) {} }

    // ---- input to the PC ----

    private fun send(id: Int, data: ByteArray, quiet: Boolean = false): Boolean {
        val h = hid ?: run { if (!quiet) handler.post { status.value = "Not started" }; return false }
        val d = host ?: run { if (!quiet) handler.post { status.value = "Not connected to a PC / TV" }; return false }
        return try {
            val ok = h.sendReport(d, id, data)
            lastReportAt = SystemClock.elapsedRealtime()
            if (!ok && !quiet) handler.post { status.value = "Send failed (report $id)" }
            ok
        } catch (e: Exception) { AppLogger.logError("Remote", "send", e); false }
    }

    private fun keyCode(a: Act) = when (a) {
        Act.RIGHT -> 0x4F; Act.LEFT -> 0x50; Act.DOWN -> 0x51; Act.UP -> 0x52
        Act.ENTER -> 0x28; Act.ESC -> 0x29; Act.BACKSPACE -> 0x2A; Act.TAB -> 0x2B; Act.SPACE -> 0x2C
        else -> 0
    }

    /** The keyboard report the PC/TV has now (a held key, or nothing). Only touched on the HID thread. */
    private var kbReport = ByteArray(8)

    private fun key(code: Int) {
        if (code == 0) return
        hid(what = "key") {
            send(1, byteArrayOf(0, 0, code.toByte(), 0, 0, 0, 0, 0))
            send(1, kbReport) // back to whatever is held (normally nothing)
        }
    }

    // ---- holding a page-1 key ----
    // A keyboard action is held down on the PC/TV until the finger lifts, so the PC's own key repeat scrolls smoothly
    // (same feel as holding a real arrow key). Any other action repeats on a timer while held.

    @Volatile private var heldButton = -1
    private var heldAction = Act.NONE

    private val holdWatchdog = Runnable { releaseHeld() }
    private val holdRepeat = object : Runnable {
        override fun run() {
            if (heldButton < 0) return
            context()?.let { perform(it, heldAction) }
            handler.postDelayed(this, REPEAT_MS)
        }
    }

    @Synchronized
    private fun holdDown(c: Context, button: Int) {
        handler.removeCallbacks(holdWatchdog)
        handler.postDelayed(holdWatchdog, HOLD_WATCHDOG_MS)
        if (heldButton == button) return // a "still held" ping
        releaseHeld()
        val a = action(c, button, 0)
        heldButton = button
        heldAction = a
        val code = keyCode(a)
        if (code != 0) {
            hid(what = "key down") {
                kbReport = byteArrayOf(0, 0, code.toByte(), 0, 0, 0, 0, 0)
                send(1, kbReport)
            }
        } else {
            perform(c, a)
            handler.postDelayed(holdRepeat, REPEAT_DELAY_MS)
        }
    }

    @Synchronized
    private fun releaseHeld() {
        handler.removeCallbacks(holdWatchdog)
        handler.removeCallbacks(holdRepeat)
        if (heldButton < 0) return
        heldButton = -1
        hid {
            if (kbReport.any { it.toInt() != 0 }) {
                kbReport = ByteArray(8)
                send(1, kbReport)
            }
        }
    }

    /**
     * Keeps the Bluetooth link to the PC/TV awake while the watch app is open. After a moment of silence most hosts
     * put a HID link into "sniff" mode (the radio only listens every few hundred ms), so the next press lags or
     * arrives in a burst. A no-op report (no keys down) every [KEEPALIVE_MS] stops that; it ends when the app closes.
     */
    private val keepAlive = object : Runnable {
        override fun run() {
            if (!watchOpen.value) return
            val idle = SystemClock.elapsedRealtime() - lastReportAt >= KEEPALIVE_MS
            // repeats the current keyboard state, so a held key stays held
            if (host != null && idle && queued.get() == 0) hid { send(1, kbReport, quiet = true) }
            handler.postDelayed(this, KEEPALIVE_MS)
        }
    }

    private fun mouse(buttons: Int, dx: Int, dy: Int, wheel: Int = 0) = hid(what = "mouse") {
        send(2, byteArrayOf(buttons.toByte(), dx.coerceIn(-127, 127).toByte(), dy.coerceIn(-127, 127).toByte(), wheel.coerceIn(-127, 127).toByte()))
    }

    /**
     * A consumer-control key (usage page 0x0C): press, hold briefly, release. Many TVs/PCs drop a media key whose
     * press and release arrive in the same instant, so the release is sent [CONSUMER_HOLD_MS] later (on the HID
     * executor, so key order stays intact).
     */
    private fun consumer(usage: Int) {
        if (usage == 0) return
        hid(what = "media key") {
            if (send(3, byteArrayOf((usage and 0xFF).toByte(), ((usage shr 8) and 0xFF).toByte()))) {
                try { Thread.sleep(CONSUMER_HOLD_MS) } catch (_: InterruptedException) {}
            }
            send(3, byteArrayOf(0, 0))
        }
    }

    /**
     * Unregisters and registers the HID app again. A PC/TV that paired with an older version keeps the old HID
     * descriptor (without the media-key report) until the phone is forgotten there and paired again: this button
     * plus re-pairing fixes "arrows work, media keys / Back / Home / volume do nothing".
     */
    fun restart(c: Context) {
        stop()
        handler.postDelayed({ start(c) }, 600)
    }

    private fun usageOf(a: Act) = when (a) {
        Act.PLAY_PAUSE -> 0x00CD; Act.NEXT_TRACK -> 0x00B5; Act.PREV_TRACK -> 0x00B6
        Act.VOL_UP -> 0x00E9; Act.VOL_DOWN -> 0x00EA; Act.MUTE -> 0x00E2
        Act.HOME -> 0x0223; Act.BACK -> 0x0224; Act.MENU -> 0x0040; Act.SEARCH -> 0x0221
        else -> 0
    }

    private fun click(mask: Int) { mouse(mask, 0, 0); mouse(0, 0, 0) }

    fun perform(c: Context, a: Act) {
        when (a) {
            Act.NONE -> {}
            Act.UP, Act.DOWN, Act.LEFT, Act.RIGHT,
            Act.ENTER, Act.ESC, Act.TAB, Act.SPACE, Act.BACKSPACE -> key(keyCode(a))
            Act.LEFT_CLICK -> click(1)
            Act.RIGHT_CLICK -> click(2)
            Act.MIDDLE_CLICK -> click(4)
            Act.DOUBLE_CLICK -> { click(1); click(1) }
            Act.BACK, Act.HOME, Act.MENU, Act.SEARCH, Act.PLAY_PAUSE, Act.NEXT_TRACK, Act.PREV_TRACK,
            Act.VOL_UP, Act.VOL_DOWN, Act.MUTE -> consumer(usageOf(a))
            Act.SCROLL_UP -> mouse(0, 0, 0, 3)
            Act.SCROLL_DOWN -> mouse(0, 0, 0, -3)
        }
    }

    /** A press (from the watch, or from the pad on the phone's own screen). */
    fun press(c: Context, button: Int, gesture: Int) = perform(c, action(c, button, gesture))

    // ---- the watch app ----

    private val expire = Runnable { if (SystemClock.elapsedRealtime() - lastBeat > EXPIRE_MS) closeApp() }

    private fun markOpen(s: Int) {
        slot = s
        lastBeat = SystemClock.elapsedRealtime()
        if (!watchOpen.value) {
            watchOpen.value = true
            context()?.let { start(it) }
            WatchLink.setFastLink(true) // short BLE interval: watch presses reach the phone in ~15 ms, not ~50-500 ms
            handler.removeCallbacks(keepAlive)
            handler.post(keepAlive)
        }
        handler.removeCallbacks(expire)
        handler.postDelayed(expire, EXPIRE_MS + 250L)
    }

    private fun closeApp() {
        releaseHeld()
        handler.removeCallbacks(expire)
        handler.removeCallbacks(keepAlive)
        if (watchOpen.value) WatchLink.setFastLink(false) // back to the battery-friendly interval
        slot = -1
        watchOpen.value = false
    }

    /** A ping said which app slot runs. True if it is the Remote app (the others then ignore it). */
    fun onPing(p: ByteArray): Boolean {
        if (p.size < 3) return false
        val s = p[1].toInt() and 0xFF
        if (s != slot) {
            if (watchOpen.value && s != 0xFF && (p[2].toInt() and 1) != 0) handler.post { closeApp() }
            return false
        }
        if ((p[2].toInt() and 1) == 0) handler.post { closeApp() }
        return true
    }

    /** Another watch app said hello: the Remote app is not the one running. */
    fun otherAppHello() { if (watchOpen.value) handler.post { closeApp() } }

    fun onEvent(s: Int, d: ByteArray) {
        when (d[2].toInt().toChar()) {
            'h' -> handler.post { markOpen(s) }
            'f' -> handler.post { markOpen(s) }
            'z' -> handler.post { closeApp() }
            'k' -> if (d.size >= 5) {
                val b = d[3].toInt() and 0xFF
                val g = d[4].toInt() and 0xFF
                // act at once (the HID thread keeps the order); the main thread can be busy with UI / media work
                context()?.let { press(it, b, g) }
                handler.post { markOpen(s) }
            }
            // page-1 hold: down / still held, and release
            'p' -> if (d.size >= 4) {
                val b = d[3].toInt() and 0xFF
                context()?.let { holdDown(it, b) } // at once, not behind the main thread
                handler.post { markOpen(s) }
            }
            'r' -> if (d.size >= 4) {
                val b = d[3].toInt() and 0xFF
                if (heldButton == b) releaseHeld()
            }
            // batched crown volume: n steps in one message
            'v' -> if (d.size >= 4) {
                val n = d[3].toInt() // signed byte
                context()?.let { c ->
                    val a = action(c, if (n > 0) 20 else 21, 0)
                    repeat(kotlin.math.abs(n).coerceAtMost(30)) { perform(c, a) }
                }
                handler.post { markOpen(s) }
            }
        }
    }
}
