package com.example

import android.content.Context
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar

/** One of the five native alarm slots exposed by the CMF Watch Pro 2. */
data class WatchAlarm(
    val index: Int,
    val secondsOfDay: Int = 0,
    val enabled: Boolean = false,
    val repeatMask: Int = 0,
    val flag: Int = 0,
    val label: String = "",
    /** False for an unused id: the watch only stores alarms that exist. */
    val present: Boolean = true
) {
    val hour: Int get() = (secondsOfDay.coerceIn(0, 86_399) / 3600)
    val minute: Int get() = (secondsOfDay.coerceIn(0, 86_399) % 3600) / 60
    val isEmptySlot: Boolean get() = !present

    fun withTime(hour: Int, minute: Int): WatchAlarm = copy(
        secondsOfDay = hour.coerceIn(0, 23) * 3600 + minute.coerceIn(0, 59) * 60
    )
}

/**
 * CMF Watch Pro 2 alarm codec, based on the corrected 40-byte record layout:
 * secondsOfDay(u32 BE), slot, enabled, weekday mask, flag, UTF-8 label[32].
 * The weekday bit order is a working mapping (Sunday=bit 0) pending physical verification.
 */
object WatchAlarmCodec {
    const val SLOT_COUNT = 5
    const val PLACEHOLDER = ""
    const val RECORD_SIZE = 40
    private const val LABEL_SIZE = 32

    fun emptySlots(): List<WatchAlarm> = (0 until SLOT_COUNT).map { WatchAlarm(index = it, present = false) }

    /** The watch keeps a list: one 40-byte record per existing alarm, nothing for unused ids. */
    fun encode(alarms: List<WatchAlarm>): ByteArray {
        var list = alarms.filter { it.present }.sortedBy { it.index }.take(SLOT_COUNT)
        // The watch ignores an empty list, so "no alarms" is stored as one unnamed 12 AM alarm that is off.
        if (list.isEmpty()) list = listOf(WatchAlarm(0, 0, false, 0, 0, PLACEHOLDER))
        val out = ByteBuffer.allocate(list.size * RECORD_SIZE).order(ByteOrder.BIG_ENDIAN)
        list.forEach { alarm ->
            val label = truncateUtf8(alarm.label, LABEL_SIZE)
            out.putInt(alarm.secondsOfDay.coerceIn(0, 86_399))
            out.put(alarm.index.toByte())
            out.put(if (alarm.enabled) 1 else 0)
            out.put((alarm.repeatMask and 0x7F).toByte())
            out.put(alarm.flag.toByte())
            out.put(ByteArray(LABEL_SIZE).also { System.arraycopy(label, 0, it, 0, label.size) })
        }
        return out.array()
    }

    fun truncateLabel(value: String): String =
        String(truncateUtf8(value.replace("\u0000", ""), LABEL_SIZE), Charsets.UTF_8)

    private fun truncateUtf8(value: String, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream(maxBytes)
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            val bytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
            if (out.size() + bytes.size > maxBytes) break
            out.write(bytes)
            offset += Character.charCount(codePoint)
        }
        return out.toByteArray()
    }

    fun decode(payload: ByteArray): List<WatchAlarm> {
        if (payload.isEmpty()) return emptySlots()
        require(payload.size % RECORD_SIZE == 0) {
            "Alarm payload must contain complete 40-byte records (received ${payload.size} bytes)"
        }
        val result = emptySlots().toMutableList()
        val count = payload.size / RECORD_SIZE
        repeat(count) { record ->
            val offset = record * RECORD_SIZE
            // The watch reports the time little-endian (it accepts big-endian when written).
            val input = ByteBuffer.wrap(payload, offset, RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val seconds = input.int.coerceIn(0, 86_399)
            val slot = input.get().toInt() and 0xFF
            val enabled = input.get().toInt() != 0
            val repeatMask = input.get().toInt() and 0x7F
            val flag = input.get().toInt() and 0xFF
            val labelBytes = ByteArray(LABEL_SIZE)
            input.get(labelBytes)
            val label = String(labelBytes, Charsets.UTF_8).substringBefore('\u0000').trim()
            if (slot in 0 until SLOT_COUNT) {
                result[slot] = WatchAlarm(slot, seconds, enabled, repeatMask, flag, label,
                    present = !(seconds == 0 && !enabled && (label.isBlank() || label == "."))) // "." was used by an older version
            }
        }
        return result
    }

    /** Calendar weekday constants are mapped Sunday=bit 0 through Saturday=bit 6. */
    fun maskFromCalendarDays(days: Iterable<Int>): Int = days.fold(0) { mask, day ->
        if (day in Calendar.SUNDAY..Calendar.SATURDAY) mask or (1 shl (day - Calendar.SUNDAY)) else mask
    }

    fun calendarDaysFromMask(mask: Int): Set<Int> = (Calendar.SUNDAY..Calendar.SATURDAY)
        .filter { (mask and (1 shl (it - Calendar.SUNDAY))) != 0 }
        .toSet()
}

object WatchAlarmState {
    val alarms = MutableStateFlow(WatchAlarmCodec.emptySlots())
    val status = MutableStateFlow("Not loaded")
    val loading = MutableStateFlow(false)
    val saving = MutableStateFlow(false)
}

/** Puts alarms caught by AlarmRelayActivity onto the watch. */
object VoiceAlarmRelay {
    // ---- Voice alarms relayed by AlarmRelayActivity --------------------------------------
    private var pendingVoice: WatchAlarm? = null
    private var voiceBusy = false

    fun addVoiceAlarm(c: Context, hour: Int, minute: Int, label: String, mask: Int) {
        pendingVoice = WatchAlarm(0, hour * 3600 + minute * 60, true, mask, 0, label)
        if (!WatchLink.connectedNow()) {
            android.widget.Toast.makeText(c, "Watch not connected: the alarm will be added when it connects", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        flushVoice(c)
    }

    fun flushVoice(c: Context) {
        val want = pendingVoice ?: return
        if (voiceBusy || !WatchLink.connectedNow()) return
        voiceBusy = true
        WatchLink.getAlarms { current, message ->
            if (current == null) { voiceBusy = false; WatchAlarmState.status.value = message; return@getAlarms }
            val dup = current.any { it.present && it.secondsOfDay == want.secondsOfDay && it.enabled && it.repeatMask == want.repeatMask }
            val free = current.firstOrNull { !it.present }
            if (dup) { pendingVoice = null; voiceBusy = false; return@getAlarms }
            if (free == null) {
                pendingVoice = null; voiceBusy = false
                android.widget.Toast.makeText(c, "Watch alarms are full", android.widget.Toast.LENGTH_LONG).show()
                return@getAlarms
            }
            val updated = current.map { if (it.index == free.index) want.copy(index = free.index) else it }
            WatchLink.setAlarms(updated) { ok, result ->
                voiceBusy = false
                WatchAlarmState.status.value = result
                if (ok) pendingVoice = null
                android.widget.Toast.makeText(c, if (ok) "Alarm sent to watch" else "Watch alarm not saved", android.widget.Toast.LENGTH_SHORT).show()
                if (ok) WatchLink.getAlarms { _, r -> WatchAlarmState.status.value = r }
            }
        }
    }
}
