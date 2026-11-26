package com.example

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.AlarmClock
import android.widget.Toast

/**
 * Optional: catches "set alarm" requests (voice assistant), passes them on to the real
 * Clock app so the phone still gets the alarm, and also puts the alarm on the watch.
 * Disabled until the user turns it on in the Alarms test section.
 */
class AlarmRelayActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val src = intent
        try {
            if (src?.action == AlarmClock.ACTION_SET_ALARM) {
                // 1) hand it to the real Clock app
                val probe = Intent(AlarmClock.ACTION_SET_ALARM)
                val clock = packageManager.queryIntentActivities(probe, 0)
                    .map { it.activityInfo }
                    .firstOrNull { it.packageName != packageName }
                if (clock != null) {
                    val fwd = Intent(src).apply {
                        component = ComponentName(clock.packageName, clock.name)
                        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    }
                    try { startActivity(fwd) } catch (e: Exception) {
                        AppLogger.logError("AlarmRelay", "forward to clock", e)
                    }
                } else {
                    Toast.makeText(this, "No clock app found to set the phone alarm", Toast.LENGTH_LONG).show()
                }
                // 2) put it on the watch
                val h = src.getIntExtra(AlarmClock.EXTRA_HOUR, -1)
                val m = src.getIntExtra(AlarmClock.EXTRA_MINUTES, 0)
                if (h in 0..23 && m in 0..59) {
                    val days = src.getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS) ?: arrayListOf()
                    val label = WatchAlarmCodec.truncateLabel(
                        src.getStringExtra(AlarmClock.EXTRA_MESSAGE).orEmpty())
                    VoiceAlarmRelay.addVoiceAlarm(this, h, m, label, WatchAlarmCodec.maskFromCalendarDays(days))
                }
            }
        } catch (e: Exception) {
            AppLogger.logError("AlarmRelay", "relay", e)
        }
        finish()
    }

    companion object {
        fun isOn(c: Context): Boolean {
            val st = c.packageManager.getComponentEnabledSetting(ComponentName(c, AlarmRelayActivity::class.java))
            return st == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        fun setOn(c: Context, on: Boolean) {
            c.packageManager.setComponentEnabledSetting(
                ComponentName(c, AlarmRelayActivity::class.java),
                if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
    }
}
