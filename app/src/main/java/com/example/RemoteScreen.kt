package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * The phone side of Watch Remote: on/off, status, which PC, whether the watch app is open and a tile for every key of the watch (two pages).
 * Tap a key to try it; long-press a key to change what it does.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WatchRemoteScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val status by Remote.status.collectAsState()
    val host by Remote.hostName.collectAsState()
    val on by Remote.enabledFlow.collectAsState()
    var tick by remember { mutableIntStateOf(0) } // forces a redraw after a key changes
    var picking by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Int?>(null) } // the key whose action list is open

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) Remote.setEnabled(context, true)
    }
    fun turnOn() {
        if (Build.VERSION.SDK_INT >= 31 &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) permission.launch(Manifest.permission.BLUETOOTH_CONNECT) else Remote.setEnabled(context, true)
    }
    LaunchedEffectOnce { Remote.start(context) }
    BackHandler(enabled = picking) { picking = false }

    Column(Modifier.fillMaxSize()) {
        NTopBar(title = "Watch Remote", onBack = { if (picking) picking = false else onBack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SGroup(
                { SSwitchRow("Bluetooth keyboard/mouse", on) { if (it) turnOn() else Remote.setEnabled(context, false) } },
                { SRow("Status", null, value = status) },
                { SRow("PC", null, value = host ?: "none", onClick = if (on) ({ picking = !picking }) else null, chevron = false) },
                { SRow("Restart Bluetooth remote", null, onClick = if (on) ({ Remote.restart(context) }) else null, chevron = false) },
            )
            if (picking) {
                val devices = remember(picking) { Remote.bonded() }
                val deviceRows: List<@Composable () -> Unit> = if (devices.isEmpty()) {
                    listOf({ SRow("No paired devices", null) })
                } else {
                    devices.map { d ->
                        @Composable { SRow(d.name ?: d.address, null, onClick = { Remote.connectTo(context, d.address); picking = false }) }
                    }
                }
                SGroup(*deviceRows.toTypedArray())
            }

            Spacer(Modifier.height(20.dp))
            Spacer(Modifier.height(10.dp))
            val key: @Composable (Int, String, Modifier) -> Unit = { b, name, mod ->
                val a = remember(tick, editing) { Remote.action(context, b, 0) }
                Column(
                    mod
                        .clip(RoundedCornerShape(22.dp))
                        .background(NColors.Card)
                        .combinedClickable(onClick = { Remote.press(context, b, 0) }, onLongClick = { editing = b })
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(name, color = NColors.Text, fontFamily = NType, fontSize = 11.sp, textAlign = TextAlign.Center)
                    Text(a.label, color = NColors.Text, fontFamily = NDot, fontSize = 14.sp, textAlign = TextAlign.Center)
                }
            }
            val k = Modifier.size(width = 96.dp, height = 70.dp)
            val g = Arrangement.spacedBy(8.dp)
            Text("Page 1", color = NColors.Text, fontFamily = NType, fontSize = 15.sp, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = g) {
                key(1, "Up", k)
                Row(horizontalArrangement = g) { key(3, "Left", k); key(5, "Centre", k); key(4, "Right", k) }
                key(2, "Down", k)
            }
            Spacer(Modifier.height(20.dp))
            Text("Page 2", color = NColors.Text, fontFamily = NType, fontSize = 15.sp, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = g) {
                Row(horizontalArrangement = g) { key(10, "Back", k); key(11, "Home", k) }
                Row(horizontalArrangement = g) { key(13, "Previous", k); key(12, "Play / Pause", k); key(14, "Next", k) }
                Row(horizontalArrangement = g) { key(15, "Mute", k); key(20, "Volume up", k); key(21, "Volume down", k) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    editing?.let { b ->
        val name = Remote.BUTTONS.firstOrNull { it.first == b }?.second ?: ""
        val current = Remote.action(context, b, 0)
        Dialog(onDismissRequest = { editing = null }) {
            Column(Modifier.clip(RoundedCornerShape(26.dp)).background(NColors.Card).padding(vertical = 16.dp)) {
                Text(
                    "Change '$name'", color = NColors.Text, fontFamily = NType, fontSize = 18.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Remote.Act.values().filter { it != Remote.Act.NONE }.forEach { a ->
                        Row(
                            Modifier.fillMaxWidth()
                                .combinedClickable(onClick = { Remote.setKey(context, b, a); tick++; editing = null })
                                .padding(horizontal = 20.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(a.label, color = NColors.Text, fontFamily = NDot, fontSize = 16.sp, modifier = Modifier.weight(1f))
                            if (a == current) androidx.compose.material3.Icon(Icons.Filled.Check, "selected", tint = NColors.Red, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LaunchedEffectOnce(block: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { block() }
}
