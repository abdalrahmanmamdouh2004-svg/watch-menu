package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Phone-microphone dictation for Watch Replies.
 * The service is promoted while HomeActivity is visible, then stays ready in the background.
 * SpeechRecognizer uses the phone's normal microphone; no HFP call or watch-audio routing is involved.
 */
class PhoneDictationService : Service() {
    companion object {
        // Shared with MediaMenuService: same channel and id, so the phone shows ONE quiet "Watch Menu" notice.
        private const val CHANNEL_ID = "watch_menu_quiet"
        private const val NOTIFICATION_ID = 2001
        private const val ACTION_READY = "com.example.watchreplies.voice.READY"
            @Volatile private var active: PhoneDictationService? = null

        fun ensureReady(context: Context) {
            val intent = Intent(context, PhoneDictationService::class.java).setAction(ACTION_READY)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                AppLogger.logError("VoiceDictation", "start foreground microphone service", e)
            }
        }

        /** The service is pre-started from HomeActivity; no background service launch is attempted here. */
        fun toggle(languageTag: String, context: Context? = null) {
            val service = active
            if (service == null && context != null &&
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                // first use of the watch microphone: ask for the permission now, not at the first start of the app
                try {
                    context.startActivity(
                        Intent(context, HomeActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            .putExtra(HomeActivity.EXTRA_ASK_MIC, true)
                    )
                } catch (e: Exception) {
                    AppLogger.logError("VoiceDictation", "ask for the microphone", e)
                }
                Replies.voiceStatus(2)
            } else if (service == null) {
                AppLogger.log("VoiceDictation", "Service is not ready; reopen Watch Menu once on the phone")
                Replies.voiceStatus(2)
            } else {
                android.os.Handler(service.mainLooper).post { service.toggleFromWatch(languageTag) }
            }
        }
    }

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var stopRequested = false

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            updateNotification("Listening on the phone microphone…")
        }
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onResults(results: Bundle?) {
            val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            listening = false
            stopRequested = false
            updateNotification("Ready — tap the microphone on Watch Replies")
            if (spoken.isNotBlank()) {
                Replies.voiceStatus(0)
                Replies.voiceText(spoken)
            } else {
                Replies.voiceStatus(2)
            }
        }

        override fun onError(error: Int) {
            listening = false
            stopRequested = false
            updateNotification("Voice input unavailable — check speech services or try again")
            Replies.voiceStatus(2)
            android.os.Handler(mainLooper).postDelayed({
                if (!listening) updateNotification("Ready — tap the microphone on Watch Replies")
            }, 3500L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        active = this
        createNotificationChannel()
        promoteToForeground("Ready — tap the microphone on Watch Replies")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Toggle requests are delivered directly to the live service instance by Replies.
        return START_NOT_STICKY
    }

    private fun toggleFromWatch(languageTag: String) {
        if (listening) requestStop() else startDictation(languageTag)
    }

    private fun startDictation(languageTag: String) {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            updateNotification("Open Watch Menu and allow microphone permission")
            Replies.voiceStatus(2)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotification("No speech recognition service is available")
            Replies.voiceStatus(2)
            return
        }
        try {
            val engine = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
                it.setRecognitionListener(recognitionListener)
                recognizer = it
            }
            stopRequested = false
            listening = true
            updateNotification("Listening on the phone microphone…")
            Replies.voiceStatus(1)
            val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                // The watch keyboard selects English or Egyptian Arabic; do not use the phone's global locale.
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            }
            engine.startListening(request)
        } catch (e: Exception) {
            listening = false
            stopRequested = false
            AppLogger.logError("VoiceDictation", "start speech recognition", e)
            updateNotification("Could not start speech recognition")
            Replies.voiceStatus(2)
        }
    }

    /** A second tap asks Android to finish the current phrase and return its recognized text. */
    private fun requestStop() {
        if (!listening || stopRequested) return
        stopRequested = true
        updateNotification("Finishing dictation…")
        try {
            recognizer?.stopListening()
        } catch (e: Exception) {
            AppLogger.logError("VoiceDictation", "stop speech recognition", e)
            listening = false
            stopRequested = false
            Replies.voiceStatus(2)
            updateNotification("Ready — tap the microphone on Watch Replies")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL_ID, "Watch Menu", NotificationManager.IMPORTANCE_MIN))
        }
    }

    /** The same plain notice the app already shows; it never says anything about the microphone. */
    private fun notification(@Suppress("UNUSED_PARAMETER") message: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Watch Menu")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    private fun promoteToForeground(message: String) {
        val n = notification(message)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun updateNotification(@Suppress("UNUSED_PARAMETER") message: String) {
        // the notice is the same in every state, so there is nothing to update
    }

    override fun onDestroy() {
        listening = false
        if (active === this) active = null
        try { recognizer?.cancel() } catch (_: Exception) { }
        recognizer?.destroy()
        recognizer = null
        stopForeground(STOP_FOREGROUND_DETACH) // leave the shared notice to MediaMenuService
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
