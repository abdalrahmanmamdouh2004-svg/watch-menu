package com.example

import android.app.NotificationManager
import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/** Knows whether a one-button toggle (torch, Do Not Disturb, ringer modes) is currently on. */
object ToggleState {
    @Volatile
    private var torchOn = false
    private var registered = false

    fun init(context: Context) {
        if (registered) return
        registered = true
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    torchOn = enabled
                }
            }, Handler(Looper.getMainLooper()))
        } catch (_: Exception) {
        }
    }

    fun isSwitch(kind: ToggleKind) =
        kind == ToggleKind.TORCH || kind == ToggleKind.DND || kind == ToggleKind.SILENT || kind == ToggleKind.VIBRATE

    fun isOn(context: Context, kind: ToggleKind): Boolean = try {
        when (kind) {
            ToggleKind.TORCH -> torchOn
            ToggleKind.DND -> {
                val f = (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).currentInterruptionFilter
                f != NotificationManager.INTERRUPTION_FILTER_ALL && f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
            }
            ToggleKind.SILENT -> (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).ringerMode == AudioManager.RINGER_MODE_SILENT
            ToggleKind.VIBRATE -> (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).ringerMode == AudioManager.RINGER_MODE_VIBRATE
            else -> false
        }
    } catch (_: Exception) {
        false
    }

    /** Name shown on the watch: it names the action the next press will do. */
    fun watchLabel(context: Context, group: MenuGroup, item: MenuItem): String {
        if (group.type == GroupType.MUSIC) {
            return if (MediaMenuConfig.showArtist(context)) item.name else item.name.substringBeforeLast(" - ", item.name).ifBlank { item.name }
        }
        if (group.type != GroupType.TOGGLES) return item.name
        val kind = ToggleKind.values().firstOrNull { it.name == item.value } ?: return item.name
        if (!isSwitch(kind)) return item.name
        return item.name + if (isOn(context, kind)) " off" else " on"
    }
}
