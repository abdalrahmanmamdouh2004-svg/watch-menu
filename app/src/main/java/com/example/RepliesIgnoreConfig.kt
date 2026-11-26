package com.example

import android.content.Context

/** Separate ignore list used only by Watch Replies; it never changes the media Ignore list. */
object RepliesIgnoreConfig {
    data class AppGroup(val label: String, val packages: Set<String>)

    val groups = listOf(
        AppGroup("WhatsApp", setOf("com.whatsapp", "com.whatsapp.w4b")),
        AppGroup("Messenger", setOf("com.facebook.orca", "com.facebook.mlite")),
        AppGroup("Telegram", setOf("org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.plus"))
    )

    private const val PREFS = "watch_replies_ignore"
    private const val KEY_PACKAGES = "packages"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ignored(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_PACKAGES, emptySet()).orEmpty().toSet()

    fun isIgnored(context: Context, packageName: String): Boolean =
        packageName in ignored(context)

    fun setIgnored(context: Context, packages: Set<String>) {
        prefs(context).edit().putStringSet(KEY_PACKAGES, packages.toSet()).apply()
        Replies.refreshIgnored(context.applicationContext)
    }
}
