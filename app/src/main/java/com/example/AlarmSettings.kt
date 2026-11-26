package com.example

import android.app.TimePickerDialog
import android.content.Context
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar

private val alarmDays = listOf(
    Calendar.MONDAY to "M",
    Calendar.TUESDAY to "Tu",
    Calendar.WEDNESDAY to "W",
    Calendar.THURSDAY to "Th",
    Calendar.FRIDAY to "F",
    Calendar.SATURDAY to "Sa",
    Calendar.SUNDAY to "Su"
)

@Composable
fun AlarmsPage() {
    val context = LocalContext.current
    val connected by WatchLinkState.connected.collectAsState()
    val alarms by WatchAlarmState.alarms.collectAsState()
    val status by WatchAlarmState.status.collectAsState()
    val loading by WatchAlarmState.loading.collectAsState()
    val saving by WatchAlarmState.saving.collectAsState()

    var editor by remember { mutableStateOf<WatchAlarm?>(null) }
    var relay by remember { mutableStateOf(AlarmRelayActivity.isOn(context)) }
    var editorNew by remember { mutableStateOf(false) }

    LaunchedEffect(connected) {
        if (connected) WatchLink.getAlarms { _, result -> WatchAlarmState.status.value = result }
    }

    val configured = alarms.filter { it.present }.sortedBy { it.index }
    val freeSlot = alarms.firstOrNull { it.isEmptySlot }

    SGroup(
        {
            SRow(
                "Refresh",
                Icons.Filled.Refresh,
                value = when {
                    !connected -> "Disconnected"
                    loading -> "Loading"
                    saving -> "Saving"
                    else -> status
                },
                onClick = { if (connected) WatchLink.getAlarms { _, result -> WatchAlarmState.status.value = result } },
                chevron = false
            )
        }
    )
    Spacer(Modifier.height(12.dp))

    if (configured.isNotEmpty()) {
        SGroup(*configured.map { alarm ->
            @Composable {
                SRow(
                    if (alarm.label.isBlank() || alarm.label.equals("alarm", ignoreCase = true)) formatAlarmTime(context, alarm.hour, alarm.minute) else alarm.label,
                    onClick = { editorNew = false; editor = alarm },
                    chevron = false,
                    labelFont = NDot,
                    trailing = {
                        Switch(
                            checked = alarm.enabled,
                            onCheckedChange = { next ->
                                val updated = alarms.map { if (it.index == alarm.index) it.copy(enabled = next) else it }
                                saveWatchAlarms(context, updated)
                            },
                            enabled = connected,
                            colors = nSwitchColors()
                        )
                    }
                )
            }
        }.toTypedArray())
        Spacer(Modifier.height(8.dp))
    }

    if (connected && !loading && freeSlot != null) {
        SGroup({ SRow("Add alarm", Icons.Filled.Add, onClick = { editorNew = true; editor = freeSlot.copy(secondsOfDay = 7 * 3600, present = true) }, chevron = false) })
    }

    Spacer(Modifier.height(22.dp))
    SGroup({
        SSwitchRow("Catch voice alarms", relay, Icons.Filled.Mic) {
            relay = it; AlarmRelayActivity.setOn(context, it)
            if (it) Toast.makeText(context, "Next voice alarm: choose Watch Menu and Always", Toast.LENGTH_LONG).show()
        }
    })

    if (editor != null) {
        AlarmEditorDialog(
            initial = editor!!,
            isNew = editorNew,
            onDismiss = { editor = null },
            onSave = { changed ->
                val updated = alarms.map { if (it.index == changed.index) changed else it }
                saveWatchAlarms(context, updated)
                editor = null
            },
            onDelete = {
                val removed = WatchAlarm(index = editor!!.index, present = false)
                val updated = alarms.map { if (it.index == removed.index) removed else it }
                saveWatchAlarms(context, updated)
                editor = null
            }
        )
    }
}

@Composable
private fun AlarmEditorDialog(
    initial: WatchAlarm,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (WatchAlarm) -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    var hour by remember(initial.index) { mutableIntStateOf(initial.hour) }
    var minute by remember(initial.index) { mutableIntStateOf(initial.minute) }
    var label by remember(initial.index) { mutableStateOf(initial.label) }
    var repeatMask by remember(initial.index) { mutableIntStateOf(initial.repeatMask) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NColors.Card,
        title = { Text(if (isNew) "New alarm" else "Alarm", color = NColors.Text, fontFamily = NType, fontSize = 18.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    onClick = {
                        TimePickerDialog(
                            context,
                            { _, h, m -> hour = h; minute = m },
                            hour,
                            minute,
                            DateFormat.is24HourFormat(context)
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(formatAlarmTime(context, hour, minute), color = NColors.Text, fontFamily = NDot, fontSize = 30.sp)
                }
                NField(value = label, onValueChange = { label = WatchAlarmCodec.truncateLabel(it) }, label = "Label")
                Text("Repeat", color = NColors.Dim, fontFamily = NType, fontSize = 13.sp)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    alarmDays.forEach { (calendarDay, short) ->
                        val bit = 1 shl (calendarDay - Calendar.SUNDAY)
                        FilterChip(
                            selected = (repeatMask and bit) != 0,
                            onClick = { repeatMask = if ((repeatMask and bit) != 0) repeatMask and bit.inv() else repeatMask or bit },
                            label = { Text(short, fontFamily = NType) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(initial.copy(
                    secondsOfDay = hour * 3600 + minute * 60,
                    enabled = true,
                    repeatMask = repeatMask,
                    label = label.trim()
                ))
            }) { Text("Save", color = NColors.Text, fontFamily = NType) }
        },
        dismissButton = {
            Row {
                if (!isNew) {
                    TextButton(onClick = onDelete) { Text("Delete", color = NColors.Dim, fontFamily = NType) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel", color = NColors.Dim, fontFamily = NType) }
            }
        }
    )
}

private fun saveWatchAlarms(context: Context, alarms: List<WatchAlarm>) {
    WatchLink.setAlarms(alarms) { success, message ->
        WatchAlarmState.status.value = message
        if (!success) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            return@setAlarms
        }
        WatchLink.getAlarms { actual, readMessage ->
            fun key(a: WatchAlarm) = if (!a.present) listOf(a.index) else listOf(a.index, a.secondsOfDay, a.enabled, a.repeatMask, a.label)
            val expected = alarms.sortedBy { it.index }.map(::key)
            if (actual != null && actual.sortedBy { it.index }.map(::key) == expected) {
                WatchAlarmState.status.value = "Saved"
                Toast.makeText(context, "Saved to watch", Toast.LENGTH_SHORT).show()
            } else {
                WatchAlarmState.status.value = readMessage.ifBlank { "Alarm verification failed" }
                Toast.makeText(context, "Watch didn't confirm the alarm", Toast.LENGTH_LONG).show()
            }
        }
    }
}

private fun formatAlarmTime(context: Context, hour: Int, minute: Int): String {
    val calendar = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute) }
    return DateFormat.getTimeFormat(context).format(calendar.time)
}

private fun repeatLabel(mask: Int): String {
    if (mask == 0) return "Once"
    if ((mask and 0x7F) == 0x7F) return "Every day"
    val names = listOf(
        Calendar.MONDAY to "M",
        Calendar.TUESDAY to "Tu",
        Calendar.WEDNESDAY to "W",
        Calendar.THURSDAY to "Th",
        Calendar.FRIDAY to "F",
        Calendar.SATURDAY to "Sa",
        Calendar.SUNDAY to "Su"
    )
    return names.filter { (day, _) -> (mask and (1 shl (day - Calendar.SUNDAY))) != 0 }
        .joinToString(" ") { it.second }
}
