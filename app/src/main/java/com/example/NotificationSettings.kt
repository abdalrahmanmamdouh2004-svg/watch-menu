package com.example

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@Composable
fun WeatherPage() {
    val context: Context = LocalContext.current
    var celsius by remember { mutableStateOf(WeatherSync.celsiusNumbers(context)) }
    var useLocation by remember { mutableStateOf(WeatherSync.useLocation(context)) }
    var locPermission by remember { mutableStateOf(WeatherSync.hasLocation(context)) }
    var cityQuery by remember { mutableStateOf(WeatherSync.city(context)) }
    var results by remember { mutableStateOf<List<CitySearchResult>>(emptyList()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        locPermission = WeatherSync.hasLocation(context)
        if (locPermission) {
            WeatherSync.setUseLocation(context, true)
            useLocation = true
            WeatherSync.sendLive(context)
        } else {
            WeatherSync.setUseLocation(context, false)
            useLocation = false
        }
    }

    var detected by remember { mutableStateOf(WeatherSync.detectedCity(context)) }
    LaunchedEffect(useLocation, locPermission) {
        if (useLocation) {
            val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { WeatherSync.detectCity(context) }
            if (!found.isNullOrBlank()) detected = found
        }
    }

    LaunchedEffect(cityQuery) {
        if (cityQuery.trim().length < 2 || cityQuery == WeatherSync.city(context)) {
            results = emptyList()
        } else {
            delay(400)
            results = WeatherSync.searchCities(cityQuery)
        }
    }

    SGroup(
        { SSwitchRow("Celsius", celsius) { celsius = it; WeatherSync.setCelsiusNumbers(context, it) } },
        { SSwitchRow("Use current location", useLocation) { next ->
            if (next && !WeatherSync.hasLocation(context)) {
                ask.launch(arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.ACCESS_FINE_LOCATION))
            } else {
                useLocation = next
                WeatherSync.setUseLocation(context, next)
                WeatherSync.sendLive(context)
            }
        } },
        { SRow("Refresh", onClick = { WeatherSync.sendLive(context) }, chevron = false) }
    )
    Spacer(Modifier.height(14.dp))
    if (useLocation) {
        NField(value = detected.ifBlank { "Finding your city" }, onValueChange = {}, label = "City", enabled = false)
    } else {
        NField(value = cityQuery, onValueChange = { cityQuery = it }, label = "City")
    }
    if (!useLocation && results.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        SGroup(*(results.map { result ->
            @Composable {
                val description = listOf(result.name, result.admin1, result.country).filter { it.isNotBlank() }.joinToString(", ")
                SRow(description, onClick = {
                    WeatherSync.setCity(context, result.name)
                    WeatherSync.setCountry(context, result.country)
                    WeatherSync.setCoordinates(context, result.latitude, result.longitude)
                    WeatherSync.setTimezone(context, result.timezone)
                    WeatherSync.setUseLocation(context, false)
                    useLocation = false
                    cityQuery = result.name
                    results = emptyList()
                    WeatherSync.sendLive(context)
                }, chevron = false)
            }
        }).toTypedArray())
    }
}

@Composable
fun NotificationPage(launchable: List<LaunchableApp>) {
    val context: Context = LocalContext.current
    NotificationForwarder.initialize(context)
    var on by remember { mutableStateOf(NotificationForwarder.enabled(context)) }
    var screenOff by remember { mutableStateOf(NotificationForwarder.screenOffOnly(context)) }
    var systemAllowed by remember { mutableStateOf(!NotificationForwarder.skipSystem(context)) }
    var hide by remember { mutableStateOf(NotificationForwarder.hideBody(context)) }
    var allowed by remember { mutableStateOf(NotificationForwarder.allowed(context)) }
    var access by remember { mutableStateOf(MediaListenerService.isListenerEnabled(context)) }
    var showPicker by remember { mutableStateOf(false) }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) access = MediaListenerService.isListenerEnabled(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    SGroup(
        { SSwitchRow("Send notifications", on) { on = it; NotificationForwarder.setEnabled(context, it) } },
        { SSwitchRow("Screen off only", screenOff) { screenOff = it; NotificationForwarder.setScreenOffOnly(context, it) } },
        { SSwitchRow("Allow system notifications", systemAllowed) { systemAllowed = it; NotificationForwarder.setSkipSystem(context, !it) } },
        { SSwitchRow("Hide message text", hide) { hide = it; NotificationForwarder.setHideBody(context, it) } },
        {
            SRow("Notification access", value = if (access) "On" else "Off", chevron = false, onClick = {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            })
        }
    )
    SSection("Allowed apps")
    val allowedRows = allowed.sorted().map { pkg ->
        @Composable {
            val label = launchable.firstOrNull { it.packageName == pkg }?.label ?: pkg
            SRow(label, chevron = false, onClick = {
                allowed = allowed - pkg
                NotificationForwarder.setAllowed(context, allowed)
            }) { Icon(Icons.Filled.Close, null, tint = NColors.Dim) }
        }
    }
    SGroup(*(allowedRows + @Composable { SRow("Add", Icons.Filled.Add, onClick = { showPicker = true }, chevron = false) }).toTypedArray())

    var quick by remember { mutableStateOf(NotificationForwarder.quickReplies(context)) }
    var quickAdd by remember { mutableStateOf(false) }
    SSection("Quick replies")
    val quickRows = quick.map { text ->
        @Composable {
            SRow(text, chevron = false, onClick = {
                quick = quick - text
                NotificationForwarder.setQuickReplies(context, quick)
            }) { Icon(Icons.Filled.Close, null, tint = NColors.Dim) }
        }
    }
    SGroup(*(quickRows + (if (quick.size < NotificationForwarder.QUICK_MAX) listOf<@Composable () -> Unit>(
        { SRow("Add", Icons.Filled.Add, onClick = { quickAdd = true }, chevron = false) }
    ) else emptyList())).toTypedArray())
    if (quickAdd) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { quickAdd = false },
            containerColor = NColors.Card,
            text = { NField(value = text, onValueChange = { text = it.take(40) }, label = "Reply") },
            confirmButton = {
                TextButton(onClick = {
                    if (text.isNotBlank()) { quick = quick + text.trim(); NotificationForwarder.setQuickReplies(context, quick) }
                    quickAdd = false
                }) { Text("Add", color = NColors.Text, fontFamily = NDot) }
            }
        )
    }

    if (showPicker) {
        var query by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = { TextButton(onClick = { showPicker = false }) { Text("Done", color = NColors.Text, fontFamily = NType) } },
            containerColor = NColors.Card,
            text = {
                Column {
                    NField(value = query, onValueChange = { query = it }, label = "Search apps")
                    LazyColumn(Modifier.height(360.dp)) {
                        items(launchable.filter { it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }, key = { it.packageName }) { app ->
                            val checked = app.packageName in allowed
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    allowed = if (checked) allowed - app.packageName else allowed + app.packageName
                                    NotificationForwarder.setAllowed(context, allowed)
                                },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null, colors = nCheckboxColors())
                                Spacer(Modifier.width(8.dp))
                                Text(app.label, color = NColors.Text, fontFamily = NType, fontSize = 14.sp, modifier = Modifier.padding(vertical = 10.dp))
                            }
                        }
                    }
                }
            }
        )
    }
}
