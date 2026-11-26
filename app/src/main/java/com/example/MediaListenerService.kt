package com.example

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat

class MediaListenerService : NotificationListenerService() {

    companion object {
        @Volatile
        var instance: MediaListenerService? = null
        @Volatile
        var isConnected = false
            private set
        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context, MediaListenerService::class.java)
        }

        fun isListenerEnabled(context: Context): Boolean {
            return try {
                NotificationManagerCompat.getEnabledListenerPackages(context)
                    .contains(context.packageName)
            } catch (e: Exception) {
                AppLogger.logError("MediaListener", "checkListenerEnabled", e)
                false
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        instance = this
        MediaMenuService.instance?.reattachObservers()
        AppLogger.log("MediaListener", "Listener connected. MediaSessionManager.getActiveSessions is now authorized.")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        instance = null
        AppLogger.log("MediaListener", "Listener disconnected.")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) {
            NotificationForwarder.onPosted(applicationContext, sbn)
            Replies.onPosted(applicationContext, sbn)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null) Replies.onRemoved(sbn)
    }
}
