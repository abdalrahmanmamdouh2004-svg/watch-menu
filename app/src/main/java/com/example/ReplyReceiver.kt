package com.example

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReplyReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_NOTIFICATION_REPLY = "com.example.ACTION_NOTIFICATION_REPLY"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val pendingResult = goAsync()
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WatchMenu:ReplyReceiver").apply {
            acquire(10_000L)
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val bundle = RemoteInput.getResultsFromIntent(intent)
                val replyText = bundle?.getCharSequence("reply")?.toString() ?: "(empty)"

                val codePoints = replyText.codePoints()
                    .toArray()
                    .joinToString(" ") { String.format("U+%04X", it) }

                AppLogger.log("ReplyReceiver", "Received reply from watch: \"$replyText\" | Code points: [$codePoints]")

                // Re-post the same notification (ID 1001) with text "Got: <reply>" to stop watch spinner
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val person = Person.Builder().setName("Watch Menu").build()
                val updatedStyle = NotificationCompat.MessagingStyle(person)
                    .addMessage(
                        NotificationCompat.MessagingStyle.Message(
                            "Got: $replyText",
                            System.currentTimeMillis(),
                            person
                        )
                    )

                val updatedNotification = NotificationCompat.Builder(context, TestEngine.CHANNEL_ID_REPLY)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setStyle(updatedStyle)
                    .setContentTitle("Watch Menu")
                    .setContentText("Got: $replyText")
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setOngoing(false)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setAutoCancel(false)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build()

                nm.notify(TestEngine.NOTIFICATION_ID_REPLY, updatedNotification)
                AppLogger.log("ReplyReceiver", "Re-posted notification 1001 with 'Got: $replyText'")

                // Optional mapping: if "okay", trigger last pressed music test
                if (replyText.trim().equals("okay", ignoreCase = true)) {
                    AppLogger.log("ReplyReceiver", "Reply text is 'okay'! Triggering last music test...")
                    TestEngine.runLastMusicTest(context)
                }
            } catch (e: Exception) {
                AppLogger.logError("ReplyReceiver", "onReceive", e)
            } finally {
                try {
                    if (wakeLock.isHeld) {
                        wakeLock.release()
                    }
                } catch (_: Exception) {}
                pendingResult.finish()
            }
        }
    }
}
