package com.example

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow

/** "Find my phone" from the watch: loud alarm at full volume until stopped or 60 s pass. */
object FindPhone {
    val ringing = MutableStateFlow(false)
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var oldVolume = -1
    private var stopper: Runnable? = null

    fun toggle(context: Context) = if (ringing.value) stop(context) else start(context)

    fun start(context: Context) {
        val app = context.applicationContext
        handler.post {
            if (ringing.value) return@post
            try {
                val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                oldVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
                am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                    )
                    setDataSource(app, uri)
                    isLooping = true
                    prepare()
                    start()
                }
                ringing.value = true
                AppLogger.log("FindPhone", "Ringing")
                val s = Runnable { stop(app) }
                stopper = s
                handler.postDelayed(s, 60_000)
            } catch (e: Exception) {
                AppLogger.logError("FindPhone", "start", e)
                stop(app)
            }
        }
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        handler.post {
            stopper?.let { handler.removeCallbacks(it) }
            stopper = null
            try { player?.stop() } catch (_: Exception) {}
            try { player?.release() } catch (_: Exception) {}
            player = null
            if (oldVolume >= 0) {
                try {
                    (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                        .setStreamVolume(AudioManager.STREAM_ALARM, oldVolume, 0)
                } catch (_: Exception) {}
                oldVolume = -1
            }
            if (ringing.value) AppLogger.log("FindPhone", "Stopped")
            ringing.value = false
        }
    }
}
