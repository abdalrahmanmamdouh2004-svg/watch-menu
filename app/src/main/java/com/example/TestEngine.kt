package com.example

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object TestEngine {

    const val NOTIFICATION_ID_REPLY = 1001
    const val NOTIFICATION_ID_RUNNER = 2001
    const val CHANNEL_ID_REPLY = "watch_lab_reply_channel"
    const val CHANNEL_ID_RUNNER = "watch_lab_runner_channel"

    private val scope = CoroutineScope(Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var lastMusicActionName: String = "playFromSearch"

    @Volatile
    var lastTargetPackage: String = "com.google.android.apps.youtube.music"

    @Volatile
    var lastMusicUrl: String = "https://music.youtube.com/watch?v=dQw4w9WgXcQ"

    @Volatile
    var lastMusicQuery: String = "Never Gonna Give You Up Rick Astley"

    fun logSystemState(context: Context, testTag: String) {
        try {
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isLocked = km?.isKeyguardLocked ?: false
            val isInteractive = pm?.isInteractive ?: false
            AppLogger.log(testTag, "Device state: keyguardLocked=$isLocked, screenInteractive=$isInteractive")
        } catch (e: Exception) {
            AppLogger.logError(testTag, "logSystemState", e)
        }
    }

    // -------------------------------------------------------------
    // Section C: Music Tests
    // -------------------------------------------------------------

    fun listActiveMediaSessions(context: Context): String {
        logSystemState(context, "Music:ListSessions")
        return try {
            val sessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = MediaListenerService.getComponentName(context)

            if (!MediaListenerService.isListenerEnabled(context)) {
                val msg = "FAIL: Notification Listener permission not granted. Cannot query active sessions."
                AppLogger.log("Music:ListSessions", msg)
                return msg
            }

            val sessions = sessionManager.getActiveSessions(component)
            if (sessions.isEmpty()) {
                val msg = "No active media sessions found (0 sessions). Ensure music player was launched."
                AppLogger.log("Music:ListSessions", msg)
                return msg
            }

            val sb = StringBuilder("Found ${sessions.size} active session(s):\n")
            for ((index, controller) in sessions.withIndex()) {
                val pkg = controller.packageName
                val playbackState = controller.playbackState
                val stateName = decodePlaybackState(playbackState?.state)
                val actionsDesc = decodeActions(playbackState?.actions ?: 0L)
                val info = "  #${index + 1} [$pkg] State=$stateName (${playbackState?.state}), Actions=[$actionsDesc]"
                sb.append(info).append("\n")
                AppLogger.log("Music:ListSessions", info)
            }
            sb.toString().trimEnd()
        } catch (e: Exception) {
            AppLogger.logError("Music:ListSessions", "getActiveSessions", e)
            "FAIL: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    fun playFromUri(context: Context, targetPackage: String, url: String): String {
        logSystemState(context, "Music:playFromUri")
        lastMusicActionName = "playFromUri"
        lastTargetPackage = targetPackage
        lastMusicUrl = url
        return try {
            val sessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = MediaListenerService.getComponentName(context)
            val sessions = sessionManager.getActiveSessions(component)
            val controller = sessions.firstOrNull { it.packageName == targetPackage }

            if (controller == null) {
                val msg = "FAIL: No active session for $targetPackage. Use 'Connect to MediaBrowserService' or launch app first."
                AppLogger.log("Music:playFromUri", msg)
                return msg
            }

            val uri = Uri.parse(url)
            controller.transportControls.playFromUri(uri, Bundle())
            val msg = "SUCCESS: Dispatched playFromUri($url) on $targetPackage session"
            AppLogger.log("Music:playFromUri", msg)
            msg
        } catch (e: Exception) {
            AppLogger.logError("Music:playFromUri", "playFromUri", e)
            "FAIL: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    fun playFromSearch(context: Context, targetPackage: String, query: String): String {
        logSystemState(context, "Music:playFromSearch")
        lastMusicActionName = "playFromSearch"
        lastTargetPackage = targetPackage
        lastMusicQuery = query
        return try {
            val sessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = MediaListenerService.getComponentName(context)
            val sessions = sessionManager.getActiveSessions(component)
            val controller = sessions.firstOrNull { it.packageName == targetPackage }

            if (controller == null) {
                val msg = "FAIL: No active session for $targetPackage. Use 'Connect to MediaBrowserService' or launch app first."
                AppLogger.log("Music:playFromSearch", msg)
                return msg
            }

            controller.transportControls.playFromSearch(query, Bundle())
            val msg = "SUCCESS: Dispatched playFromSearch(\"$query\") on $targetPackage session"
            AppLogger.log("Music:playFromSearch", msg)
            msg
        } catch (e: Exception) {
            AppLogger.logError("Music:playFromSearch", "playFromSearch", e)
            "FAIL: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    suspend fun connectMediaBrowserAndPlay(
        context: Context,
        targetPackage: String,
        url: String,
        query: String
    ): String = withContext(Dispatchers.IO) {
        logSystemState(context, "Music:MediaBrowser")
        lastMusicActionName = "connectMediaBrowser"
        lastTargetPackage = targetPackage
        lastMusicUrl = url
        lastMusicQuery = query

        val browserIntent = Intent("android.media.browse.MediaBrowserService")
        val serviceList = context.packageManager.queryIntentServices(browserIntent, 0)
        val matchedService = serviceList.firstOrNull { it.serviceInfo.packageName == targetPackage }

        if (matchedService == null) {
            val msg = "FAIL: No MediaBrowserService found for $targetPackage in queryIntentServices. Found: ${serviceList.map { it.serviceInfo.packageName }}"
            AppLogger.log("Music:MediaBrowser", msg)
            return@withContext msg
        }

        val serviceComponent = ComponentName(matchedService.serviceInfo.packageName, matchedService.serviceInfo.name)
        AppLogger.log("Music:MediaBrowser", "Found MediaBrowserService: ${serviceComponent.flattenToShortString()}")

        val latch = CountDownLatch(1)
        var outcome = "FAIL: Connection timed out after 10s"

        mainHandler.post {
            try {
                var browserRef: MediaBrowser? = null
                val callback = object : MediaBrowser.ConnectionCallback() {
                    override fun onConnected() {
                        try {
                            val token = browserRef?.sessionToken
                            if (token != null) {
                                val controller = MediaController(context, token)
                                controller.transportControls.playFromSearch(query, Bundle())
                                outcome = "SUCCESS: Connected to ${serviceComponent.className}. SessionToken acquired. Dispatched playFromSearch(\"$query\")."
                                AppLogger.log("Music:MediaBrowser", outcome)
                            } else {
                                outcome = "FAIL: Connected to ${serviceComponent.className} but sessionToken was null"
                                AppLogger.log("Music:MediaBrowser", outcome)
                            }
                        } catch (e: Exception) {
                            outcome = "FAIL: onConnected error: ${e.javaClass.simpleName}: ${e.message}"
                            AppLogger.logError("Music:MediaBrowser", "transportControls", e)
                        } finally {
                            try {
                                browserRef?.disconnect()
                            } catch (_: Exception) {}
                            latch.countDown()
                        }
                    }

                    override fun onConnectionFailed() {
                        outcome = "FAIL: MediaBrowser onConnectionFailed for ${serviceComponent.className}"
                        AppLogger.log("Music:MediaBrowser", outcome)
                        try {
                            browserRef?.disconnect()
                        } catch (_: Exception) {}
                        latch.countDown()
                    }

                    override fun onConnectionSuspended() {
                        AppLogger.log("Music:MediaBrowser", "MediaBrowser onConnectionSuspended")
                    }
                }

                val browser = MediaBrowser(context, serviceComponent, callback, null)
                browserRef = browser
                browser.connect()
            } catch (e: Exception) {
                outcome = "FAIL: Exception creating MediaBrowser: ${e.javaClass.simpleName}: ${e.message}"
                AppLogger.logError("Music:MediaBrowser", "MediaBrowser.connect", e)
                latch.countDown()
            }
        }

        latch.await(10, TimeUnit.SECONDS)
        outcome
    }

    fun mediaSearchIntentActivity(context: Context, targetPackage: String, query: String): String {
        logSystemState(context, "Music:IntentActivity")
        lastMusicActionName = "mediaSearchIntentActivity"
        lastTargetPackage = targetPackage
        lastMusicQuery = query
        return try {
            val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                setPackage(targetPackage)
                putExtra(SearchManager.QUERY, query)
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val resolved = intent.resolveActivity(context.packageManager)
            if (resolved == null) {
                val msg = "FAIL: No Activity resolved for INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH in $targetPackage"
                AppLogger.log("Music:IntentActivity", msg)
                return msg
            }

            context.startActivity(intent)
            val msg = "SUCCESS: startActivity sent for $resolved with query=\"$query\""
            AppLogger.log("Music:IntentActivity", msg)
            msg
        } catch (e: Exception) {
            AppLogger.logError("Music:IntentActivity", "startActivity", e)
            "FAIL: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    fun mediaSearchIntentBroadcastAndService(context: Context, targetPackage: String, query: String): String {
        logSystemState(context, "Music:IntentBcastService")
        val results = mutableListOf<String>()

        // Broadcast variant
        try {
            val bcastIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                setPackage(targetPackage)
                putExtra(SearchManager.QUERY, query)
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            }
            context.sendBroadcast(bcastIntent)
            results.add("Broadcast: sendBroadcast dispatched without exception")
            AppLogger.log("Music:IntentBcastService", "Broadcast sent to $targetPackage")
        } catch (e: Exception) {
            val err = "Broadcast FAIL: ${e.javaClass.simpleName}: ${e.message}"
            results.add(err)
            AppLogger.logError("Music:IntentBcastService", "sendBroadcast", e)
        }

        // Service variant
        try {
            val svcIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                setPackage(targetPackage)
                putExtra(SearchManager.QUERY, query)
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            }
            val matches = context.packageManager.queryIntentServices(svcIntent, 0)
            if (matches.isEmpty()) {
                results.add("Service: No matching service registered for MEDIA_PLAY_FROM_SEARCH")
                AppLogger.log("Music:IntentBcastService", "No services match intent")
            } else {
                for (match in matches) {
                    val comp = ComponentName(match.serviceInfo.packageName, match.serviceInfo.name)
                    svcIntent.component = comp
                    try {
                        context.startService(svcIntent)
                        results.add("Service ${comp.shortClassName}: startService returned without exception")
                        AppLogger.log("Music:IntentBcastService", "startService succeeded for $comp")
                    } catch (e: Exception) {
                        results.add("Service ${comp.shortClassName} FAIL: ${e.javaClass.simpleName}: ${e.message}")
                        AppLogger.logError("Music:IntentBcastService", "startService($comp)", e)
                    }
                }
            }
        } catch (e: Exception) {
            results.add("Service query FAIL: ${e.javaClass.simpleName}: ${e.message}")
            AppLogger.logError("Music:IntentBcastService", "queryIntentServices", e)
        }

        return results.joinToString("\n")
    }

    fun openLink(context: Context, targetPackage: String, url: String, goHomeBackAfter3s: Boolean): String {
        logSystemState(context, "Music:OpenLink")
        lastMusicActionName = "openLink"
        lastTargetPackage = targetPackage
        lastMusicUrl = url
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage(targetPackage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val msg = "SUCCESS: startActivity(ACTION_VIEW) launched with package=$targetPackage, url=$url"
            AppLogger.log("Music:OpenLink", msg)

            if (goHomeBackAfter3s) {
                scheduleHomeOrBack(3000)
            }
            msg
        } catch (e: Exception) {
            AppLogger.logError("Music:OpenLink", "startActivity(ACTION_VIEW)", e)
            "FAIL: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    fun sendMediaKeySession(context: Context, targetPackage: String, keyCode: Int, keyName: String): String {
        logSystemState(context, "Music:KeySession")
        return try {
            val sessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = MediaListenerService.getComponentName(context)
            val sessions = sessionManager.getActiveSessions(component)
            val controller = sessions.firstOrNull { it.packageName == targetPackage }

            if (controller == null) {
                val msg = "FAIL: No active session for $targetPackage"
                AppLogger.log("Music:KeySession", msg)
                return msg
            }

            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    val state = controller.playbackState?.state
                    if (state == PlaybackState.STATE_PLAYING) {
                        controller.transportControls.pause()
                        AppLogger.log("Music:KeySession", "SUCCESS: Called pause() on $targetPackage session")
                    } else {
                        controller.transportControls.play()
                        AppLogger.log("Music:KeySession", "SUCCESS: Called play() on $targetPackage session")
                    }
                }
                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    controller.transportControls.skipToNext()
                    AppLogger.log("Music:KeySession", "SUCCESS: Called skipToNext() on $targetPackage session")
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    controller.transportControls.skipToPrevious()
                    AppLogger.log("Music:KeySession", "SUCCESS: Called skipToPrevious() on $targetPackage session")
                }
                KeyEvent.KEYCODE_MEDIA_STOP -> {
                    controller.transportControls.stop()
                    AppLogger.log("Music:KeySession", "SUCCESS: Called stop() on $targetPackage session")
                }
                else -> {
                    // Send media key event to session
                    val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
                    val up = KeyEvent(KeyEvent.ACTION_UP, keyCode)
                    val sentDown = controller.dispatchMediaButtonEvent(down)
                    val sentUp = controller.dispatchMediaButtonEvent(up)
                    AppLogger.log("Music:KeySession", "SUCCESS: dispatchMediaButtonEvent($keyName) down=$sentDown up=$sentUp")
                }
            }
            "SUCCESS: Media key $keyName sent via session"
        } catch (e: Exception) {
            AppLogger.logError("Music:KeySession", "transportControls $keyName", e)
            "FAIL: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    fun sendMediaKeyAudioManager(context: Context, keyCode: Int, keyName: String): String {
        logSystemState(context, "Music:KeyAudioMgr")
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val up = KeyEvent(KeyEvent.ACTION_UP, keyCode)
            am.dispatchMediaKeyEvent(down)
            am.dispatchMediaKeyEvent(up)
            val msg = "SUCCESS: AudioManager.dispatchMediaKeyEvent($keyName) down+up sent without exception"
            AppLogger.log("Music:KeyAudioMgr", msg)
            msg
        } catch (e: Exception) {
            AppLogger.logError("Music:KeyAudioMgr", "dispatchMediaKeyEvent $keyName", e)
            "FAIL: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    suspend fun runAllMusicTests(
        context: Context,
        targetPackage: String,
        url: String,
        query: String
    ): String = withContext(Dispatchers.IO) {
        AppLogger.log("Music:RunAll", "=== STARTING ALL MUSIC TESTS (2 to 8) WITH 6s GAPS ===")
        val results = mutableListOf<Triple<String, String, String>>() // (TestName, Status, Detail)

        fun record(name: String, resultString: String) {
            val status = if (resultString.startsWith("SUCCESS")) "PASS" else "FAIL"
            results.add(Triple(name, status, resultString.lines().firstOrNull() ?: ""))
        }

        // Test 2: playFromUri
        val r2 = playFromUri(context, targetPackage, url)
        record("2. playFromUri", r2)
        delay(6000)

        // Test 3: playFromSearch
        val r3 = playFromSearch(context, targetPackage, query)
        record("3. playFromSearch", r3)
        delay(6000)

        // Test 4: MediaBrowserService
        val r4 = connectMediaBrowserAndPlay(context, targetPackage, url, query)
        record("4. MediaBrowserService", r4)
        delay(6000)

        // Test 5: Media search intent (activity)
        val r5 = mediaSearchIntentActivity(context, targetPackage, query)
        record("5. IntentActivity", r5)
        delay(6000)

        // Test 6: Media search intent (broadcast/service)
        val r6 = mediaSearchIntentBroadcastAndService(context, targetPackage, query)
        record("6. IntentBcastService", r6)
        delay(6000)

        // Test 7: Open link (ACTION_VIEW)
        val r7 = openLink(context, targetPackage, url, goHomeBackAfter3s = false)
        record("7. OpenLink", r7)
        delay(6000)

        // Test 8a: Media keys via session
        val r8a = sendMediaKeySession(context, targetPackage, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "PLAY_PAUSE")
        record("8a. MediaKeySession", r8a)
        delay(3000)

        // Test 8b: Media keys via AudioManager
        val r8b = sendMediaKeyAudioManager(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "PLAY_PAUSE")
        record("8b. MediaKeyAudioManager", r8b)

        // Build Summary Table
        val sb = StringBuilder("\n=== SUMMARY TABLE OF MUSIC TESTS ===\n")
        sb.append(String.format("%-25s | %-6s | %s\n", "TEST", "RESULT", "DETAILS / EXCEPTION"))
        sb.append("-".repeat(75)).append("\n")
        for ((name, status, detail) in results) {
            sb.append(String.format("%-25s | %-6s | %s\n", name, status, detail.take(45)))
        }
        sb.append("=".repeat(75))
        val summary = sb.toString()
        AppLogger.log("Music:RunAll", summary)
        summary
    }

    fun runLastMusicTest(context: Context) {
        scope.launch {
            AppLogger.log("Music:LastTest", "Triggering last music test: $lastMusicActionName")
            when (lastMusicActionName) {
                "playFromUri" -> playFromUri(context, lastTargetPackage, lastMusicUrl)
                "playFromSearch" -> playFromSearch(context, lastTargetPackage, lastMusicQuery)
                "connectMediaBrowser" -> connectMediaBrowserAndPlay(context, lastTargetPackage, lastMusicUrl, lastMusicQuery)
                "mediaSearchIntentActivity" -> mediaSearchIntentActivity(context, lastTargetPackage, lastMusicQuery)
                "openLink" -> openLink(context, lastTargetPackage, lastMusicUrl, false)
                else -> playFromSearch(context, lastTargetPackage, lastMusicQuery)
            }
        }
    }

    // -------------------------------------------------------------
    // Section D: Open an App
    // -------------------------------------------------------------

    fun launchApp(context: Context, packageName: String, goHomeBackAfter3s: Boolean): String {
        logSystemState(context, "AppLaunch")
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent == null) {
                val msg = "FAIL: No launch intent found for $packageName"
                AppLogger.log("AppLaunch", msg)
                return msg
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)

            // Check if app process importance reached foreground
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val runningProcesses = am.runningAppProcesses ?: emptyList()
            val proc = runningProcesses.firstOrNull { it.processName == packageName }
            val importanceStr = proc?.importance?.let { decodeImportance(it) } ?: "Not found in processes"

            val msg = "SUCCESS: startActivity returned without exception. Current importance: $importanceStr"
            AppLogger.log("AppLaunch", msg)

            if (goHomeBackAfter3s) {
                scheduleHomeOrBack(3000)
            }
            msg
        } catch (e: Exception) {
            AppLogger.logError("AppLaunch", "launchApp($packageName)", e)
            "FAIL: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    // -------------------------------------------------------------
    // Section E: Phone Toggles
    // -------------------------------------------------------------

    fun setTorch(context: Context, enable: Boolean): String {
        logSystemState(context, "Toggle:Torch")
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cm.cameraIdList.firstOrNull { id ->
                val chars = cm.getCameraCharacteristics(id)
                val flash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                flash && facing == CameraCharacteristics.LENS_FACING_BACK
            } ?: cm.cameraIdList.firstOrNull()

            if (cameraId == null) {
                val msg = "FAIL: No camera with flash available"
                AppLogger.log("Toggle:Torch", msg)
                return msg
            }

            cm.setTorchMode(cameraId, enable)
            val msg = "SUCCESS: setTorchMode(camera=$cameraId, enable=$enable) completed"
            AppLogger.log("Toggle:Torch", msg)
            msg
        } catch (e: Exception) {
            AppLogger.logError("Toggle:Torch", "setTorchMode", e)
            "FAIL: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    fun adjustVolume(context: Context, raise: Boolean): String {
        logSystemState(context, "Toggle:Volume")
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val direction = if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val msg = "SUCCESS: Volume ${if (raise) "raised" else "lowered"}. Current music volume: $current/$max"
            AppLogger.log("Toggle:Volume", msg)
            msg
        } catch (e: Exception) {
            AppLogger.logError("Toggle:Volume", "adjustStreamVolume", e)
            "FAIL: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    fun setDnd(context: Context, enable: Boolean): String {
        logSystemState(context, "Toggle:DND")
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!nm.isNotificationPolicyAccessGranted) {
                val msg = "FAIL: Do Not Disturb policy access NOT granted. Please grant in Section A."
                AppLogger.log("Toggle:DND", msg)
                return msg
            }
            val filter = if (enable) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
            nm.setInterruptionFilter(filter)
            val msg = "SUCCESS: setInterruptionFilter(${if (enable) "PRIORITY" else "ALL"})"
            AppLogger.log("Toggle:DND", msg)
            msg
        } catch (e: Exception) {
            AppLogger.logError("Toggle:DND", "setInterruptionFilter", e)
            "FAIL: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    fun setRingerMode(context: Context, mode: Int, modeName: String): String {
        logSystemState(context, "Toggle:Ringer")
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.ringerMode = mode
            val msg = "SUCCESS: ringerMode set to $modeName"
            AppLogger.log("Toggle:Ringer", msg)
            msg
        } catch (e: SecurityException) {
            val msg = "FAIL: SecurityException setting $modeName ringer. Missing DND Policy Access permission: ${e.message}"
            AppLogger.log("Toggle:Ringer", msg)
            msg
        } catch (e: Exception) {
            AppLogger.logError("Toggle:Ringer", "setRingerMode($modeName)", e)
            "FAIL: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    fun lockScreen(context: Context): String {
        logSystemState(context, "Toggle:LockScreen")
        val service = MenuAccessibilityService.instance
        if (service == null) {
            val msg = "FAIL: Accessibility service is NOT enabled. Enable it in Section A."
            AppLogger.log("Toggle:LockScreen", msg)
            return msg
        }
        val ok = service.executeGlobal(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, "LOCK_SCREEN")
        val msg = if (ok) "SUCCESS: GLOBAL_ACTION_LOCK_SCREEN executed" else "FAIL: performGlobalAction(LOCK_SCREEN) returned false"
        AppLogger.log("Toggle:LockScreen", msg)
        return msg
    }

    fun takeScreenshot(context: Context): String {
        logSystemState(context, "Toggle:Screenshot")
        val service = MenuAccessibilityService.instance
        if (service == null) {
            val msg = "FAIL: Accessibility service is NOT enabled. Enable it in Section A."
            AppLogger.log("Toggle:Screenshot", msg)
            return msg
        }
        val ok = service.executeGlobal(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, "TAKE_SCREENSHOT")
        val msg = if (ok) "SUCCESS: GLOBAL_ACTION_TAKE_SCREENSHOT executed" else "FAIL: performGlobalAction(TAKE_SCREENSHOT) returned false"
        AppLogger.log("Toggle:Screenshot", msg)
        return msg
    }

    // -------------------------------------------------------------
    // Section F: Custom URL
    // -------------------------------------------------------------

    fun openUrlAsLink(context: Context, url: String): String {
        logSystemState(context, "URL:OpenLink")
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val msg = "SUCCESS: startActivity(ACTION_VIEW) launched with url: $url"
            AppLogger.log("URL:OpenLink", msg)
            msg
        } catch (e: Exception) {
            AppLogger.logError("URL:OpenLink", "openUrlAsLink", e)
            "FAIL: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    suspend fun sendSilentGet(context: Context, url: String): String = withContext(Dispatchers.IO) {
        logSystemState(context, "URL:SilentGet")
        return@withContext try {
            val client = OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()

            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val code = response.code
            val body = response.body?.string()?.take(200) ?: ""
            val msg = "SUCCESS: HTTP $code. Body preview: \"${body.replace("\n", " ")}\""
            AppLogger.log("URL:SilentGet", msg)
            msg
        } catch (e: Exception) {
            AppLogger.logError("URL:SilentGet", "sendSilentGet($url)", e)
            "FAIL: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    // -------------------------------------------------------------
    // Section G: Notification Reply Test
    // -------------------------------------------------------------

    fun postTestNotification(context: Context): String {
        logSystemState(context, "Notify:Post")
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    CHANNEL_ID_REPLY,
                    "Watch Menu",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Channel for CMF Watch Pro 2 quick reply testing"
                    enableVibration(true)
                }
                nm.createNotificationChannel(channel)
            }

            val person = Person.Builder()
                .setName("Watch Menu")
                .setKey("lab_contact")
                .build()

            val remoteInput = RemoteInput.Builder("reply")
                .setLabel("Command")
                .build()

            val replyIntent = Intent(context, ReplyReceiver::class.java).apply {
                action = ReplyReceiver.ACTION_NOTIFICATION_REPLY
            }
            val replyPendingIntent = PendingIntent.getBroadcast(
                context,
                NOTIFICATION_ID_REPLY,
                replyIntent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val replyAction = NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_send,
                "Reply",
                replyPendingIntent
            )
                .addRemoteInput(remoteInput)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .setShowsUserInterface(false)
                .setAllowGeneratedReplies(false)
                .build()

            val messagingStyle = NotificationCompat.MessagingStyle(person)
                .addMessage(
                    NotificationCompat.MessagingStyle.Message(
                        "‹ Test ›",
                        System.currentTimeMillis(),
                        person
                    )
                )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID_REPLY)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setStyle(messagingStyle)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setOngoing(false)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(replyAction)
                .setAutoCancel(false)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()

            nm.notify(NOTIFICATION_ID_REPLY, notification)
            val msg = "SUCCESS: Test notification posted with MessagingStyle and quick reply action. Check watch!"
            AppLogger.log("Notify:Post", msg)
            msg
        } catch (e: Exception) {
            AppLogger.logError("Notify:Post", "postTestNotification", e)
            "FAIL: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    // -------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------

    private fun scheduleHomeOrBack(delayMs: Long) {
        scope.launch {
            delay(delayMs)
            val service = MenuAccessibilityService.instance
            if (service != null) {
                service.executeGlobal(AccessibilityService.GLOBAL_ACTION_HOME, "HOME")
            } else {
                AppLogger.log("Accessibility", "Cannot go Home: service not connected")
            }
        }
    }

    private fun decodePlaybackState(state: Int?): String {
        return when (state) {
            PlaybackState.STATE_NONE -> "STATE_NONE"
            PlaybackState.STATE_STOPPED -> "STATE_STOPPED"
            PlaybackState.STATE_PAUSED -> "STATE_PAUSED"
            PlaybackState.STATE_PLAYING -> "STATE_PLAYING"
            PlaybackState.STATE_FAST_FORWARDING -> "STATE_FAST_FORWARDING"
            PlaybackState.STATE_REWINDING -> "STATE_REWINDING"
            PlaybackState.STATE_BUFFERING -> "STATE_BUFFERING"
            PlaybackState.STATE_ERROR -> "STATE_ERROR"
            PlaybackState.STATE_CONNECTING -> "STATE_CONNECTING"
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "STATE_SKIPPING_TO_PREVIOUS"
            PlaybackState.STATE_SKIPPING_TO_NEXT -> "STATE_SKIPPING_TO_NEXT"
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "STATE_SKIPPING_TO_QUEUE_ITEM"
            null -> "UNKNOWN (null)"
            else -> "STATE_$state"
        }
    }

    private fun decodeActions(actions: Long): String {
        val list = mutableListOf<String>()
        if ((actions and PlaybackState.ACTION_STOP) != 0L) list.add("STOP")
        if ((actions and PlaybackState.ACTION_PAUSE) != 0L) list.add("PAUSE")
        if ((actions and PlaybackState.ACTION_PLAY) != 0L) list.add("PLAY")
        if ((actions and PlaybackState.ACTION_REWIND) != 0L) list.add("REWIND")
        if ((actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L) list.add("PREV")
        if ((actions and PlaybackState.ACTION_SKIP_TO_NEXT) != 0L) list.add("NEXT")
        if ((actions and PlaybackState.ACTION_FAST_FORWARD) != 0L) list.add("FAST_FWD")
        if ((actions and PlaybackState.ACTION_SET_RATING) != 0L) list.add("SET_RATING")
        if ((actions and PlaybackState.ACTION_SEEK_TO) != 0L) list.add("SEEK_TO")
        if ((actions and PlaybackState.ACTION_PLAY_PAUSE) != 0L) list.add("PLAY_PAUSE")
        if ((actions and PlaybackState.ACTION_PLAY_FROM_MEDIA_ID) != 0L) list.add("PLAY_MEDIA_ID")
        if ((actions and PlaybackState.ACTION_PLAY_FROM_SEARCH) != 0L) list.add("PLAY_SEARCH")
        if ((actions and PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM) != 0L) list.add("SKIP_QUEUE")
        if ((actions and PlaybackState.ACTION_PLAY_FROM_URI) != 0L) list.add("PLAY_URI")
        if ((actions and PlaybackState.ACTION_PREPARE) != 0L) list.add("PREPARE")
        if ((actions and PlaybackState.ACTION_PREPARE_FROM_MEDIA_ID) != 0L) list.add("PREP_MEDIA_ID")
        if ((actions and PlaybackState.ACTION_PREPARE_FROM_SEARCH) != 0L) list.add("PREP_SEARCH")
        if ((actions and PlaybackState.ACTION_PREPARE_FROM_URI) != 0L) list.add("PREP_URI")
        return if (list.isEmpty()) "NONE ($actions)" else list.joinToString(",")
    }

    private fun decodeImportance(importance: Int): String {
        return when (importance) {
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "FOREGROUND (100)"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "FOREGROUND_SERVICE (125)"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_TOP_SLEEPING -> "TOP_SLEEPING (325)"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "VISIBLE (200)"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "PERCEPTIBLE (230)"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "CACHED (400)"
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "GONE (1000)"
            else -> "IMPORTANCE_$importance"
        }
    }
}
