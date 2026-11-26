package com.example

import android.content.Context

/** Independent feature gates for watch-bound data/commands. All remain enabled by default
 * to preserve an existing installation until the user changes them in the Lab. */
enum class WatchFeature(val key: String, val label: String) {
    CALLS("calls", "Calls"),
    CONTACTS("contacts", "Contacts"),
    WEATHER("weather", "Weather"),
    MUSIC("music", "Music"),
    NOTIFICATIONS("notifications", "Notifications")
}

object WatchFeatureSwitches {
    private const val PREFS = "watch_feature_switches"
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context, feature: WatchFeature): Boolean =
        true

    fun setEnabled(context: Context, feature: WatchFeature, enabled: Boolean) {
        prefs(context).edit().putBoolean(feature.key, enabled).apply()
        AppLogger.log("Features", "${feature.label} ${if (enabled) "enabled" else "disabled"}")
    }

    fun all(context: Context): Map<WatchFeature, Boolean> =
        WatchFeature.entries.associateWith { isEnabled(context, it) }
}
