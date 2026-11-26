package com.example

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TestRunnerService : Service() {

    companion object {
        const val EXTRA_TEST_TYPE = "extra_test_type"
        const val EXTRA_DELAY_SECONDS = "extra_delay_seconds"
        const val EXTRA_TARGET_PACKAGE = "extra_target_package"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_QUERY = "extra_query"
        const val EXTRA_KEY_CODE = "extra_key_code"
        const val EXTRA_KEY_NAME = "extra_key_name"
        const val EXTRA_GO_HOME_BACK = "extra_go_home_back"

        fun startTest(
            context: Context,
            testType: String,
            delaySeconds: Int,
            targetPackage: String = "",
            url: String = "",
            query: String = "",
            keyCode: Int = 0,
            keyName: String = "",
            goHomeBack: Boolean = false
        ) {
            val intent = Intent(context, TestRunnerService::class.java).apply {
                putExtra(EXTRA_TEST_TYPE, testType)
                putExtra(EXTRA_DELAY_SECONDS, delaySeconds)
                putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_QUERY, query)
                putExtra(EXTRA_KEY_CODE, keyCode)
                putExtra(EXTRA_KEY_NAME, keyName)
                putExtra(EXTRA_GO_HOME_BACK, goHomeBack)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private val serviceJob = Job()
    private val scope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val testType = intent.getStringExtra(EXTRA_TEST_TYPE) ?: "UNKNOWN"
        val delaySec = intent.getIntExtra(EXTRA_DELAY_SECONDS, 0)
        val targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE) ?: ""
        val url = intent.getStringExtra(EXTRA_URL) ?: ""
        val query = intent.getStringExtra(EXTRA_QUERY) ?: ""
        val keyCode = intent.getIntExtra(EXTRA_KEY_CODE, 0)
        val keyName = intent.getStringExtra(EXTRA_KEY_NAME) ?: ""
        val goHomeBack = intent.getBooleanExtra(EXTRA_GO_HOME_BACK, false)

        val notification = NotificationCompat.Builder(this, TestEngine.CHANNEL_ID_RUNNER)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Test Runner Active")
            .setContentText("Running $testType in ${delaySec}s...")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    TestEngine.NOTIFICATION_ID_RUNNER,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(TestEngine.NOTIFICATION_ID_RUNNER, notification)
            }
        } else {
            startForeground(TestEngine.NOTIFICATION_ID_RUNNER, notification)
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WatchMenu:TestRunner").apply {
            acquire((delaySec + 30) * 1000L)
        }

        AppLogger.log("RunnerService", "Scheduled $testType with ${delaySec}s delay. WakeLock acquired. Lock screen now to test background!")

        scope.launch {
            try {
                if (delaySec > 0) {
                    delay(delaySec * 1000L)
                }
                AppLogger.log("RunnerService", "Timer expired for $testType. Executing test now...")
                runTest(testType, targetPackage, url, query, keyCode, keyName, goHomeBack)
            } catch (e: Exception) {
                AppLogger.logError("RunnerService", "runTest($testType)", e)
            } finally {
                try {
                    wakeLock?.let {
                        if (it.isHeld) it.release()
                    }
                } catch (_: Exception) {}
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun runTest(
        testType: String,
        targetPackage: String,
        url: String,
        query: String,
        keyCode: Int,
        keyName: String,
        goHomeBack: Boolean
    ) {
        when (testType) {
            "MUSIC_LIST_SESSIONS" -> TestEngine.listActiveMediaSessions(this)
            "MUSIC_PLAY_FROM_URI" -> TestEngine.playFromUri(this, targetPackage, url)
            "MUSIC_PLAY_FROM_SEARCH" -> TestEngine.playFromSearch(this, targetPackage, query)
            "MUSIC_MEDIA_BROWSER" -> TestEngine.connectMediaBrowserAndPlay(this, targetPackage, url, query)
            "MUSIC_INTENT_ACTIVITY" -> TestEngine.mediaSearchIntentActivity(this, targetPackage, query)
            "MUSIC_INTENT_BCAST_SERVICE" -> TestEngine.mediaSearchIntentBroadcastAndService(this, targetPackage, query)
            "MUSIC_OPEN_LINK" -> TestEngine.openLink(this, targetPackage, url, goHomeBack)
            "MUSIC_KEY_SESSION" -> TestEngine.sendMediaKeySession(this, targetPackage, keyCode, keyName)
            "MUSIC_KEY_AUDIO" -> TestEngine.sendMediaKeyAudioManager(this, keyCode, keyName)
            "MUSIC_RUN_ALL" -> TestEngine.runAllMusicTests(this, targetPackage, url, query)
            "APP_LAUNCH" -> TestEngine.launchApp(this, targetPackage, goHomeBack)
            "TOGGLE_TORCH_ON" -> TestEngine.setTorch(this, true)
            "TOGGLE_TORCH_OFF" -> TestEngine.setTorch(this, false)
            "TOGGLE_VOL_UP" -> TestEngine.adjustVolume(this, true)
            "TOGGLE_VOL_DOWN" -> TestEngine.adjustVolume(this, false)
            "TOGGLE_DND_ON" -> TestEngine.setDnd(this, true)
            "TOGGLE_DND_OFF" -> TestEngine.setDnd(this, false)
            "TOGGLE_RINGER_SILENT" -> TestEngine.setRingerMode(this, android.media.AudioManager.RINGER_MODE_SILENT, "SILENT")
            "TOGGLE_RINGER_VIBRATE" -> TestEngine.setRingerMode(this, android.media.AudioManager.RINGER_MODE_VIBRATE, "VIBRATE")
            "TOGGLE_RINGER_NORMAL" -> TestEngine.setRingerMode(this, android.media.AudioManager.RINGER_MODE_NORMAL, "NORMAL")
            "TOGGLE_LOCK_SCREEN" -> TestEngine.lockScreen(this)
            "TOGGLE_SCREENSHOT" -> TestEngine.takeScreenshot(this)
            "URL_OPEN_LINK" -> TestEngine.openUrlAsLink(this, url)
            "URL_SILENT_GET" -> TestEngine.sendSilentGet(this, url)
            "NOTIFY_POST_TEST" -> TestEngine.postTestNotification(this)
            else -> AppLogger.log("RunnerService", "Unknown test type: $testType")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                TestEngine.CHANNEL_ID_RUNNER,
                "Test Runner Silent Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Minimal silent foreground notification while running delayed background tests"
                enableVibration(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Exception) {}
    }
}
