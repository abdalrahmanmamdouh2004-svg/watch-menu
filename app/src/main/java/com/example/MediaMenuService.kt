package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import androidx.media.app.NotificationCompat.MediaStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** What the UI shows. */
object MediaMenuState {
    val running = MutableStateFlow(false)
    val menuMode = MutableStateFlow(false)
    val title = MutableStateFlow("")
    val artist = MutableStateFlow("")
    val events = MutableStateFlow<List<String>>(emptyList())
    /** Who sent media commands to us recently, for the "Watch source" picker. Newest first. */
    val senders = MutableStateFlow<List<String>>(emptyList())
    val learning = MutableStateFlow(false)
    /** What the app currently sees playing on the phone, for the Watch screen. */
    val phoneMusic = MutableStateFlow("not checked yet")
    /** Packages that currently have a media session (except ours), for the ignore list. */
    val seenPlayers = MutableStateFlow<List<String>>(emptyList())
}


/** Snapshot of the live Android media session used by the Watch Music page. */
data class WatchAppMediaSnapshot(
    val title: String,
    val artist: String,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val artwork: Bitmap?,
    val artworkUri: String?,
)

/** Simple persisted settings. */
object MediaMenuConfig {
    private fun p(c: Context) = c.getSharedPreferences("media_menu", Context.MODE_PRIVATE)

    private val VALID = setOf("PLAY", "PAUSE", "NEXT", "PREV")
    const val DEFAULT_ENTER = "PAUSE,PREV,PLAY,NEXT"
    const val DEFAULT_EXIT = "NEXT,PREV,NEXT,PREV"
    const val DEFAULT_SWITCH = "PAUSE,PLAY,PAUSE"
    const val DEFAULT_IGNORED = ""
    private val OLD_DEFAULTS = setOf("org.kde.kdeconnect_tf", "org.kde.kdeconnect_tp")

    fun targetPackage(c: Context): String =
        p(c).getString("pkg", "app.revanced.android.apps.youtube.music") ?: "app.revanced.android.apps.youtube.music"
    fun setTargetPackage(c: Context, v: String) = p(c).edit().putString("pkg", v.trim()).apply()

    private fun parseSeq(text: String): List<String> =
        text.split(",").map { it.trim().uppercase() }.filter { it in VALID }

    // Passcode that switches the watch into menu mode (observed from the real music app).
    fun enterText(c: Context): String = p(c).getString("passcode", DEFAULT_ENTER) ?: DEFAULT_ENTER
    fun setEnterText(c: Context, v: String) = p(c).edit().putString("passcode", v).apply()
    fun enterSeq(c: Context): List<String> = parseSeq(enterText(c))

    // Passcode that leaves menu mode (pressed on our own session).
    fun exitText(c: Context): String = p(c).getString("exit_passcode", DEFAULT_EXIT) ?: DEFAULT_EXIT
    fun setExitText(c: Context, v: String) = p(c).edit().putString("exit_passcode", v).apply()
    fun exitSeq(c: Context): List<String> = parseSeq(exitText(c))

    fun idleExit(c: Context): Boolean = p(c).getBoolean("idle_exit", false)
    fun setIdleExit(c: Context, v: Boolean) = p(c).edit().putBoolean("idle_exit", v).apply()
    fun idleSec(c: Context): Int = p(c).getInt("idle_sec", 30)
    fun setIdleSec(c: Context, v: Int) = p(c).edit().putInt("idle_sec", v.coerceIn(1, 3600)).apply()
    fun crownGroups(c: Context): Boolean = p(c).getBoolean("crown_groups", false)
    fun setCrownGroups(c: Context, v: Boolean) = p(c).edit().putBoolean("crown_groups", v).apply()
    fun samePasscode(c: Context): Boolean = p(c).getBoolean("same_passcode", false)
    fun setSamePasscode(c: Context, v: Boolean) = p(c).edit().putBoolean("same_passcode", v).apply()

    fun sameGroupCode(c: Context): Boolean = p(c).getBoolean("same_group_code", true)
    fun setSameGroupCode(c: Context, v: Boolean) = p(c).edit().putBoolean("same_group_code", v).apply()
    fun prevText(c: Context): String = p(c).getString("prev_passcode", "PAUSE,PLAY,PREV") ?: "PAUSE,PLAY,PREV"
    fun setPrevText(c: Context, v: String) = p(c).edit().putString("prev_passcode", v).apply()
    fun prevSeq(c: Context): List<String> = parseSeq(prevText(c))

    fun switchText(c: Context): String = p(c).getString("switch_passcode", DEFAULT_SWITCH) ?: DEFAULT_SWITCH
    fun setSwitchText(c: Context, v: String) = p(c).edit().putString("switch_passcode", v).apply()
    fun switchSeq(c: Context): List<String> = parseSeq(switchText(c))

    fun ignoredApps(c: Context): Set<String> =
        (p(c).getString("ignored_apps", DEFAULT_IGNORED) ?: DEFAULT_IGNORED).split(",").map { it.trim() }
            .filter { it.isNotEmpty() && it !in OLD_DEFAULTS }.toSet()
    /** Saved at once (commit, not apply) so the list survives the app being closed right after a change. */
    fun setIgnoredApps(c: Context, v: Set<String>) {
        p(c).edit().putString("ignored_apps", v.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")).commit()
    }
    /** True for an app on the ignore list: the watch never reads or controls its media. */
    fun isIgnored(c: Context, pkg: String): Boolean = pkg in ignoredApps(c)

    // Kept so the lab screen still compiles.
    fun passcodeText(c: Context): String = enterText(c)
    fun setPasscodeText(c: Context, v: String) = setEnterText(c, v)
    fun passcode(c: Context): List<String> = enterSeq(c)

    fun windowSec(c: Context): Int = p(c).getInt("window", 6)
    fun setWindowSec(c: Context, v: Int) = p(c).edit().putInt("window", v.coerceIn(1, 30)).apply()

    /** Gap that ends a burst of play/pause taps (1 tap = confirm, 3 taps = next group). */
    fun tapMs(c: Context): Int = p(c).getInt("tap_ms", 700)
    fun setTapMs(c: Context, v: Int) = p(c).edit().putInt("tap_ms", v.coerceIn(300, 2000)).apply()

    fun silentAudio(c: Context): Boolean = p(c).getBoolean("silent", true)
    fun setSilentAudio(c: Context, v: Boolean) = p(c).edit().putBoolean("silent", v).apply()

    fun audioFocus(c: Context): Boolean = p(c).getBoolean("focus", true)
    fun setAudioFocus(c: Context, v: Boolean) = p(c).edit().putBoolean("focus", v).apply()

    fun mirrorState(c: Context): Boolean = p(c).getBoolean("mirror", false)
    fun setMirrorState(c: Context, v: Boolean) = p(c).edit().putBoolean("mirror", v).apply()

    fun proxy(c: Context): Boolean = p(c).getBoolean("proxy", true)
    fun setProxy(c: Context, v: Boolean) = p(c).edit().putBoolean("proxy", v).apply()

    /** AUTO, URI, SEARCH, BROWSER or LAUNCH. */
    fun musicMethod(c: Context): String = p(c).getString("music_method", "AUTO") ?: "AUTO"
    fun setMusicMethod(c: Context, v: String) = p(c).edit().putString("music_method", v).apply()

    /** Only commands from this sender count (empty = everyone). Learned with the "Learn the watch" button. */
    fun watchPkg(c: Context): String = p(c).getString("watch_pkg", "") ?: ""
    fun setWatchPkg(c: Context, v: String) = p(c).edit().putString("watch_pkg", v).apply()
    /** Input device id of the watch for key events (-999 = not set). */
    fun watchDevice(c: Context): Int = p(c).getInt("watch_dev", -999)
    fun setWatchDevice(c: Context, v: Int) = p(c).edit().putInt("watch_dev", v).apply()

    fun pauseOnEnter(c: Context): Boolean = p(c).getBoolean("pause_on_enter", true)
    fun setPauseOnEnter(c: Context, v: Boolean) = p(c).edit().putBoolean("pause_on_enter", v).apply()
    fun resumeOnExit(c: Context): Boolean = p(c).getBoolean("resume_on_exit", true)
    fun setResumeOnExit(c: Context, v: Boolean) = p(c).edit().putBoolean("resume_on_exit", v).apply()
    fun podcastLoadCount(c: Context): Int = p(c).getInt("podcast_load_count", 50)
    fun setPodcastLoadCount(c: Context, v: Int) = p(c).edit().putInt("podcast_load_count", v.coerceIn(10, 500)).apply()
    fun podcastStay(c: Context): Boolean = p(c).getBoolean("podcast_stay", true)
    fun setPodcastStay(c: Context, v: Boolean) = p(c).edit().putBoolean("podcast_stay", v).apply()
    fun showArtist(c: Context): Boolean = p(c).getBoolean("show_artist", true)
    fun setShowArtist(c: Context, v: Boolean) = p(c).edit().putBoolean("show_artist", v).apply()
    fun musicStay(c: Context): Boolean = p(c).getBoolean("music_stay", false)
    fun setMusicStay(c: Context, v: Boolean) = p(c).edit().putBoolean("music_stay", v).apply()
    fun undoSkips(c: Context): Boolean = p(c).getBoolean("undo_skips", true)
    fun setUndoSkips(c: Context, v: Boolean) = p(c).edit().putBoolean("undo_skips", v).apply()

    fun autoStart(c: Context): Boolean = p(c).getBoolean("auto_start", true)
    fun setAutoStart(c: Context, v: Boolean) = p(c).edit().putBoolean("auto_start", v).apply()

    fun returnAfterLaunch(c: Context): Boolean = p(c).getBoolean("return_home", false)
    fun setReturnAfterLaunch(c: Context, v: Boolean) = p(c).edit().putBoolean("return_home", v).apply()
}

/** Logs every active media session (the one Android considers "on top" is first). */
fun logActiveSessions(context: Context) {
    try {
        if (!MediaListenerService.isListenerEnabled(context)) {
            AppLogger.log("MediaMenu", "FAIL: Notification access is not granted, cannot list sessions")
            return
        }
        val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val list = msm.getActiveSessions(MediaListenerService.getComponentName(context))
        if (list.isEmpty()) AppLogger.log("MediaMenu", "Active sessions: none")
        list.forEachIndexed { index, c ->
            val st = c.playbackState
            AppLogger.log(
                "MediaMenu",
                "Active session #${index + 1}${if (index == 0) " (TOP)" else ""}: ${c.packageName} state=${st?.state} " +
                    "title=${c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)}"
            )
        }
    } catch (e: Exception) {
        AppLogger.logError("MediaMenu", "logActiveSessions", e)
    }
}

class MediaMenuService : Service() {

    companion object {
        private const val CHANNEL_ID = "media_menu"
        private const val QUIET_CHANNEL_ID = "watch_menu_quiet"
        private const val NOTIF_ID = 2001
        private const val ACTION_CONNECT_WATCH = "com.abdalrahman.watchmenulab.CONNECT_WATCH"
        private const val EXTRA_WATCH_ADDRESS = "watch_address"
        private const val EXTRA_WATCH_NAME = "watch_name"

        @Volatile
        var instance: MediaMenuService? = null
            private set
        fun start(context: Context) {
            if (instance != null || MediaMenuState.running.value) return
            ContextCompat.startForegroundService(context, Intent(context, MediaMenuService::class.java))
        }

        /** Start the foreground bridge first, then connect from onStartCommand after onCreate attached the BLE router. */
        fun connectWatch(context: Context, address: String, name: String) {
            val intent = Intent(context, MediaMenuService::class.java).apply {
                action = ACTION_CONNECT_WATCH
                putExtra(EXTRA_WATCH_ADDRESS, address)
                putExtra(EXTRA_WATCH_NAME, name)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MediaMenuService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var session: MediaSessionCompat

    // Menu position
    private var g = 0
    private var i = 0
    private var tapCount = 0
    private var tapRunnable: Runnable? = null
    private var feedbackRunnable: Runnable? = null
    private var swallowUntil = 0L
    private var ignoreTapsUntil = 0L
    private var lastNavAt = 0L
    private var lastNavToken = ""
    private var ignoreMetaUntil = 0L
    private var wasPlayingBeforeMenu = false
    private var lastAppliedKey = ""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Passcode detection
    private val navHistory = ArrayDeque<Pair<Int, Int>>()
    private var lastEventToken = ""
    private var lastEventAt = 0L
    private var lastEventSource = ""
    private var lastSkipAt = 0L
    private var lastBleToken = ""
    private var lastBleAt = 0L

    // Observing the real music app
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private val registered = mutableMapOf<MediaController, MediaController.Callback>()
    private var targetController: MediaController? = null
    /** Ignore short empty active-session lists while Android hands playback between session owners. */
    private val targetSessionGrace = MediaSessionGracePeriod()
    /** Preserve the last known playing icon through brief null/buffering state reports. */
    private val watchPlaybackStability = MediaSessionPlaybackStability()
    private var lastMissingSessionLogAt = 0L
    private var lastObservedState = -1
    private var lastPos = 0L
    private var lastTitle: String? = null
    private var lastArtist: String? = null
    private val titleHistory = ArrayDeque<String>()

    // Podcast completion watcher. It only prompts when the active RSS episode reaches its end.
    private var podcastObservedItemId: String? = null
    private var podcastObservedIndex = -1
    private var podcastObservedTitle: String? = null
    private var podcastNearEndSeen = false
    private var podcastPromptedKey: String? = null
    private val podcastMonitor = object : Runnable {
        override fun run() {
            try {
                monitorPodcastCompletion()
                monitorGroupQueue()
                dailyPodcastRefresh()
            } catch (e: Exception) {
                AppLogger.logError("MediaMenu", "playback monitor", e)
            }
            if (MediaMenuState.running.value) handler.postDelayed(this, 1200)
        }
    }

    // Silent audio + focus
    @Volatile private var audioRunning = false
    private var audioThread: Thread? = null
    private var focusRequest: AudioFocusRequestCompat? = null

    // ---------------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        MenuStore.init(applicationContext)
        NotificationForwarder.initialize(applicationContext)
        // Calls were removed from the watch, so the phone-state listener (READ_PHONE_STATE) is no longer started.
        createChannel()

        session = MediaSessionCompat(this, "WatchMenu").apply {
            @Suppress("DEPRECATION")
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(callback)
        }
        setMeta("Watch Menu", "idle")
        setState(PlaybackStateCompat.STATE_NONE)

        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        )

        MediaMenuState.running.value = true
        MediaMenuState.menuMode.value = false
        AppLogger.log("MediaMenu", "Service started. Session created. Menu mode OFF.")
        ToggleState.init(applicationContext)
        scope.launch {
            WatchLinkState.connected.collect { handler.post { applySessionState(true) } }
        }
        WatchLink.attach(
            applicationContext,
            tokenSink = { t -> handler.post { onBleToken(t) } },
            connectionSink = { connected -> handler.post { onBleConnectionChanged(connected) } }
        )
        WatchApp.start(applicationContext)
        attachObservers()
        handler.postDelayed(podcastMonitor, 1200)
        applySessionState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CONNECT_WATCH) {
            val address = intent.getStringExtra(EXTRA_WATCH_ADDRESS).orEmpty()
            val name = intent.getStringExtra(EXTRA_WATCH_NAME).orEmpty()
            if (address.isNotBlank()) WatchLink.connect(address, name)
            else AppLogger.log("MediaMenu", "CONNECT_WATCH ignored: empty BLE address")
        } else {
            WatchLink.connectSaved()
        }
        return START_STICKY
    }


    override fun onDestroy() {
        handler.removeCallbacks(podcastMonitor)
        try {
            CallsManager.stopListening()
            WatchLink.shutdown()
            detachObservers()
            stopSilentAudio()
            abandonFocus()
            session.isActive = false
            session.release()
        } catch (e: Exception) {
            AppLogger.logError("MediaMenu", "onDestroy", e)
        }
        instance = null
        scope.cancel()
        MediaMenuState.running.value = false
        MediaMenuState.menuMode.value = false
        AppLogger.log("MediaMenu", "Service stopped.")
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------------
    // Public controls (called from the UI on the main thread)
    // ---------------------------------------------------------------------------------------------

    /** Disables only Watch Menu's music controls/queue; it does not stop the user's external player. */
    fun disableMusicFeature() {
        handler.post {
            queueActive = false
            queueGroupId = null
            queueAdvancing = false
            queueCurrentConfirmed = false
            queueNearEndSeen = false
            liveMusicId = null
            setMenuMode(false)
            applySessionState(force = true)
        }
    }

    fun setCustomText(group: String, item: String) {
        setMeta(item, group)
        AppLogger.log("MediaMenu", "Watch text set manually: artist='$group' title='$item'")
    }

    fun reapplySettings() {
        detachObservers()
        attachObservers()
        applySessionState()
        AppLogger.log("MediaMenu", "Settings re-applied (target=${MediaMenuConfig.targetPackage(this)}).")
    }

    // ---------------------------------------------------------------------------------------------
    // Our own media session callbacks = the watch buttons
    // ---------------------------------------------------------------------------------------------

    /** Package name of whoever is sending the command being delivered right now. */
    private fun senderPackage(): String = try {
        session.currentControllerInfo.packageName ?: ""
    } catch (e: Exception) {
        ""
    }

    private val callback = object : MediaSessionCompat.Callback() {
        override fun onPlay() = onCommand("PLAY", senderPackage(), null)
        override fun onPause() = onCommand("PAUSE", senderPackage(), null)
        override fun onSkipToNext() = onCommand("NEXT", senderPackage(), null)
        override fun onSkipToPrevious() = onCommand("PREV", senderPackage(), null)
        override fun onStop() = onCommand("STOP", senderPackage(), null)
        override fun onSeekTo(pos: Long) {
            AppLogger.log("MediaMenu", "COMMAND onSeekTo($pos) from ${senderPackage()}")
        }
        override fun onFastForward() {
            AppLogger.log("MediaMenu", "COMMAND onFastForward from ${senderPackage()}")
        }
        override fun onRewind() {
            AppLogger.log("MediaMenu", "COMMAND onRewind from ${senderPackage()}")
        }
        override fun onCustomAction(action: String?, extras: Bundle?) {
            AppLogger.log("MediaMenu", "COMMAND onCustomAction($action) from ${senderPackage()}")
        }
        override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
            val ke = mediaButtonEvent?.let {
                IntentCompat.getParcelableExtra(it, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            } ?: return super.onMediaButtonEvent(mediaButtonEvent)
            val pkg = senderPackage()
            AppLogger.log(
                "MediaMenu",
                "MEDIA BUTTON EVENT: ${KeyEvent.keyCodeToString(ke.keyCode)} action=${ke.action} " +
                    "device=${ke.deviceId} source=0x${Integer.toHexString(ke.source)} from=$pkg"
            )
            val token = when (ke.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY -> "PLAY"
                KeyEvent.KEYCODE_MEDIA_PAUSE -> "PAUSE"
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK ->
                    if (MediaMenuState.menuMode.value) "PAUSE"
                    else if (targetController?.playbackState?.state == PlaybackState.STATE_PLAYING) "PAUSE" else "PLAY"
                KeyEvent.KEYCODE_MEDIA_NEXT -> "NEXT"
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "PREV"
                KeyEvent.KEYCODE_MEDIA_STOP -> "STOP"
                else -> return super.onMediaButtonEvent(mediaButtonEvent)
            }
            if (ke.action == KeyEvent.ACTION_DOWN && ke.repeatCount == 0) onCommand(token, pkg, ke.deviceId)
            return true
        }
    }

    private fun noteSender(pkg: String, deviceId: Int?) {
        val label = "${pkg.ifEmpty { "(unknown)" }}" + (if (deviceId != null) " dev=$deviceId" else "")
        MediaMenuState.senders.value = (listOf(label) + MediaMenuState.senders.value).distinct().take(8)
    }

    /** Entry point for every command that reaches our session. Only the watch is allowed through. */
    private fun onCommand(token: String, pkg: String, deviceId: Int?) {
        if (!WatchFeatureSwitches.isEnabled(this, WatchFeature.MUSIC)) {
            AppLogger.log("MediaMenu", "Music feature disabled; ignoring media command")
            return
        }
        noteSender(pkg, deviceId)

        if (MediaMenuState.learning.value) {
            MediaMenuConfig.setWatchPkg(this, pkg)
            MediaMenuConfig.setWatchDevice(this, deviceId ?: -999)
            MediaMenuState.learning.value = false
            AppLogger.log("MediaMenu", "LEARNED WATCH: sender=$pkg device=$deviceId (from command $token)")
        }

        val wantedPkg = MediaMenuConfig.watchPkg(this)
        val wantedDev = MediaMenuConfig.watchDevice(this)
        val pkgOk = wantedPkg.isBlank() || pkg == wantedPkg
        val devOk = wantedDev == -999 || deviceId == null || deviceId == wantedDev
        if (WatchLink.connectedNow()) {
            val now = SystemClock.elapsedRealtime()
            // While menu mode is active, consume every media-key event at our active proxy
            // session so no Bluetooth Classic duplicate reaches the real player. Outside the
            // menu, suppress only a same-token Classic event that duplicates a direct BLE event;
            // unrelated headphones/keyboard controls can still pass through the proxy.
            val duplicateOfBle = token == lastBleToken && now - lastBleAt in 0..700
            if (MediaMenuState.menuMode.value || duplicateOfBle) {
                AppLogger.log("MediaMenu", "SUPPRESSED OS media command $token from $pkg (menu=${MediaMenuState.menuMode.value}, directBleDuplicate=$duplicateOfBle)")
                return
            }
        }
        if (!(pkgOk && devOk)) {
            AppLogger.log("MediaMenu", "IGNORED $token from $pkg dev=$deviceId (not the configured watch)")
            if (!MediaMenuState.menuMode.value) forwardToTarget(token)
            return
        }
        onWatchKey(token)
    }

    /** Keep our proxy session in front while the direct BLE route is active. This captures any
     * parallel classic media-key event so it cannot double-trigger the real player. */
    private fun onBleConnectionChanged(connected: Boolean) {
        AppLogger.log("MediaMenu", "Direct watch BLE ${if (connected) "connected" else "disconnected"}; refreshing media-session routing")
        applySessionState(force = true)
    }

    /** A button press that arrived over the direct Bluetooth link to the watch. */
    private fun onBleToken(t: String) {
        if (!WatchFeatureSwitches.isEnabled(this, WatchFeature.MUSIC)) {
            AppLogger.log("MediaMenu", "Music feature disabled; ignoring direct BLE media key")
            return
        }
        val token = if (t == "TOGGLE") {
            if (MediaMenuState.menuMode.value) "PAUSE"
            else if (targetController?.playbackState?.state == PlaybackState.STATE_PLAYING) "PAUSE" else "PLAY"
        } else t
        noteSender("watch (direct)", null)
        lastBleToken = token
        lastBleAt = SystemClock.elapsedRealtime()
        onWatchKey(token)
    }

    private fun onWatchKey(token: String) {
        val wasMenu = MediaMenuState.menuMode.value
        if (wasMenu) bumpIdle()
        AppLogger.log("MediaMenu", "WATCH BUTTON -> $token (menuMode=$wasMenu)")
        recordEvent(token, "own")

        // The last press of a passcode must not also act as a normal press.
        if (SystemClock.elapsedRealtime() < swallowUntil) return

        if (!MediaMenuState.menuMode.value) {
            // Standby: pass the press on to the real music app, but wait a moment first if it could
            // be the start of the open-menu passcode, so a passcode never skips or pauses the song.
            holdOrForward(token)
            return
        }

        if (token == "VOLUME_UP" || token == "VOLUME_DOWN") {
            val n = MenuStore.groups.value.size
            if (MediaMenuConfig.crownGroups(this) && n > 1) {
                // Crown up = toward the first group (top of the bar), down = toward the last.
                // The phone volume is not touched.
                val ng = (g + if (token == "VOLUME_UP") -1 else 1).coerceIn(0, n - 1)
                if (ng != g) {
                    g = ng
                    i = 0
                    persistCurrentSelection()
                    refreshMenuFast()
                }
            } else {
                forwardToTarget(token)
            }
            return
        }
        menuHoldOrRun(token)

        // Keep reporting "playing" so every tap reaches us as the same kind of command,
        // unless the user wants us to mirror the toggles.
        if (MediaMenuConfig.mirrorState(this)) {
            if (token == "PLAY") setState(PlaybackStateCompat.STATE_PLAYING)
            if (token == "PAUSE") setState(PlaybackStateCompat.STATE_PAUSED)
        } else {
            setState(PlaybackStateCompat.STATE_PLAYING)
        }
    }

    // Skipping an episode asks first: the first press shows the name on the watch, a later press confirms.
    private var skipPressAt = 0L
    private var skipPendingAt = 0L
    private var skipPendingKey = ""
    private var switchingUntil = 0L

    private fun podcastSkipPress(token: String, item: MenuItem) {
        val now = SystemClock.elapsedRealtime()
        if (now - skipPressAt < 1000L) { skipPressAt = now; return }   // quick double press: ignore the extra one
        skipPressAt = now
        val queue = PodcastFeed.decodeEpisodes(item.podcastEpisodes)
        val target = item.podcastIndex + if (token == "NEXT") 1 else -1
        val episode = queue.getOrNull(target) ?: run { AppLogger.log("MediaMenu", "No episode in that direction"); return }
        val key = "${item.id}:$target"
        if (key == skipPendingKey && now - skipPendingAt < 20_000L) {
            skipPendingKey = ""
            switchingUntil = now + 20_000L
            scope.launch {
                PodcastPlayback.playEpisode(applicationContext, item.id, target)
                refreshMenu()
            }
        } else {
            skipPendingKey = key
            skipPendingAt = now
            NotificationForwarder.send(NotificationForwarder.payload(0xFF, System.currentTimeMillis() / 1000, if (token == "NEXT") "Next episode?" else "Previous episode?", episode.title))
        }
    }

    /** Counts a burst of taps; the burst ends after the configured quiet gap. */
    private fun onTap() {
        if (SystemClock.elapsedRealtime() < ignoreTapsUntil) {
            AppLogger.log("MediaMenu", "Ignored a play/pause right after the menu changed state")
            return
        }
        tapCount++
        tapRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            tapRunnable = null
            val n = tapCount
            tapCount = 0
            when {
                n == 1 -> confirm()
                else -> AppLogger.log("MediaMenu", "Ignored $n taps (1 tap = run)")
            }
        }
        tapRunnable = r
        handler.postDelayed(r, MediaMenuConfig.tapMs(this).toLong())
    }

    private val held = ArrayList<String>()
    private var holdRunnable: Runnable? = null
    private val HOLD_MS = 600L

    private fun normTok(t: String) = if (t == "PLAY" || t == "PAUSE") "TAP" else t

    private fun cancelHold() {
        holdRunnable?.let { handler.removeCallbacks(it) }
        holdRunnable = null
        held.clear()
    }

    private fun armHold() {
        holdRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable { flushHeld() }
        holdRunnable = r
        handler.postDelayed(r, HOLD_MS)
    }

    private fun flushHeld() {
        holdRunnable?.let { handler.removeCallbacks(it) }
        holdRunnable = null
        if (held.isEmpty()) return
        val tokens = ArrayList(held)
        held.clear()
        if (MediaMenuState.menuMode.value) {
            for (t in tokens) menuRun(t)
            return
        }
        if (tokens.size == 1) {
            forwardToTarget(tokens[0])
            return
        }
        var playing = targetController?.playbackState?.state == PlaybackState.STATE_PLAYING
        for (t in tokens) {
            if (t == "PLAY" || t == "PAUSE") {
                forwardToTarget(if (playing) "PAUSE" else "PLAY")
                playing = !playing
            } else forwardToTarget(t)
        }
    }

    /** In the menu, a press that could start the exit or group-switch passcode waits a moment, like outside the menu. */
    private fun menuSeqs(): List<List<String>> {
        val list = ArrayList<List<String>>()
        // Keep the app's physical media-button sequence handling; sensor-based navigation is not used.
        list += (if (MediaMenuConfig.samePasscode(this)) MediaMenuConfig.enterSeq(this) else MediaMenuConfig.exitSeq(this)).map { normTok(it) }
        list += MediaMenuConfig.switchSeq(this).map { normTok(it) }
        if (!MediaMenuConfig.sameGroupCode(this)) list += MediaMenuConfig.prevSeq(this).map { normTok(it) }
        return list.filter { it.size >= 2 }
    }

    private fun menuHoldOrRun(token: String) {
        if (token == "NEXT" || token == "PREV" || token == "PLAY" || token == "PAUSE") {
            val seqs = menuSeqs()
            val cand = held.map { normTok(it) } + normTok(token)
            if (seqs.any { cand.size < it.size && cand == it.take(cand.size) }) {
                held.add(token); armHold(); return
            }
            flushHeld()
            if (seqs.any { normTok(token) == it[0] }) {
                held.add(token); armHold(); return
            }
        }
        menuRun(token)
    }

    private fun menuRun(menuToken: String) {
        when (menuToken) {
            "NEXT", "PREV" -> {
                navHistory.addLast(g to i)
                while (navHistory.size > 12) navHistory.removeFirst()
                lastNavAt = SystemClock.elapsedRealtime()
                lastNavToken = menuToken
                cancelPendingTap()
                val group = MenuStore.groups.value.getOrNull(g)
                val items = group?.items.orEmpty()
                val liveItem = items.getOrNull(i)
                if (group?.type == GroupType.PODCAST && liveItem != null && livePodcastId == liveItem.id) {
                    podcastSkipPress(menuToken, liveItem)
                } else if (items.isNotEmpty()) {
                    val nextIndex = if (menuToken == "NEXT") (i + 1) % items.size else (i - 1 + items.size) % items.size
                    if (group?.type == GroupType.MUSIC && liveMusicId != null) {
                        playSavedMusicAt(nextIndex)
                    } else {
                        i = nextIndex
                        persistCurrentSelection()
                        refreshMenu()
                    }
                }
            }
            "PLAY", "PAUSE" -> onTap()
        }
    }

    /** The legacy menu passcode no longer exists; forward watch media keys directly. */
    private fun holdOrForward(token: String) {
        flushHeld()
        forwardToTarget(token)
    }

    private fun cancelPendingTap() {
        tapRunnable?.let { handler.removeCallbacks(it) }
        tapRunnable = null
        tapCount = 0
    }

    private fun clampPosition() {
        val list = MenuStore.groups.value
        g = g.coerceIn(0, maxOf(0, list.size - 1))
        val group = list.getOrNull(g)
        val n = group?.items?.size ?: 0
        i = i.coerceIn(0, maxOf(0, n - 1))
    }

    private fun persistCurrentSelection() {
        val group = MenuStore.groups.value.getOrNull(g) ?: return
        val item = group.items.getOrNull(i)
        MenuStore.saveSelection(this, group.id, item?.id.orEmpty())
    }

    private fun nextGroup() {
        cancelMusicNav()
        val list = MenuStore.groups.value
        if (list.isEmpty()) return
        g = (g + 1) % list.size
        i = 0
        liveMusicId = null
        persistCurrentSelection()
        AppLogger.log("MediaMenu", "SWITCH GROUP -> ${list[g].name}")
        refreshMenu()
    }

    private fun prevGroup() {
        cancelMusicNav()
        val list = MenuStore.groups.value
        if (list.isEmpty()) return
        g = (g - 1 + list.size) % list.size
        i = 0
        liveMusicId = null
        persistCurrentSelection()
        AppLogger.log("MediaMenu", "PREVIOUS GROUP -> ${list[g].name}")
        refreshMenu()
    }

    // Ordered music-group playback. A manual change to content outside this group releases
    // the queue; it does not pause or interrupt the user's manually selected content.
    private var queueActive = false
    private var queueGroupId: String? = null
    private var queueItemIndex = 0
    private var queueWaitingSince = 0L
    private var queueCurrentConfirmed = false
    private var queueNearEndSeen = false
    private var queueAdvancing = false
    private var queuePackage: String? = null           // the player app that is playing the queue
    private var queueTitle: String? = null            // the track the queue is playing now
    private var queueBefore: String? = null            // the track that played before we started the item
    private var queueLastRemainingMs = Long.MAX_VALUE  // time left in the track, last time we saw it playing

    private fun queueTitleMatches(title: String?, item: MenuItem): Boolean {
        val actual = title?.let { norm(it) }.orEmpty()
        if (actual.isBlank()) return false
        val expected = norm(item.name.substringBeforeLast(" - ", item.name))
        return expected.isNotBlank() && (actual == expected || actual.contains(expected) || expected.contains(actual))
    }

    private fun monitorGroupQueue() {
        if (!WatchFeatureSwitches.isEnabled(applicationContext, WatchFeature.MUSIC)) {
            queueActive = false
            queueGroupId = null
            queueAdvancing = false
            return
        }
        if (!queueActive) return
        val controller = targetController ?: refreshTargetFromSystem() ?: return
        checkGroupQueueAdvance(controller.metadata?.let { metadataTitle(it) }, controller.playbackState)
    }

    private fun checkGroupQueueAdvance(title: String?, state: PlaybackState?) {
        if (!queueActive || queueAdvancing) return
        val group = queueGroupId?.let { MenuStore.group(it) }
        if (group == null || group.type != GroupType.MUSIC || !group.stayInGroup || group.items.isEmpty() || queueItemIndex !in group.items.indices) {
            queueActive = false
            queueGroupId = null
            return
        }
        val expected = group.items[queueItemIndex]
        if (!queueCurrentConfirmed) {
            // The first new title after we opened the item is our track, even if its name differs from the saved one.
            val fresh = !title.isNullOrBlank() &&
                (queueTitleMatches(title, expected) || norm(title) != norm(queueBefore.orEmpty()))
            if (fresh) {
                queueCurrentConfirmed = true
                queueTitle = title
                queuePackage = targetController?.packageName
                queueLastRemainingMs = Long.MAX_VALUE
            } else if (SystemClock.elapsedRealtime() - queueWaitingSince > 25_000L) {
                AppLogger.log("GroupQueue", "Could not confirm '${expected.name}' in the player; queue released")
                queueActive = false
                queueGroupId = null
            }
            return
        }
        val other = targetController
        if (queuePackage != null && other != null && other.packageName != queuePackage &&
            other.playbackState?.state == PlaybackState.STATE_PLAYING) {
            AppLogger.log("GroupQueue", "Another app (${other.packageName}) is playing; queue released")
            queueActive = false
            queueGroupId = null
            return
        }
        val current = queueTitle
        if (!title.isNullOrBlank() && current != null && norm(title) != norm(current)) {
            if (queueLastRemainingMs <= 3500L) {
                AppLogger.log("GroupQueue", "Track ended; player moved to '$title'. Playing the next one in the group")
                advanceGroupQueue(group)
            } else {
                // You picked a song yourself. If it is one of this group's songs, carry on from there.
                val pickedIdx = group.items.indexOfFirst { queueTitleMatches(title, it) }
                if (pickedIdx >= 0) {
                    AppLogger.log("GroupQueue", "You picked '${group.items[pickedIdx].name}' from the group; queue continues from it")
                    queueItemIndex = pickedIdx
                    queueTitle = title
                    queueLastRemainingMs = Long.MAX_VALUE
                } else {
                    AppLogger.log("GroupQueue", "Manual track change ('$title'); queue released without pausing")
                    queueActive = false
                    queueGroupId = null
                }
            }
            return
        }
        if (state == null) return
        val duration = targetController?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (duration <= 0L) return
        val rawPosition = state.position.coerceAtLeast(0L)
        val position = if (state.state == PlaybackState.STATE_PLAYING) {
            rawPosition + ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime).coerceAtLeast(0L) * state.playbackSpeed).toLong()
        } else rawPosition
        if (state.state == PlaybackState.STATE_PLAYING) queueLastRemainingMs = (duration - position).coerceAtLeast(0L)
        val stopped = state.state == PlaybackState.STATE_PAUSED || state.state == PlaybackState.STATE_STOPPED || state.state == PlaybackState.STATE_NONE
        if (stopped && queueLastRemainingMs <= 3500L && duration - position <= 3500L) advanceGroupQueue(group)
    }

    private fun advanceGroupQueue(group: MenuGroup) {
        if (!WatchFeatureSwitches.isEnabled(applicationContext, WatchFeature.MUSIC)) {
            queueActive = false
            queueGroupId = null
            queueAdvancing = false
            return
        }
        if (queueAdvancing || !queueActive) return
        val nextIndex = when {
            queueItemIndex + 1 < group.items.size -> queueItemIndex + 1
            group.repeatGroup && group.items.isNotEmpty() -> 0
            else -> -1
        }
        if (nextIndex < 0) {
            AppLogger.log("GroupQueue", "End of ${group.name}; queue stopped")
            queueActive = false
            queueGroupId = null
            return
        }
        playQueueIndex(group, nextIndex)
    }

    /** Next / previous press while a group queue is running: play the neighbouring song of the group. */
    private fun queueStepIfActive(token: String): Boolean {
        if (!queueActive) return false
        // A track change is in progress (the split second between two songs): swallow the press.
        if (queueAdvancing || !queueCurrentConfirmed) return SystemClock.elapsedRealtime() - queueWaitingSince < 6000L
        val group = queueGroupId?.let { MenuStore.group(it) } ?: return false
        if (group.type != GroupType.MUSIC || !group.stayInGroup || queueItemIndex !in group.items.indices) return false
        val c = targetController ?: return false
        if (c.packageName != queuePackage) return false
        val title = metadataTitle(c.metadata)
        val current = queueTitle
        if (title == null || current == null || norm(title) != norm(current)) return false
        val n = group.items.size
        val want = queueItemIndex + if (token == "NEXT") 1 else -1
        val idx = when {
            want in 0 until n -> want
            group.repeatGroup -> (want + n) % n
            else -> return false
        }
        if (!WatchFeatureSwitches.isEnabled(applicationContext, WatchFeature.MUSIC)) return false
        AppLogger.log("GroupQueue", "$token pressed: playing ${group.items[idx].name}")
        queueAdvancing = false
        playQueueIndex(group, idx)
        return true
    }

    private fun playQueueIndex(group: MenuGroup, nextIndex: Int) {
        queueItemIndex = nextIndex
        val nextItem = group.items[nextIndex]
        queueBefore = queueTitle ?: targetController?.metadata?.let { metadataTitle(it) }
        queueTitle = null
        queueLastRemainingMs = Long.MAX_VALUE
        queueCurrentConfirmed = false
        queueNearEndSeen = false
        queueWaitingSince = SystemClock.elapsedRealtime()
        queueAdvancing = true
        AppLogger.log("GroupQueue", "Advancing to ${group.name}: ${nextItem.name}")
        scope.launch {
            val result = ActionRunner.run(applicationContext, group, nextItem)
            handler.post {
                queueAdvancing = false
                if (!result.startsWith("SUCCESS")) {
                    AppLogger.log("GroupQueue", "Next item failed: $result")
                    queueActive = false
                    queueGroupId = null
                } else {
                    handler.postDelayed({
                        refreshTargetFromSystem()
                        checkGroupQueueAdvance(targetController?.metadata?.let { metadataTitle(it) }, targetController?.playbackState)
                    }, 3500L)
                }
            }
        }
    }

    private fun onLiveItem(): Boolean {
        if (!MediaMenuState.menuMode.value) return false
        val grp = MenuStore.groups.value.getOrNull(g) ?: return false
        val itemId = grp.items.getOrNull(i)?.id ?: return false
        return (grp.type == GroupType.PODCAST && livePodcastId == itemId) ||
            (grp.type == GroupType.MUSIC && liveMusicId == itemId)
    }

    private fun onLiveMusicItem(): Boolean {
        if (!MediaMenuState.menuMode.value || liveMusicId == null) return false
        val grp = MenuStore.groups.value.getOrNull(g) ?: return false
        return grp.type == GroupType.MUSIC && grp.items.getOrNull(i)?.id == liveMusicId
    }

    private fun confirm() {
        cancelMusicNav()
        if (onLiveItem()) {
            val group = MenuStore.groups.value.getOrNull(g)
            val item = group?.items?.getOrNull(i)
            if (group?.type == GroupType.PODCAST && item != null) {
                // The phone may currently be playing a song from another group. In that case
                // targetController belongs to the song, so toggling it would pause/resume the
                // wrong player. Re-open the saved podcast episode and restore its saved position.
                val pc = findPodcastController(item)
                pauseOthers(item)
                liveMusicId = null
                if (pc != null) {
                    // The podcast has its own session: a tap only pauses or resumes it.
                    try {
                        if (pc.playbackState?.state == PlaybackState.STATE_PLAYING) pc.transportControls.pause()
                        else pc.transportControls.play()
                    } catch (e: Exception) {
                        AppLogger.logError("MediaMenu", "podcast play/pause", e)
                    }
                    handler.postDelayed({ if (onLiveItem()) refreshMenu() }, 400)
                } else {
                    // A song took over the player: stop it, reopen the episode, jump to the saved spot.
                    resumeLivePodcast(group, item)
                }
            } else {
                try {
                    val t = refreshTargetFromSystem() ?: targetController
                    if (t?.playbackState?.state == PlaybackState.STATE_PLAYING) t.transportControls.pause()
                    else t?.transportControls?.play()
                    handler.postDelayed({ if (onLiveItem()) refreshMenu() }, 400)
                } catch (e: Exception) {
                    AppLogger.logError("MediaMenu", "live item play/pause", e)
                }
            }
            return
        }
        val list = MenuStore.groups.value
        clampPosition()
        val group = list.getOrNull(g) ?: return
        val item = group.items.getOrNull(i) ?: return
        persistCurrentSelection()
        AppLogger.log("MediaMenu", "RUN: ${group.name} / ${item.name}")
        if (group.type == GroupType.MUSIC && group.stayInGroup) {
            queueActive = true
            queueGroupId = group.id
            queueItemIndex = i
            queueBefore = targetController?.metadata?.let { metadataTitle(it) }
            queueTitle = null
            queueLastRemainingMs = Long.MAX_VALUE
            queueWaitingSince = SystemClock.elapsedRealtime()
            queueCurrentConfirmed = false
            queueNearEndSeen = false
            queueAdvancing = false
        } else {
            queueActive = false
            queueGroupId = null
            queueAdvancing = false
        }
        val titleBefore = try { targetController?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) } catch (e: Exception) { null }
        val shownName = ToggleState.watchLabel(this, group, item)
        val stay = (group.type == GroupType.PODCAST && MediaMenuConfig.podcastStay(this)) ||
            (group.type == GroupType.MUSIC && MediaMenuConfig.musicStay(this))
        if (stay && group.type == GroupType.PODCAST) {
            livePodcastId = item.id
            startLiveTick()
        } else if (stay && group.type == GroupType.MUSIC) {
            liveMusicId = item.id
            startLiveMusicTick()
        } else if ((group.type == GroupType.MUSIC || group.type == GroupType.PODCAST) && MediaMenuState.menuMode.value) {
            // Leave the menu first, so the new song is not paused by the menu guard.
            wasPlayingBeforeMenu = false
            setMenuMode(false)
        }
        // Never let two things play: a song pauses the podcast at its exact spot; a podcast pauses the song.
        val pausedPodcast = if (group.type == GroupType.MUSIC) pausePodcastForSwitch() else false
        if (group.type == GroupType.PODCAST) pauseOthers(item)
        scope.launch {
            if (pausedPodcast) kotlinx.coroutines.delay(300)
            val result = ActionRunner.run(applicationContext, group, item)
            val ok = result.startsWith("SUCCESS")
            if (ok && (group.type == GroupType.MUSIC || group.type == GroupType.PODCAST)) {
                // Make sure the song actually plays after it was opened.
                handler.postDelayed({
                    try {
                        val t = refreshTargetFromSystem()
                        val titleNow = t?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                        // Only start it if the NEW song is loaded; never resume the old one.
                        if (t != null && titleNow != null && titleNow != titleBefore &&
                            t.playbackState?.state != PlaybackState.STATE_PLAYING) {
                            t.transportControls.play()
                            AppLogger.log("MediaMenu", "Started the new song")
                        }
                    } catch (e: Exception) {
                        AppLogger.logError("MediaMenu", "start new song", e)
                    }
                }, 2500)
            }
            if (!ok && group.type == GroupType.MUSIC && liveMusicId == item.id) liveMusicId = null
            AppLogger.log("MediaMenu", "RESULT (${item.name}): $result")
            if (MediaMenuState.menuMode.value) {
                // Our session may have lost the "active" spot to the app we just controlled.
                if (!WatchLink.connectedNow()) session.isActive = true
                if (stay && ok) { refreshMenu(); return@launch }
                val text = if (ok) "Done - $shownName"
                else "Failed - " + result.removePrefix("FAIL:").trim().take(32)
                setMeta(text, group.name)
                feedbackRunnable?.let { handler.removeCallbacks(it) }
                val fr = Runnable { if (MediaMenuState.menuMode.value) refreshMenu() }
                feedbackRunnable = fr
                handler.postDelayed(fr, 2500)
            }
        }
    }


    private fun allSessions(): List<MediaController> = try {
        (getSystemService(Context.MEDIA_SESSION_SERVICE) as android.media.session.MediaSessionManager)
            .getActiveSessions(MediaListenerService.getComponentName(this)).filter { it.packageName != packageName && !MediaMenuConfig.isIgnored(this, it.packageName) }
    } catch (e: Exception) { emptyList() }

    private fun activePodcastItem(): MenuItem? {
        val id = PodcastPlayback.activeItemId(applicationContext) ?: return null
        return MenuStore.groups.value.filter { it.type == GroupType.PODCAST }.flatMap { it.items }.firstOrNull { it.id == id }
    }

    /** The session that really holds this podcast's episode (not just the same app). */
    private fun findPodcastController(item: MenuItem): MediaController? = allSessions().firstOrNull { podcastSessionMatches(item, it) }

    /** Pauses everything that is playing and is not this podcast. */
    private fun pauseOthers(item: MenuItem) {
        allSessions().filter { it.playbackState?.state == PlaybackState.STATE_PLAYING && !podcastSessionMatches(item, it) }
            .forEach { try { it.transportControls.pause() } catch (_: Exception) {} }
    }

    /** Saves the exact spot and pauses the podcast that is playing. Returns true if it paused one. */
    private fun pausePodcastForSwitch(): Boolean {
        val item = activePodcastItem() ?: return false
        val pc = findPodcastController(item) ?: return false
        val st = pc.playbackState ?: return false
        if (st.state != PlaybackState.STATE_PLAYING) return false
        try {
            val dur = pc.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: item.podcastDurationMs
            val pos = st.position + ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime).coerceAtLeast(0L) * st.playbackSpeed).toLong()
            if (dur > 0L) savePodcastProgress(item, pos.coerceAtMost(dur), dur)
            pc.transportControls.pause()
            AppLogger.log("Podcast", "Paused ${item.name} at ${pos / 1000}s for another item")
            return true
        } catch (e: Exception) {
            AppLogger.logError("Podcast", "pause for switch", e)
            return false
        }
    }

    /** Reopen the pinned podcast instead of toggling whichever unrelated song is currently active. */
    private fun resumeLivePodcast(group: MenuGroup, item: MenuItem) {
        val savedPosition = item.podcastPositionMs.coerceAtLeast(0L)
        livePodcastId = item.id
        startLiveTick()
        AppLogger.log("Podcast", "Watch Play pressed for ${item.name}; reopening saved episode at ${savedPosition}ms")
        scope.launch {
            val result = ActionRunner.run(applicationContext, group, item)
            if (!result.startsWith("SUCCESS")) {
                AppLogger.log("Podcast", "Could not reopen ${item.name}: $result")
                return@launch
            }

            // Opening a deep link is asynchronous. Wait for the matching podcast session rather
            // than issuing PLAY to the previous song's MediaSession.
            var matched: MediaController? = null
            for (attempt in 0 until 24) {
                kotlinx.coroutines.delay(350)
                val candidate = refreshTargetFromSystem()
                if (candidate != null && podcastSessionMatches(item, candidate)) {
                    matched = candidate
                    break
                }
            }
            val podcastController = matched
            if (podcastController != null) {
                try {
                    if (savedPosition > 0L) podcastController.transportControls.seekTo(savedPosition)
                    podcastController.transportControls.play()
                    AppLogger.log("Podcast", "Resumed ${item.name} at ${savedPosition}ms")
                } catch (e: Exception) {
                    AppLogger.logError("Podcast", "resume podcast session", e)
                }
            } else {
                AppLogger.log("Podcast", "Episode link opened, but no matching podcast MediaSession appeared; did not send PLAY to the song session")
            }
            if (MediaMenuState.menuMode.value && onLiveItem()) refreshMenu()
        }
    }

    private fun menuSubtitle(group: MenuGroup, item: MenuItem?): String = group.name

    /** The podcast/music item currently being played inside the nested watch menu. */
    private var livePodcastId: String? = null
    private var liveMusicId: String? = null
    private var liveTickOn = false
    private var liveMusicTickOn = false
    private var musicNavRunnable: Runnable? = null

    private fun cancelMusicNav() {
        musicNavRunnable?.let { handler.removeCallbacks(it) }
        musicNavRunnable = null
    }

    private fun hms(ms: Long): String {
        val s = (ms.coerceAtLeast(0) + 999) / 1000
        return "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
    }

    private fun podcastSessionMatches(item: MenuItem, controller: MediaController? = targetController): Boolean {
        val md = controller?.metadata ?: return false
        val title = metadataTitle(md).orEmpty()
        val titleKey = norm(title)
        if (titleKey.isBlank()) return false
        if (titlesMatch(title, item.episode)) return true
        val queue = PodcastFeed.decodeEpisodes(item.podcastEpisodes)
        return queue.any { titlesMatch(title, it.title) }
    }

    /** Podcast display is pinned to its own session. Songs played in another app cannot replace it. */
    private fun liveText(item: MenuItem): Pair<String, String> {
        val c = targetController
        if (SystemClock.elapsedRealtime() < switchingUntil && item.episode.isNotBlank()) {
            // Just switched episode: the player still shows the old one for a moment. Show the new name, no old timer.
            val playerTitle = metadataTitle(c?.metadata)
            if (playerTitle != null && !titlesMatch(playerTitle, item.episode)) return item.episode to "--:--:--"
        }
        if (podcastSessionMatches(item, c)) {
            val md = c?.metadata
            val title = metadataTitle(md) ?: item.episode.ifBlank { item.name }
            val dur = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: item.podcastDurationMs
            val st = c?.playbackState
            var pos = st?.position ?: item.podcastPositionMs
            if (st != null && st.state == PlaybackState.STATE_PLAYING) {
                pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime).coerceAtLeast(0L) * st.playbackSpeed).toLong()
            }
            if (dur > 0L) savePodcastProgress(item, pos.coerceAtMost(dur), dur)
            return title to (if (dur > 0L) hms(dur - pos) else "--:--:--")
        }
        val duration = item.podcastDurationMs
        val remaining = if (duration > 0L) duration - item.podcastPositionMs else 0L
        return (item.episode.ifBlank { item.name }) to (if (duration > 0L) hms(remaining) else "--:--:--")
    }

    private fun savePodcastProgress(item: MenuItem, positionMs: Long, durationMs: Long) {
        val pos = positionMs.coerceAtLeast(0L)
        val dur = durationMs.coerceAtLeast(0L)
        // Persist at a modest cadence rather than writing preferences every second.
        if (item.podcastDurationMs == dur && kotlin.math.abs(item.podcastPositionMs - pos) < 5_000L) return
        MenuStore.update(applicationContext) { groups ->
            groups.map { group ->
                if (group.type != GroupType.PODCAST) group else group.copy(items = group.items.map { saved ->
                    if (saved.id == item.id) saved.copy(podcastPositionMs = pos, podcastDurationMs = dur) else saved
                })
            }
        }
    }

    private fun liveMusicText(item: MenuItem): Pair<String, String> {
        val md = targetController?.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            ?: item.name.substringBeforeLast(" - ", item.name).ifBlank { item.name }
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
            ?: item.name.substringAfterLast(" - ", "").takeIf { it.isNotBlank() }
            ?: ""
        val line = if (MediaMenuConfig.showArtist(this) && artist.isNotBlank()) "$title - $artist" else title
        return line to "Music"
    }

    private fun startLiveMusicTick() {
        if (liveMusicTickOn) return
        liveMusicTickOn = true
        val r = object : Runnable {
            override fun run() {
                val id = liveMusicId
                if (id == null || !MediaMenuState.menuMode.value) { liveMusicTickOn = false; return }
                val group = MenuStore.groups.value.getOrNull(g)
                val item = group?.items?.getOrNull(i)
                if (group?.type == GroupType.MUSIC && item?.id == id) {
                    val (title, artist) = liveMusicText(item)
                    setMeta(title, artist)
                }
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(r, 500)
    }

    private fun playSavedMusicAt(index: Int) {
        val group = MenuStore.groups.value.getOrNull(g) ?: return
        if (group.type != GroupType.MUSIC) return
        val item = group.items.getOrNull(index) ?: return
        i = index
        liveMusicId = item.id
        persistCurrentSelection()
        if (queueActive && queueGroupId == group.id) {
            queueItemIndex = index
            queueBefore = targetController?.metadata?.let { metadataTitle(it) }
            queueTitle = null
            queueLastRemainingMs = Long.MAX_VALUE
            queueWaitingSince = SystemClock.elapsedRealtime()
            queueCurrentConfirmed = false
            queueNearEndSeen = false
        }
        startLiveMusicTick()
        refreshMenu()
        val titleBefore = targetController?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        scope.launch {
            val result = ActionRunner.run(applicationContext, group, item)
            if (result.startsWith("SUCCESS")) {
                handler.postDelayed({
                    try {
                        val controller = refreshTargetFromSystem()
                        val titleNow = controller?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                        if (controller != null && titleNow != null && titleNow != titleBefore &&
                            controller.playbackState?.state != PlaybackState.STATE_PLAYING) {
                            controller.transportControls.play()
                        }
                    } catch (e: Exception) {
                        AppLogger.logError("MediaMenu", "start saved music", e)
                    }
                    if (onLiveMusicItem()) refreshMenu()
                }, 2200)
                AppLogger.log("MediaMenu", "Saved music ${item.name}: $result")
            } else {
                liveMusicId = null
                AppLogger.log("MediaMenu", "Could not play saved music ${item.name}: $result")
                setMeta("Could not open song", group.name)
                handler.postDelayed({ if (MediaMenuState.menuMode.value) refreshMenu() }, 2200)
            }
        }
    }

    private fun startLiveTick() {
        if (liveTickOn) return
        liveTickOn = true
        val r = object : Runnable {
            override fun run() {
                val id = livePodcastId
                if (id == null || !MediaMenuState.menuMode.value) { liveTickOn = false; return }
                val group = MenuStore.groups.value.getOrNull(g)
                val item = group?.items?.getOrNull(i)
                if (group != null && item != null && item.id == id && group.type == GroupType.PODCAST) {
                    val (t, a) = liveText(item)
                    MediaMenuState.title.value = t
                    MediaMenuState.artist.value = a
                    WatchLink.pushMusic(watchState(), t, a)
                }
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(r, 1000)
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun matchPodcast(md: MediaMetadata): MenuItem? {
        val names = listOfNotNull(
            md.getString(MediaMetadata.METADATA_KEY_ARTIST),
            md.getString(MediaMetadata.METADATA_KEY_ALBUM),
            md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        ).map { norm(it) }.filter { it.length >= 4 }
        if (names.isEmpty()) return null
        return MenuStore.groups.value.filter { it.type == GroupType.PODCAST }.flatMap { it.items }.firstOrNull { p ->
            val n = norm(p.name)
            n.length >= 4 && names.any { it.contains(n) || n.contains(it) }
        }
    }

    /** Update the saved episode/index when the player's metadata matches a known RSS item. */
    private fun trackPodcast(force: Boolean = false) {
        try {
            val c = targetController ?: return
            val md = c.metadata ?: return
            val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: return
            val groups = MenuStore.groups.value
            val metadataMatch = matchPodcast(md)
            val activeId = PodcastPlayback.activeItemId(applicationContext)
            val activeItem = groups.asSequence().filter { it.type == GroupType.PODCAST }
                .flatMap { it.items.asSequence() }.firstOrNull { it.id == activeId }
            // While a queue is active, prefer an exact title match anywhere in that queue. This
            // lets us remember a player that advances on its own instead of losing the show when
            // the new episode title no longer matches the previous episode.
            val activeQueueMatch = activeItem?.let { item ->
                val activeQueue = PodcastFeed.decodeEpisodes(item.podcastEpisodes)
                val key = norm(title)
                activeQueue.any { episode ->
                    val candidate = norm(episode.title)
                    candidate.isNotBlank() && (candidate == key || candidate.contains(key) || key.contains(candidate))
                }
            } == true
            val hit = if (activeQueueMatch) activeItem else metadataMatch
                ?: activeItem?.takeIf { norm(title).contains(norm(it.episode)) || norm(it.episode).contains(norm(title)) }
            hit ?: return

            val queue = PodcastFeed.decodeEpisodes(hit.podcastEpisodes)
            val titleKey = norm(title)
            val matchedIndex = queue.indexOfFirst { episode -> titlesMatch(title, episode.title) }
            // Only episodes that are in the loaded list count. Any other song or video is ignored.
            if (matchedIndex < 0) return
            val currentEpisodeUrl = queue.getOrNull(matchedIndex)?.let { it.link.ifBlank { it.audioUrl } }.orEmpty()
            if (!force && hit.episode == title && (matchedIndex < 0 || matchedIndex == hit.podcastIndex)) return

            MenuStore.update(applicationContext) { list ->
                list.map { group ->
                    if (group.type != GroupType.PODCAST) group
                    else group.copy(items = group.items.map { item ->
                        if (item.id != hit.id) item
                        else item.copy(
                            episode = title,
                            podcastIndex = if (matchedIndex >= 0) matchedIndex else item.podcastIndex,
                            podcastCurrentUrl = currentEpisodeUrl.ifBlank { item.podcastCurrentUrl }
                        )
                    })
                }
            }
            AppLogger.log("MediaMenu", "Podcast ${hit.name}: $title${if (matchedIndex >= 0) " (${matchedIndex + 1}/${queue.size})" else ""}")
        } catch (e: Exception) {
            AppLogger.logError("MediaMenu", "trackPodcast", e)
        }
    }

    private var lastDailyCheck = 0L
    private fun dailyPodcastRefresh() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDailyCheck < 10 * 60_000L) return
        lastDailyCheck = now
        val ids = MenuStore.groups.value.filter { it.type == GroupType.PODCAST }.flatMap { it.items }
            .filter { it.podcastFeedUrl.isNotBlank() && PodcastPlayback.dueForRefresh(applicationContext, it.id) }.map { it.id }
        if (ids.isEmpty()) return
        scope.launch { ids.forEach { runCatching { PodcastPlayback.refresh(applicationContext, it) } } }
    }

    /** Strict: same title, or one fully inside the other and almost as long. Random songs never match. */
    private fun titlesMatch(a: String, b: String): Boolean {
        val x = norm(a); val y = norm(b)
        if (x.length < 4 || y.length < 4) return false
        if (x == y) return true
        val shorter = minOf(x.length, y.length)
        if (shorter >= 12 && (x.contains(y) || y.contains(x))) return true
        // Players add or drop words (show name, "Episode 12"). Most of the words must still be shared.
        fun words(t: String) = t.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.toSet()
        val wx = words(a); val wy = words(b)
        val small = if (wx.size <= wy.size) wx else wy
        val big = if (wx.size <= wy.size) wy else wx
        return small.size >= 3 && small.count { it in big } * 10 >= small.size * 7
    }

    private fun monitorPodcastCompletion() {
        val itemId = PodcastPlayback.activeItemId(applicationContext) ?: run {
            podcastObservedItemId = null
            podcastNearEndSeen = false
            return
        }
        val item = MenuStore.groups.value.asSequence().filter { it.type == GroupType.PODCAST }
            .flatMap { it.items.asSequence() }.firstOrNull { it.id == itemId } ?: return
        val episodes = PodcastFeed.decodeEpisodes(item.podcastEpisodes)
        if (episodes.isEmpty() || item.podcastIndex !in episodes.indices || targetController == null) return

        val controller = targetController ?: return
        val state = controller.playbackState ?: return
        val title = controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
        val indexChanged = podcastObservedItemId != itemId || podcastObservedIndex != item.podcastIndex
        if (indexChanged) {
            podcastObservedItemId = itemId
            podcastObservedIndex = item.podcastIndex
            podcastObservedTitle = title
            podcastNearEndSeen = false
            podcastPromptedKey = null
        } else if (title != null && podcastObservedTitle != null && title != podcastObservedTitle) {
            podcastObservedTitle = title
            podcastNearEndSeen = false
        } else if (podcastObservedTitle == null && title != null) {
            podcastObservedTitle = title
        }

        // Make sure this is still the tracked episode, not an unrelated song played afterwards.
        val expected = norm(item.episode)
        val actual = norm(title.orEmpty())
        val metadataBelongs = expected.isNotBlank() && actual.isNotBlank() &&
            (actual.contains(expected) || expected.contains(actual))
        val titleBelongsToQueue = title?.let { currentTitle ->
            val titleKey = norm(currentTitle)
            episodes.any { episode ->
                val episodeKey = norm(episode.title)
                episodeKey.isNotBlank() && (titleKey == episodeKey || titleKey.contains(episodeKey) || episodeKey.contains(titleKey))
            }
        } == true
        val podcastMetadataMatch = controller.metadata?.let { matchPodcast(it)?.id == itemId } == true
        if (!metadataBelongs && !titleBelongsToQueue && !podcastMetadataMatch) {
            // A different song may become the active Android session while the podcast is
            // intentionally paused in the background. Keep the podcast queue and its last
            // known position; do not let unrelated music overwrite or clear it.
            return
        }

        val duration = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val rawPosition = state.position.coerceAtLeast(0L)
        val position = if (state.state == PlaybackState.STATE_PLAYING) {
            rawPosition + ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime).coerceAtLeast(0L) * state.playbackSpeed).toLong()
        } else rawPosition
        if (duration > 0L && state.state == PlaybackState.STATE_PLAYING && position >= duration - maxOf(2500L, duration / 100L)) {
            podcastNearEndSeen = true
        }

        val finished = podcastNearEndSeen && (
            state.state == PlaybackState.STATE_STOPPED || state.state == PlaybackState.STATE_NONE ||
                (state.state == PlaybackState.STATE_PAUSED && duration > 0L && position >= duration - 1200L)
            )
        val promptKey = "$itemId:${item.podcastIndex}"
        if (finished && podcastPromptedKey != promptKey) {
            podcastPromptedKey = promptKey
            podcastNearEndSeen = false
            handler.postDelayed({
                val current = MenuStore.groups.value.asSequence().filter { it.type == GroupType.PODCAST }
                    .flatMap { it.items.asSequence() }.firstOrNull { it.id == itemId }
                val currentState = targetController?.playbackState
                if (current != null && current.podcastIndex == item.podcastIndex && currentState?.state != PlaybackState.STATE_PLAYING) {
                    scope.launch { PodcastPlayback.offerNext(applicationContext, itemId) }
                }
            }, 1500L)
        }
    }

    private var slowSync: Runnable? = null

    /** Leaves the menu after N quiet seconds, if the user turned that on. */
    private var lastActivity = 0L
    private var idleWatch = false

    /** A press counts as activity. A one-second watchdog leaves the menu after N quiet seconds. */
    private fun bumpIdle() {
        lastActivity = SystemClock.elapsedRealtime()
        if (idleWatch || !MediaMenuState.menuMode.value) return
        idleWatch = true
        val r = object : Runnable {
            override fun run() {
                if (!MediaMenuState.menuMode.value) { idleWatch = false; return }
                if (MediaMenuConfig.idleExit(this@MediaMenuService) &&
                    SystemClock.elapsedRealtime() - lastActivity >= MediaMenuConfig.idleSec(this@MediaMenuService) * 1000L
                ) {
                    idleWatch = false
                    AppLogger.log("MediaMenu", "Idle - back to normal")
                    setMenuMode(false)
                    return
                }
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(r, 1000)
    }

    private fun updateBar() {
        val n = MenuStore.groups.value.size
        WatchLink.barFraction =
            if (MediaMenuState.menuMode.value && MediaMenuConfig.crownGroups(this) && n > 1) (n - 1 - g) / (n - 1).toFloat() else null
    }

    /** Quick path for fast scrolling: only the watch text, the heavy updates follow when scrolling stops. */
    private fun refreshMenuFast() {
        val list = MenuStore.groups.value
        if (list.isEmpty()) return
        clampPosition()
        updateBar()
        val group = list[g]
        val item = group.items.getOrNull(i)
        val (t, a) = if (item != null && group.type == GroupType.PODCAST && item.id == livePodcastId) liveText(item)
        else (if (item != null) ToggleState.watchLabel(this, group, item) else "(empty group)") to menuSubtitle(group, item)
        MediaMenuState.title.value = t
        MediaMenuState.artist.value = a
        WatchLink.pushMusic(watchState(), t, a)
        slowSync?.let { handler.removeCallbacks(it) }
        val r = Runnable { if (MediaMenuState.menuMode.value) refreshMenu() }
        slowSync = r
        handler.postDelayed(r, 500)
    }

    private fun refreshMenu() {
        val list = MenuStore.groups.value
        if (list.isEmpty()) {
            setMeta("Add a group in the app", "Watch Menu")
            return
        }
        clampPosition()
        updateBar()
        val group = list[g]
        val item = group.items.getOrNull(i)
        if (item != null && group.type == GroupType.PODCAST && item.id == livePodcastId) {
            val (t, a) = liveText(item)
            setMeta(t, a)
            return
        }
        if (item != null && group.type == GroupType.MUSIC && item.id == liveMusicId) {
            val (t, a) = liveMusicText(item)
            setMeta(t, a)
            return
        }
        setMeta(if (item != null) ToggleState.watchLabel(this, group, item) else "(empty group)", menuSubtitle(group, item))
        AppLogger.log("MediaMenu", "MENU: ${group.name} -> ${item?.name} (${i + 1}/${group.items.size})")
    }

    // ---------------------------------------------------------------------------------------------
    // Passcode
    // ---------------------------------------------------------------------------------------------

    /** Record diagnostic input only. The retired watch Control Centre passcode flow is removed. */
    private fun recordEvent(token: String, source: String) {
        // Only commands that reached our own session count (the watch), never inferred ones.
        if (source == "target") return

        val now = SystemClock.elapsedRealtime()
        // The same press can be reported by both sessions within a moment: count it once.
        if (source != lastEventSource && token == lastEventToken && now - lastEventAt < 300) return
        lastEventToken = token
        lastEventAt = now
        lastEventSource = source

        val shown = listOf("${source.uppercase()}:$token") + MediaMenuState.events.value
        MediaMenuState.events.value = shown.take(12)
        AppLogger.log("MediaMenu", "EVENT $source:$token")
    }

    private fun setMenuMode(on: Boolean) {
        cancelMusicNav()
        cancelPendingTap()
        cancelHold()
        val target = targetController
        livePodcastId = null
        liveMusicId = null
        // The watch can echo a play/pause when the music state changes. Never treat that as "run this item".
        ignoreTapsUntil = SystemClock.elapsedRealtime() + 2500L
        swallowUntil = maxOf(swallowUntil, SystemClock.elapsedRealtime() + 800L)
        if (on) {
            wasPlayingBeforeMenu = target?.playbackState?.state == PlaybackState.STATE_PLAYING
            // Restore the last explicit selection. Do not auto-jump to a podcast just because
            // an older podcast session is still present in the background.
            val groups = MenuStore.groups.value
            val savedGroupId = MenuStore.lastSelectedGroupId(this)
            val savedItemId = MenuStore.lastSelectedItemId(this)
            val savedGroupIndex = groups.indexOfFirst { it.id == savedGroupId }
            if (savedGroupIndex >= 0) {
                g = savedGroupIndex
                val savedItemIndex = groups[g].items.indexOfFirst { it.id == savedItemId }
                i = if (savedItemIndex >= 0) savedItemIndex else 0
            } else {
                g = 0
                i = 0
            }

            val selectedGroup = groups.getOrNull(g)
            val selectedItem = selectedGroup?.items?.getOrNull(i)
            if (selectedGroup?.type == GroupType.PODCAST && selectedItem != null && MediaMenuConfig.podcastStay(this)) {
                val podcastController = findPodcastController(selectedItem)
                if (podcastController?.playbackState?.state == PlaybackState.STATE_PLAYING) {
                    livePodcastId = selectedItem.id
                    startLiveTick()
                }
            } else if (MediaMenuConfig.pauseOnEnter(this) && wasPlayingBeforeMenu && !queueActive) {
                try {
                    target?.transportControls?.pause()
                    AppLogger.log("MediaMenu", "Paused the real music for menu mode")
                } catch (e: Exception) {
                    AppLogger.logError("MediaMenu", "pause on enter", e)
                }
            }
            persistCurrentSelection()
        } else {
            persistCurrentSelection()
        }
        MediaMenuState.menuMode.value = on
        updateBar()
        bumpIdle()
        AppLogger.log("MediaMenu", "MENU MODE ${if (on) "ON" else "OFF"}")
        applySessionState(force = true)
        if (!on && MediaMenuConfig.resumeOnExit(this) && wasPlayingBeforeMenu) {
            handler.postDelayed({
                try {
                    targetController?.transportControls?.play()
                    AppLogger.log("MediaMenu", "Resumed the real music")
                } catch (e: Exception) {
                    AppLogger.logError("MediaMenu", "resume on exit", e)
                }
            }, 500)
            wasPlayingBeforeMenu = false
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Session state: active while in menu mode (or proxy mode), inactive otherwise so the real app
    // is shown on the watch.
    // ---------------------------------------------------------------------------------------------

    private fun applySessionState(force: Boolean = false) {
        val menu = MediaMenuState.menuMode.value
        val standby = MediaMenuConfig.proxy(this) || WatchLink.connectedNow()
        val haveTarget = targetController != null
        // With the direct Bluetooth link the watch text goes straight to the watch, so no media session
        // is needed. Keeping it inactive means Android shows no music card on the phone.
        val ble = WatchLink.connectedNow()
        val key = "$menu|$standby|$haveTarget|$ble"
        if (!force && key == lastAppliedKey) return
        lastAppliedKey = key

        if (menu || (standby && haveTarget)) {
            if (!ble && menu && MediaMenuConfig.audioFocus(this)) requestFocus() else abandonFocus()
            if (!ble && MediaMenuConfig.silentAudio(this)) startSilentAudio() else stopSilentAudio()
            session.isActive = !ble
            if (menu) {
                refreshMenu()
                setState(PlaybackStateCompat.STATE_PLAYING)
            } else {
                mirrorFromTarget()
                mirrorStateFromTarget()
            }
        } else {
            stopSilentAudio()
            abandonFocus()
            setState(PlaybackStateCompat.STATE_STOPPED)
            session.isActive = false
            setMeta("Watch Menu", "idle")
        }
    }

    private fun mirrorStateFromTarget() {
        when (targetController?.playbackState?.state) {
            PlaybackState.STATE_PLAYING -> setState(PlaybackStateCompat.STATE_PLAYING)
            else -> setState(PlaybackStateCompat.STATE_PAUSED)
        }
    }

    private fun setMeta(title: String, artist: String) {
        MediaMenuState.title.value = title
        MediaMenuState.artist.value = artist
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, 180_000L)
                .build()
        )
        WatchLink.pushMusic(watchState(), title, artist)
        updateNotification()
    }

    private var shownState = 1

    /** In the menu the watch always shows the play triangle; outside it follows the real song. */
    private fun watchState(): Int =
        if (MediaMenuState.menuMode.value) {
            if (onLiveItem() && targetController?.playbackState?.state == PlaybackState.STATE_PLAYING) 2 else 1
        } else shownState

    private fun setState(state: Int) {
        shownState = if (state == PlaybackStateCompat.STATE_PLAYING) 2 else 1
        WatchLink.pushMusic(watchState(), MediaMenuState.title.value, MediaMenuState.artist.value)
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_SEEK_TO
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(
                    state, 0L,
                    if (state == PlaybackStateCompat.STATE_PLAYING) 1f else 0f,
                    SystemClock.elapsedRealtime()
                )
                .build()
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Notification (required for a foreground media service)
    // ---------------------------------------------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(QUIET_CHANNEL_ID, "Watch Menu", NotificationManager.IMPORTANCE_MIN)
            )
        }
    }

    /** A plain, quiet notification. It deliberately has no media controls and shows no song,
     * so the phone does not show an always-playing music card. */
    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, QUIET_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Watch Menu")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    private fun updateNotification() {
        try {
            return  // the notification no longer shows the song, so there is nothing to update
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification())
        } catch (e: Exception) {
            AppLogger.logError("MediaMenu", "updateNotification", e)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Silent audio and audio focus (helps our session be treated as the active player)
    // ---------------------------------------------------------------------------------------------

    private fun startSilentAudio() {
        if (audioRunning) return
        audioRunning = true
        audioThread = Thread {
            var track: AudioTrack? = null
            try {
                val rate = 8000
                val minBuf = AudioTrack.getMinBufferSize(
                    rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBuf, 4096))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track.play()
                val silence = ByteArray(4096)
                while (audioRunning) track.write(silence, 0, silence.size)
            } catch (e: Exception) {
                AppLogger.logError("MediaMenu", "silent audio", e)
            } finally {
                try {
                    track?.stop()
                } catch (_: Exception) {
                }
                track?.release()
            }
        }.also { it.start() }
        AppLogger.log("MediaMenu", "Silent audio started")
    }

    private fun stopSilentAudio() {
        if (audioRunning) AppLogger.log("MediaMenu", "Silent audio stopped")
        audioRunning = false
    }

    private fun requestFocus() {
        try {
            if (focusRequest != null) return
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val req = AudioFocusRequestCompat.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributesCompat.Builder()
                        .setUsage(AudioAttributesCompat.USAGE_MEDIA)
                        .setContentType(AudioAttributesCompat.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener { change ->
                    AppLogger.log("MediaMenu", "Audio focus change: $change")
                }
                .build()
            val result = AudioManagerCompat.requestAudioFocus(am, req)
            focusRequest = req
            AppLogger.log("MediaMenu", "Audio focus requested, result=$result")
        } catch (e: Exception) {
            AppLogger.logError("MediaMenu", "requestFocus", e)
        }
    }

    private fun abandonFocus() {
        try {
            val req = focusRequest ?: return
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            AudioManagerCompat.abandonAudioFocusRequest(am, req)
            focusRequest = null
        } catch (e: Exception) {
            AppLogger.logError("MediaMenu", "abandonFocus", e)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Observing the real music app (needs Notification access)
    // ---------------------------------------------------------------------------------------------

    private val retryAttach = object : Runnable {
        override fun run() {
            if (targetController == null && MediaListenerService.isListenerEnabled(this@MediaMenuService)) {
                try {
                    val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                    refreshControllers(msm.getActiveSessions(MediaListenerService.getComponentName(this@MediaMenuService)))
                } catch (_: Exception) {
                }
            }
            handler.postDelayed(this, 4000)
        }
    }

    private fun attachObservers() {
        handler.removeCallbacks(retryAttach)
        handler.postDelayed(retryAttach, 4000)
        if (!MediaListenerService.isListenerEnabled(this)) {
            AppLogger.log("MediaMenu", "NOT GRANTED: Notification access missing, cannot watch the music app. Menu mode can still be forced.")
            return
        }
        try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val comp = MediaListenerService.getComponentName(this)
            refreshControllers(msm.getActiveSessions(comp))
            val l = MediaSessionManager.OnActiveSessionsChangedListener { list ->
                refreshControllers(list ?: emptyList())
            }
            msm.addOnActiveSessionsChangedListener(l, comp)
            sessionsListener = l
        } catch (e: Exception) {
            AppLogger.logError("MediaMenu", "attachObservers", e)
        }
    }

    private fun detachObservers() {
        try {
            sessionsListener?.let {
                val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                msm.removeOnActiveSessionsChangedListener(it)
            }
        } catch (e: Exception) {
            AppLogger.logError("MediaMenu", "detachObservers", e)
        }
        sessionsListener = null
        for ((c, cb) in registered) {
            try {
                c.unregisterCallback(cb)
            } catch (_: Exception) {
            }
        }
        registered.clear()
        targetController = null
        targetSessionGrace.onSessionPresent()
        watchPlaybackStability.reset()
        lastMissingSessionLogAt = 0L
    }

    /**
     * Pick the real player session. The package in Setup is a preference, not a hard filter:
     * if that app is not the one currently playing, fall back to the active external player.
     * This is important because a media session from another player must still be visible and
     * controllable when the user leaves Watch Menu mode.
     */
    private fun chooseTargetController(list: List<MediaController>): MediaController? {
        val seen = list.map { it.packageName }.filter { it != packageName }.distinct()
        // During a short session handover keep the last known player list instead of flashing empty.
        if (seen.isNotEmpty() || targetController == null) MediaMenuState.seenPlayers.value = seen
        val ignored = MediaMenuConfig.ignoredApps(this)
        val external = list.filter { it.packageName != packageName && it.packageName !in ignored }
        val preferredPackage = MediaMenuConfig.targetPackage(this).trim()

        fun usable(controller: MediaController): Boolean {
            val state = controller.playbackState?.state
            val hasMetadata = controller.metadata != null
            return hasMetadata || (state != null && state != PlaybackState.STATE_NONE && state != PlaybackState.STATE_STOPPED)
        }

        // A currently playing app wins over a remembered preference for another player.
        // Otherwise, a stale paused session (for example YouTube Music left in the background)
        // can steal the watch controls from the song the user is actually listening to.
        val playing = external.filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        if (playing.isNotEmpty()) {
            if (preferredPackage.isNotEmpty()) {
                playing.firstOrNull { it.packageName == preferredPackage }?.let { return it }
            }
            return playing.first()
        }

        if (preferredPackage.isNotEmpty()) {
            external.firstOrNull { it.packageName == preferredPackage && usable(it) }?.let { return it }
        }

        // getActiveSessions() is already ordered by recent/active priority. Prefer buffering,
        // then paused sessions that still have track metadata.
        return external.filter(::usable).withIndex()
            .minWithOrNull(compareBy<IndexedValue<MediaController>> {
                when (it.value.playbackState?.state) {
                    PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> 0
                    PlaybackState.STATE_PAUSED -> 1
                    else -> 2
                }
            }.thenBy { it.index })?.value
    }

    private fun refreshControllers(list: List<MediaController>) {
        val newTarget = chooseTargetController(list)
        val current = targetController
        // Android can briefly return an empty/partial list while a player replaces its session
        // (notably around pause, resume, Bluetooth audio routing and notification updates).
        // Keep the currently-bound controller and callback through that handover so the watch
        // never receives an artificial "Nothing playing" snapshot in the middle of a song.
        if (newTarget != null) {
            targetSessionGrace.onSessionPresent()
            // Our own session changes the active-session list too. Keep the callback when the
            // same external player session is still being reported.
            if (current?.sessionToken == newTarget.sessionToken) return
        } else if (current == null) {
            targetSessionGrace.onSessionPresent()
            return
        } else {
            val now = SystemClock.elapsedRealtime()
            if (targetSessionGrace.shouldRetainCurrent(now)) {
                if (now - lastMissingSessionLogAt >= 3000L) {
                    AppLogger.log("MediaMenu", "Active media session briefly missing; retaining the last controller during Android handover")
                    lastMissingSessionLogAt = now
                }
                return
            }
            targetSessionGrace.onSessionPresent()
            lastMissingSessionLogAt = 0L
        }

        // Capture the podcast's own position before Android switches our observer to another
        // app (for example, when the user starts a song while the podcast queue stays saved).
        if (current != null) {
            val activePodcastId = PodcastPlayback.activeItemId(applicationContext)
            if (!activePodcastId.isNullOrBlank()) {
                val podcastItem = MenuStore.groups.value.asSequence()
                    .filter { it.type == GroupType.PODCAST }.flatMap { it.items.asSequence() }
                    .firstOrNull { it.id == activePodcastId }
                if (podcastItem != null && podcastSessionMatches(podcastItem, current)) liveText(podcastItem)
            }
        }

        for ((c, cb) in registered) {
            try {
                c.unregisterCallback(cb)
            } catch (_: Exception) {
            }
        }
        registered.clear()
        targetController = null

        if (newTarget != null) {
            watchPlaybackStability.reset()
            val cb = makeTargetCallback()
            newTarget.registerCallback(cb, handler)
            registered[newTarget] = cb
            targetController = newTarget
            lastObservedState = newTarget.playbackState?.state ?: -1
            lastTitle = metadataTitle(newTarget.metadata)
            lastArtist = metadataArtist(newTarget.metadata)
            val preferred = MediaMenuConfig.targetPackage(this).trim()
            val selection = if (newTarget.packageName == preferred) "preferred player" else "active-player fallback"
            AppLogger.log(
                "MediaMenu",
                "Watching $selection session: ${newTarget.packageName} (state=$lastObservedState title=$lastTitle artist=$lastArtist)"
            )
        } else {
            watchPlaybackStability.reset()
            lastObservedState = -1
            lastTitle = null
            lastArtist = null
            MediaMenuState.seenPlayers.value = emptyList()
            AppLogger.log("MediaMenu", "No active external media session. Start playback and check Notification access.")
        }
        MediaMenuState.phoneMusic.value = when {
            !MediaListenerService.isListenerEnabled(this) -> "Notification access is OFF, so the phone's music can't be seen"
            targetController != null -> "${targetController?.packageName}: ${lastTitle ?: "(no title)"}"
            else -> "No music or video session found on the phone"
        }
        handler.post {
            if (!MediaMenuState.menuMode.value) {
                if (targetController != null) mirrorFromTarget()
                else setMeta("Nothing playing", "")
            }
            applySessionState(force = true)
        }
    }

    private fun metadataTitle(metadata: MediaMetadata?): String? {
        if (metadata == null) return null
        return metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.takeIf { it.isNotBlank() }
            ?: metadata.description?.title?.toString()?.takeIf { it.isNotBlank() }
    }

    private fun metadataArtist(metadata: MediaMetadata?): String? {
        if (metadata == null) return null
        return metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.takeIf { it.isNotBlank() }
            ?: metadata.getString(MediaMetadata.METADATA_KEY_AUTHOR)?.takeIf { it.isNotBlank() }
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)?.takeIf { it.isNotBlank() }
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }
            ?: metadata.description?.subtitle?.toString()?.takeIf { it.isNotBlank() }
    }

    private fun makeTargetCallback() = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            state ?: return
            val s = state.state
            lastObservedState = s
            trackPodcast()
            checkGroupQueueAdvance(targetController?.metadata?.let { metadataTitle(it) }, state)
            val menu = MediaMenuState.menuMode.value
            if (menu && (livePodcastId != null || liveMusicId != null || queueActive)) return
            if (menu) {
                // Menu mode: the real music must stay quiet.
                if (s == PlaybackState.STATE_PLAYING && MediaMenuConfig.undoSkips(this@MediaMenuService)) {
                    try {
                        ignoreTapsUntil = SystemClock.elapsedRealtime() + 2000L
                        targetController?.transportControls?.pause()
                        AppLogger.log("MediaMenu", "GUARD: the real music started, paused it again")
                    } catch (e: Exception) {
                        AppLogger.logError("MediaMenu", "guard pause", e)
                    }
                }
                return
            }
            if (MediaMenuConfig.proxy(this@MediaMenuService)) {
                // Some players publish metadata and playback-state changes separately. Refresh
                // both so leaving menu mode restores the title/artist as well as the controls.
                mirrorFromTarget()
                mirrorStateFromTarget()
            }
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            val t = metadataTitle(metadata)
            val a = metadataArtist(metadata)
            trackPodcast()
            checkGroupQueueAdvance(t, targetController?.playbackState)
            val changed = t != lastTitle || a != lastArtist
            if (!changed) return
            lastTitle = t
            lastArtist = a
            val now = SystemClock.elapsedRealtime()
            if (MediaState_menu() && (livePodcastId != null || liveMusicId != null || queueActive)) return
            if (MediaState_menu()) {
                // The real music changed track while we are in the menu: undo it once.
                if (MediaMenuConfig.undoSkips(this@MediaMenuService) &&
                    now > ignoreMetaUntil && now - lastNavAt < 2000 && lastNavToken.isNotEmpty()
                ) {
                    ignoreMetaUntil = now + 2000
                    try {
                        val tc = targetController?.transportControls
                        if (lastNavToken == "NEXT") tc?.skipToPrevious() else tc?.skipToNext()
                        tc?.pause()
                        AppLogger.log("MediaMenu", "GUARD: the real music skipped ($lastNavToken), undone")
                    } catch (e: Exception) {
                        AppLogger.logError("MediaMenu", "guard undo skip", e)
                    }
                }
                return
            }
            if (MediaMenuConfig.proxy(this@MediaMenuService)) mirrorFromTarget()
        }
    }

    private fun MediaState_menu(): Boolean = MediaMenuState.menuMode.value

    private fun mirrorFromTarget() {
        val md = targetController?.metadata
        val title = metadataTitle(md) ?: "Nothing playing"
        val artist = metadataArtist(md) ?: ""
        setMeta(title, artist)
        targetController?.let { MediaMenuState.phoneMusic.value = "${it.packageName}: $title" }
    }

    /** Re-query immediately before forwarding so a player that became active after menu exit
     * is not missed while waiting for Android's active-session listener callback. */
    private fun refreshTargetFromSystem(): MediaController? {
        if (!MediaListenerService.isListenerEnabled(this)) return targetController
        return try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val list = msm.getActiveSessions(MediaListenerService.getComponentName(this))
            refreshControllers(list)
            targetController
        } catch (e: Exception) {
            AppLogger.logError("MediaMenu", "refresh target before forwarding", e)
            targetController
        }
    }

    /** Artwork supplied by the active media session; use the same cover palette pipeline as saved tracks. */
    private fun metadataArtwork(metadata: MediaMetadata?): Bitmap? {
        if (metadata == null) return null
        return try {
            metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                ?: metadata.description?.iconBitmap
        } catch (e: Exception) {
            AppLogger.logError("MediaMenu", "read active media artwork", e)
            null
        }
    }

    private fun metadataArtworkUri(metadata: MediaMetadata?): String? {
        if (metadata == null) return null
        return try {
            metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)?.takeIf { it.isNotBlank() }
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)?.takeIf { it.isNotBlank() }
                ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)?.takeIf { it.isNotBlank() }
                ?: metadata.description?.iconUri?.toString()?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            AppLogger.logError("MediaMenu", "read active media artwork URI", e)
            null
        }
    }

    /** Current non-Watch-Menu player for the Watch Music page's Return to controls shortcut.
     * Uses the same active-session selection as the Media screen, including videos and browser media. */
    fun watchAppMediaSnapshot(): WatchAppMediaSnapshot? {
        val controller = refreshTargetFromSystem() ?: return null
        val state = controller.playbackState
        val rawPlaying = when (state?.state) {
            PlaybackState.STATE_PLAYING -> true
            PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED, PlaybackState.STATE_ERROR -> false
            // Some players briefly publish NONE/BUFFERING/CONNECTING while replacing a session.
            // Keep the last stable state for a short window instead of flashing pause/play.
            else -> null
        }
        val isPlaying = watchPlaybackStability.resolve(rawPlaying, SystemClock.elapsedRealtime())
        var position = state?.position?.coerceAtLeast(0L) ?: 0L
        if (isPlaying && state != null) {
            position += ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime).coerceAtLeast(0L) * state.playbackSpeed).toLong()
        }
        val metadata = controller.metadata
        val title = metadataTitle(metadata) ?: MediaMenuState.title.value.takeIf { it.isNotBlank() } ?: "Nothing playing"
        val artist = metadataArtist(metadata) ?: MediaMenuState.artist.value
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L
        return WatchAppMediaSnapshot(title, artist, isPlaying, position, duration, metadataArtwork(metadata), metadataArtworkUri(metadata))
    }

    /** Direct transport/seek actions for the Watch Music page, always targeting the active player. */
    fun watchAppMediaCommand(token: String, seekPermille: Int? = null) {
        handler.post {
            val controller = refreshTargetFromSystem()
            if (seekPermille != null) {
                val duration = controller?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                if (duration > 0L) {
                    try { controller?.transportControls?.seekTo(duration * seekPermille.coerceIn(0, 1000) / 1000L) }
                    catch (e: Exception) { AppLogger.logError("MediaMenu", "Watch Music seek", e) }
                }
                return@post
            }
            if (controller == null) {
                dispatchSystemKey(token)
                return@post
            }
            try {
                when (token) {
                    "PLAY" -> controller.transportControls.play()
                    "PAUSE" -> controller.transportControls.pause()
                    "NEXT" -> controller.transportControls.skipToNext()
                    "PREV" -> controller.transportControls.skipToPrevious()
                    else -> return@post
                }
                AppLogger.log("MediaMenu", "Watch Music forwarded $token to ${controller.packageName}")
            } catch (e: Exception) {
                AppLogger.logError("MediaMenu", "Watch Music $token", e)
                dispatchSystemKey(token)
            }
        }
    }

    /** Used when we cannot see the real player: step aside briefly and send a normal media key,
     * so Android hands it to whatever app was playing before us. */
    private fun dispatchSystemKey(token: String) {
        val code = when (token) {
            "PLAY" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
            "PAUSE" -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
            "NEXT" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
            "PREV" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "STOP" -> android.view.KeyEvent.KEYCODE_MEDIA_STOP
            else -> return
        }
        val wasActive = session.isActive
        session.isActive = false
        handler.postDelayed({
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
                am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
                AppLogger.log("MediaMenu", "Fallback: sent system media key $token")
            } catch (e: Exception) {
                AppLogger.logError("MediaMenu", "system key $token", e)
            }
            handler.postDelayed({ if (wasActive && !MediaMenuState.menuMode.value) session.isActive = true }, 700)
        }, 80)
    }

    /** Called when Notification access connects or changes, so the real player can be found. */
    fun reattachObservers() {
        handler.post {
            detachObservers()
            attachObservers()
        }
    }

    private fun forwardToTarget(token: String) {
        if ((token == "NEXT" || token == "PREV") && queueStepIfActive(token)) return
        try {
            if (token == "VOLUME_UP" || token == "VOLUME_DOWN") {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                val want = (cur + if (token == "VOLUME_UP") 1 else -1).coerceIn(0, max)
                try {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, want, AudioManager.FLAG_SHOW_UI)
                } catch (e: Exception) {
                    am.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        if (token == "VOLUME_UP") AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                        AudioManager.FLAG_SHOW_UI
                    )
                }
                AppLogger.log("MediaMenu", "VOLUME $cur -> ${am.getStreamVolume(AudioManager.STREAM_MUSIC)} / $max")
                WatchLink.pushMusic(watchState(), MediaMenuState.title.value, MediaMenuState.artist.value)
                return
            }
            val currentTarget = refreshTargetFromSystem()
            val tc = currentTarget?.transportControls
            if (tc == null) {
                dispatchSystemKey(token)
                val reason = if (MediaListenerService.isListenerEnabled(this))
                    "no active external media session; start playback and verify the music app exposes a media session"
                else "Notification access is not granted; Android does not expose the player's media session"
                AppLogger.log("MediaMenu", "FAIL: cannot forward $token: $reason")
                return
            }
            when (token) {
                "PLAY" -> tc.play()
                "PAUSE" -> tc.pause()
                "NEXT" -> tc.skipToNext()
                "PREV" -> tc.skipToPrevious()
                "STOP" -> tc.stop()
            }
            AppLogger.log("MediaMenu", "SUCCESS: forwarded $token to ${currentTarget.packageName}")
        } catch (e: Exception) {
            AppLogger.logError("MediaMenu", "forward $token", e)
        }
    }
}
