package com.example

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private val needed = listOf("nearby_devices", "notification_listener", "post_notifications", "battery_optimization", "draw_overlays")

private fun iconFor(id: String): ImageVector = when (id) {
    "nearby_devices" -> Icons.Filled.Bluetooth
    "notification_listener", "post_notifications" -> Icons.Filled.Notifications
    "battery_optimization" -> Icons.Filled.BatteryFull
    "draw_overlays" -> Icons.Filled.Layers
    "location" -> Icons.Filled.LocationOn
    "contacts" -> Icons.Filled.Person
    "phone_state" -> Icons.Filled.Call
    else -> Icons.Filled.Security
}

/** Android 13+ blocks these for apps installed from a file until "Allow restricted settings" is chosen. */
private fun restrictedHint(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        "\n\nIf Android says the app isn't safe or the switch is gray: open App info for Watch Menu, tap the three dots, choose Allow restricted settings, then try again."
    else ""

private fun whyFor(id: String): String = when (id) {
    "nearby_devices" -> "Connects to your watch over Bluetooth."
    "notification_listener" -> "Sees your music player and sends phone notifications to the watch." + restrictedHint()
    "post_notifications" -> "Shows the small Watch Menu notification."
    "battery_optimization" -> "Keeps the menu working when the screen is off."
    "draw_overlays" -> "Lets the app open other apps from the background."
    "location" -> "Only used to get the weather where you are."
    "contacts" -> "Lets you pick contacts to send to the watch."
    "phone_state" -> "Lets the app see an incoming call and tell the watch."
    else -> "Needed by this feature."
}

private fun runtimePermissions(id: String): Array<String>? = when (id) {
    "location" -> arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    "contacts" -> arrayOf(Manifest.permission.READ_CONTACTS)
    "phone_state" -> arrayOf(Manifest.permission.READ_PHONE_STATE)
    "nearby_devices" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else null
    "post_notifications" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        arrayOf(Manifest.permission.POST_NOTIFICATIONS) else null
    else -> null
}

/** Permission rows in the settings style. Tap a row to allow it; tap ? to see why it is needed. */
@Composable
fun PermissionRows() {
    val context = LocalContext.current
    var permissions by remember { mutableStateOf(PermissionHelper.getPermissions(context)) }
    var why by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissions = PermissionHelper.getPermissions(context)
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) permissions = PermissionHelper.getPermissions(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    fun rows(list: List<PermissionStatusItem>): Array<@Composable () -> Unit> = list.map { p ->
        @Composable {
            SRow(
                p.name, iconFor(p.id), value = if (p.isGranted) "On" else "Off", chevron = false,
                onClick = {
                    if (!p.isGranted) {
                        val rt = runtimePermissions(p.id)
                        if (rt != null) launcher.launch(rt) else p.openSettings(context)
                    }
                },
                trailing = {
                    Spacer(Modifier.width(12.dp))
                    Text("?", color = NColors.Dim, fontFamily = NDot, fontSize = 20.sp,
                        modifier = Modifier.clickable { why = whyFor(p.id) }.padding(horizontal = 6.dp))
                }
            )
        }
    }.toTypedArray()

    // Only the permissions the app needs are listed; the optional ones were removed on purpose.
    val important = permissions.filter { it.id in needed }
    if (important.isNotEmpty()) {
        SSection("Needed")
        SGroup(*rows(important))
    }
    why?.let { text ->
        AlertDialog(
            onDismissRequest = { why = null },
            containerColor = NColors.Card,
            text = { Text(text, color = NColors.Text, fontFamily = NType, fontSize = 15.sp) },
            confirmButton = { TextButton(onClick = { why = null }) { Text("OK", color = NColors.Text, fontFamily = NType) } }
        )
    }
}

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        NPageHeader("Setup", null)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            PermissionRows()
            Spacer(Modifier.height(16.dp))
        }
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            NButton("Done", onClick = onDone, modifier = Modifier.fillMaxWidth(), primary = true)
        }
    }
}

object Onboarding {
    fun isDone(c: android.content.Context) = c.getSharedPreferences("app_ui", android.content.Context.MODE_PRIVATE).getBoolean("onboarding_done", false)
    fun setDone(c: android.content.Context) = c.getSharedPreferences("app_ui", android.content.Context.MODE_PRIVATE).edit().putBoolean("onboarding_done", true).apply()
}
