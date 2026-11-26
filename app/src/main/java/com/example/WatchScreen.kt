package com.example

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Connects WatchMenu to the watch after Nothing X has established the phone's BLE connection. */
@Composable
fun WatchScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val status by WatchLinkState.status.collectAsState()
    val connected by WatchLinkState.connected.collectAsState()
    val savedName by WatchLinkState.savedName.collectAsState()
    val ringing by FindPhone.ringing.collectAsState()

    var nothingXDevices by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var showResults by remember { mutableStateOf(false) }

    val permissionRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (WatchLink.hasPermission()) {
            nothingXDevices = FmcSharedConnection.connectedNothingXWatches(context)
        } else {
            WatchLinkState.status.value = "Bluetooth permission denied"
        }
    }

    LaunchedEffect(Unit) {
        WatchLink.initialize(context)
        FmcSharedConnection.setSelected(context, true)
        if (!WatchLink.hasPermission()) {
            permissionRequest.launch(WatchLink.neededPermissions())
        }
        while (true) {
            nothingXDevices = if (WatchLink.hasPermission()) {
                FmcSharedConnection.connectedNothingXWatches(context)
            } else {
                emptyList()
            }
            delay(1000)
        }
    }

    val nothingXDetected = nothingXDevices.isNotEmpty() || FmcSharedConnection.isConnected()
    // Direct mode remains available for the native alarm/legacy commands.
    val connectionPrerequisiteMet = nothingXDetected
    val displayDevices = remember(nothingXDevices) { nothingXDevices.distinctBy { it.second } }

    Column(Modifier.fillMaxSize()) {
        NTopBar(title = "Connect Watch", onBack = onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Pair your watch with the Nothing X app first, then connect it here.",
                color = NColors.Text, fontFamily = NType, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            NCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NButton(
                        text = "Find watch",
                        onClick = {
                            if (WatchLink.hasPermission()) {
                                nothingXDevices = FmcSharedConnection.connectedNothingXWatches(context)
                                showResults = true
                            } else {
                                permissionRequest.launch(WatchLink.neededPermissions())
                            }
                        },
                        enabled = connectionPrerequisiteMet,
                        primary = connectionPrerequisiteMet && !connected
                    )
                    NButton(
                        "Disconnect",
                        onClick = { FmcSharedConnection.disconnect() },
                        enabled = connectionPrerequisiteMet && connected
                    )
                }
                if (connected) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "CMF Watch Pro 2 is connected",
                        color = NColors.Green,
                        fontFamily = NType,
                        fontSize = 13.sp
                    )
                } else if (status == "Connection failed") {
                    Spacer(Modifier.height(6.dp))
                    Text("Connection failed", color = NColors.Red, fontFamily = NType, fontSize = 13.sp)
                }
            }

            if (showResults) {
                displayDevices.forEach { (name, address) ->
                    NCard {
                        Text(name, color = NColors.Text, fontFamily = NDot, fontSize = 18.sp)
                        Spacer(Modifier.height(6.dp))
                        NButton(
                            text = if (connected && savedName == name) "Connected" else "Connect",
                            onClick = { FmcSharedConnection.connect(context, address, name) },
                            enabled = !connected && connectionPrerequisiteMet,
                            primary = name.contains("CMF Watch Pro 2", true)
                        )
                    }
                }
            }

            if (ringing) {
                NCard {
                    Text("Phone is ringing", color = NColors.Text, fontFamily = NType, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    NButton("Stop ringing", { FindPhone.stop(context) }, primary = true)
                }
            }
        }
    }
}
