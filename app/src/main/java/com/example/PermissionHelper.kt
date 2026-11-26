package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

data class PermissionStatusItem(
    val id: String,
    val name: String,
    val isGranted: Boolean,
    val openSettings: (Context) -> Unit
)

object PermissionHelper {

    fun getPermissions(context: Context): List<PermissionStatusItem> {
        val items = mutableListOf<PermissionStatusItem>()
        // Only permissions used by the retained bridge and phone features are shown.
        MenuStore.init(context)

        items.add(
            PermissionStatusItem(
                id = "location",
                name = "Location",
                isGranted = WeatherSync.hasLocation(context),
                openSettings = { ctx -> openAppDetails(ctx) }
            )
        )
        items.add(
            PermissionStatusItem(
                id = "contacts",
                name = "Contacts",
                isGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED,
                openSettings = { ctx -> openAppDetails(ctx) }
            )
        )
        items.add(
            PermissionStatusItem(
                id = "phone_state",
                name = "Phone state",
                isGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED,
                openSettings = { ctx -> openAppDetails(ctx) }
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scanGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            val connectGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            items.add(
                PermissionStatusItem(
                    id = "nearby_devices",
                    name = "Nearby devices",
                    isGranted = scanGranted && connectGranted,
                    openSettings = { ctx -> openAppDetails(ctx) }
                )
            )
        }

        // 1. Notifications
        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        items.add(
            PermissionStatusItem(
                id = "post_notifications",
                name = "Notifications",
                isGranted = notifGranted,
                openSettings = { ctx ->
                    try {
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        ctx.startActivity(intent)
                    } catch (e: Exception) {
                        openAppDetails(ctx)
                    }
                }
            )
        )

        // 2. Display over other apps (lets the app open other apps from the background)
        items.add(
            PermissionStatusItem(
                id = "draw_overlays",
                name = "Display over apps",
                isGranted = Settings.canDrawOverlays(context),
                openSettings = { ctx ->
                    try {
                        ctx.startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        )
                    } catch (e: Exception) {
                        openAppDetails(ctx)
                    }
                }
            )
        )

        // Notification listener access
        val notifListenerGranted = MediaListenerService.isListenerEnabled(context)
        items.add(
            PermissionStatusItem(
                id = "notification_listener",
                name = "Notification access",
                isGranted = notifListenerGranted,
                openSettings = { ctx ->
                    try {
                        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        ctx.startActivity(intent)
                    } catch (e: Exception) {
                        openAppDetails(ctx)
                    }
                }
            )
        )

        // Battery optimization
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryIgnored = pm.isIgnoringBatteryOptimizations(context.packageName)
        items.add(
            PermissionStatusItem(
                id = "battery_optimization",
                name = "Battery",
                isGranted = batteryIgnored,
                openSettings = { ctx ->
                    try {
                        val intent = Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${ctx.packageName}")
                        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        ctx.startActivity(intent)
                    } catch (e: Exception) {
                        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try {
                            ctx.startActivity(fallback)
                        } catch (e2: Exception) {
                            openAppDetails(ctx)
                        }
                    }
                }
            )
        )

        return items
    }

    fun openAppDetails(context: Context) {
        try {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
        } catch (e: Exception) {
            AppLogger.logError("Permissions", "openAppDetails", e)
        }
    }

    fun getManufacturerHint(): String {
        val m = Build.MANUFACTURER?.lowercase() ?: ""
        return when {
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") ->
                "Xiaomi/Redmi/Poco (MIUI/HyperOS): In App Info -> Other Permissions, allow 'Display pop-up windows while running in the background' and 'Show on Lock screen'. Set Battery saver to 'No restrictions' and enable Autostart."
            m.contains("oppo") || m.contains("realme") || m.contains("oneplus") ->
                "Oppo/Realme/OnePlus (ColorOS/OxygenOS): In App Info -> Battery usage, enable 'Allow background activity' & 'Allow auto-launch'. Allow 'Display over other apps'."
            m.contains("vivo") || m.contains("iqoo") ->
                "Vivo/iQOO (Funtouch/OriginOS): In App Info -> Single permission, enable 'Autostart' and 'Floating window'. Under Battery, set 'High background power consumption'."
            m.contains("huawei") || m.contains("honor") ->
                "Huawei/Honor (EMUI/MagicOS): In Settings -> Battery -> App launch, set this app to 'Manage manually' and enable Auto-launch, Secondary launch, and Run in background."
            m.contains("samsung") ->
                "Samsung (One UI): In Settings -> Battery -> Background usage limits, add this app to 'Never sleeping apps'. Under Apps -> Special access, enable 'Appear on top'."
            m.contains("nothing") ->
                "Nothing Phone (Nothing OS): Ensure Battery usage is set to 'Unrestricted' and 'Allow display over other apps' is granted in App Info."
            else ->
                "OEM Hint: On Xiaomi/Oppo/Vivo/Huawei/Samsung/Nothing, ensure 'Display pop-up windows in background', 'Autostart', and Unrestricted Battery are allowed."
        }
    }
}
