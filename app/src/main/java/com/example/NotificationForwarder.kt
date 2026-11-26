package com.example

import android.app.Notification
import android.content.Context
import android.os.PowerManager
import android.service.notification.StatusBarNotification
import android.text.Html
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Phone notifications -> watch (APP_NOTIFICATION 0065/0001).
 * Payload: icon(1) 0x00 when(u32 BE) titleLen(1) title(<=20 B) body(<=128 B).
 * Bodies are never written to the log.
 */
object NotificationForwarder {
    val status = MutableStateFlow("")

    private const val TITLE_MAX = 20
    private const val BODY_MAX = 128
    private const val QUEUE_MAX = 5
    private const val QUEUE_TTL_MS = 120_000L

    private fun prefs(c: Context) = c.getSharedPreferences("notif_forward", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, v: Boolean) {
        prefs(c).edit().putBoolean("enabled", v).apply()
        if (!v) clearQueue()
    }
    fun screenOffOnly(c: Context) = prefs(c).getBoolean("screen_off", false)
    fun setScreenOffOnly(c: Context, v: Boolean) = prefs(c).edit().putBoolean("screen_off", v).apply()
    fun hideBody(c: Context) = prefs(c).getBoolean("hide_body", false)
    fun setHideBody(c: Context, v: Boolean) = prefs(c).edit().putBoolean("hide_body", v).apply()
    fun skipSystem(c: Context) = prefs(c).getBoolean("skip_system", true)
    fun setSkipSystem(c: Context, v: Boolean) = prefs(c).edit().putBoolean("skip_system", v).apply()
    private val systemPkgs = setOf("android", "com.android.systemui", "com.android.settings", "com.android.providers.downloads",
        "com.google.android.packageinstaller", "com.android.packageinstaller", "com.google.android.gms", "com.android.vending",
        "com.google.android.apps.wellbeing", "com.android.phone", "com.android.bluetooth", "com.android.server.telecom")
    /** Notifications are deny-by-default: only packages explicitly added here are forwarded. */
    fun allowed(c: Context): Set<String> = prefs(c).getStringSet("allowed_packages", emptySet())?.toSet() ?: emptySet()
    fun setAllowed(c: Context, v: Set<String>) = prefs(c).edit().putStringSet("allowed_packages", v.toSet()).apply()

    // Kept only for reading old settings; the new UI no longer exposes a Skip list.
    fun excluded(c: Context): Set<String> = prefs(c).getStringSet("excluded", emptySet())?.toSet() ?: emptySet()
    fun setExcluded(c: Context, v: Set<String>) = prefs(c).edit().putStringSet("excluded", v.toSet()).apply()

    // The only icon codes known for this watch: WhatsApp 8, Telegram 12, Instagram 18, Gmail 27.
    // Every email app uses the envelope (Gmail) icon; anything unknown uses the neutral icon.
    private val emailPackages = setOf(
        "com.google.android.gm", "com.google.android.gm.lite", "com.samsung.android.email.provider",
        "com.android.email", "com.yahoo.mobile.client.android.mail", "ch.protonmail.android", "com.fsck.k9", "com.fsck.k9.material",
        "me.bluemail.mail", "com.my.mail", "com.github.cketti.k9", "com.fastmail.mobile", "eu.faircode.email", "net.thunderbird.android"
    )

    /** Icon codes of the CMF Watch Pro 2, found with the icon finder. */
    private val iconTable = mapOf(
        "com.whatsapp" to 8,
        "com.snapchat.android" to 9,
        "com.whatsapp.w4b" to 10,
        "org.telegram.messenger" to 12, "org.telegram.messenger.web" to 12,
        "com.facebook.orca" to 13, "com.facebook.mlite" to 13,
        "com.facebook.katana" to 17,
        "com.instagram.android" to 18, "com.instagram.barcelona" to 18,
        "com.zhiliaoapp.musically" to 19, "com.ss.android.ugc.trill" to 19,
        "com.discord" to 21,
        "com.skype.raider" to 23, "com.skype.m2" to 23,
        "com.facebook.lite" to 24,
        "com.twitter.android" to 25, "com.twitter.android.lite" to 25,
        "com.microsoft.office.outlook" to 29
    )

    fun iconFor(pkg: String): Int = when {
        pkg in iconTable -> iconTable.getValue(pkg)
        pkg.startsWith("org.thunderdog.challegram") -> 12
        pkg in emailPackages -> 27
        else -> iconOverride(pkg) ?: 0xFF
    }

    // Quick replies offered by the user (up to 6).
    const val QUICK_MAX = 6
    fun quickReplies(c: Context): List<String> =
        (prefs(c).getString("quick_replies", "") ?: "").split("\n").map { it.trim() }.filter { it.isNotEmpty() }.take(QUICK_MAX)
    fun setQuickReplies(c: Context, v: List<String>) =
        prefs(c).edit().putString("quick_replies", v.map { it.trim().replace("\n", " ") }.filter { it.isNotEmpty() }.take(QUICK_MAX).joinToString("\n")).apply()

    /** Icon codes the user has found with the icon finder, stored per app. */
    private var overrideCache: Map<String, Int>? = null
    private fun iconOverride(pkg: String): Int? = overrideCache?.get(pkg)
    fun setIconOverrides(c: Context) {
        val raw = prefs(c).getString("icon_overrides", "") ?: ""
        overrideCache = raw.split(";").mapNotNull { e ->
            val kv = e.split("=")
            if (kv.size == 2) kv[1].toIntOrNull()?.let { kv[0] to it } else null
        }.toMap()
    }

    /** Cuts to at most [max] bytes without splitting a UTF-8 character. */
    fun truncate(s: String, max: Int): ByteArray {
        val b = s.toByteArray(Charsets.UTF_8)
        if (b.size <= max) return b
        var end = max
        while (end > 0 && (b[end].toInt() and 0xC0) == 0x80) end--
        return b.copyOfRange(0, end)
    }

    fun payload(icon: Int, whenSec: Long, title: String, body: String): ByteArray {
        val t = truncate(title, TITLE_MAX)
        val bd = truncate(body, BODY_MAX)
        return ByteBuffer.allocate(2 + 4 + 1 + t.size + bd.size).order(ByteOrder.BIG_ENDIAN)
            .put(icon.toByte()).put(0).putInt(whenSec.toInt()).put(t.size.toByte()).put(t).put(bd).array()
    }

    private val lastSeen = LinkedHashMap<String, Int>()
    private class Queued(val payload: ByteArray, val at: Long)
    private val queue = ArrayDeque<Queued>()
    @Volatile private var appContext: Context? = null

    fun initialize(context: Context) { appContext = context.applicationContext; setIconOverrides(context) }

    private fun clean(cs: CharSequence?): String {
        val s = cs?.toString().orEmpty()
        return (if (s.contains('<')) Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString() else s).trim()
    }

    fun onPosted(c: Context, sbn: StatusBarNotification) {
        initialize(c)
        try {
            if (!enabled(c) || !WatchFeatureSwitches.isEnabled(c, WatchFeature.NOTIFICATIONS)) return
            val pkg = sbn.packageName
            if (pkg == c.packageName) return
            val n = sbn.notification ?: return
            if (n.category == Notification.CATEGORY_CALL) {
                val t = n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
                if (t.isNotBlank()) CallsManager.noteCallNotification(NumberNames.apply(c, t))
            }
            val isSystemNotification = pkg in systemPkgs || n.category == Notification.CATEGORY_SYSTEM ||
                n.category == Notification.CATEGORY_ERROR
            if (isSystemNotification) {
                if (skipSystem(c)) return
            } else if (pkg !in allowed(c)) {
                return
            }
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
            if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
            val ex = n.extras ?: return
            if (ex.getString(Notification.EXTRA_TEMPLATE).orEmpty().contains("MediaStyle")) return
            if (n.category == Notification.CATEGORY_TRANSPORT) return
            if (screenOffOnly(c) && (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive) return

            var title = clean(ex.getCharSequence(Notification.EXTRA_TITLE))
            var body = clean(ex.getCharSequence(Notification.EXTRA_BIG_TEXT)).ifBlank { clean(ex.getCharSequence(Notification.EXTRA_TEXT)) }
            // Chat apps: use the newest message and its sender.
            val msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
            (msgs?.lastOrNull() as? android.os.Bundle)?.let { m ->
                val text = clean(m.getCharSequence("text"))
                if (text.isNotBlank()) body = text
                val sender = m.getCharSequence("sender")?.toString()?.trim().orEmpty()
                if (title.isBlank() && sender.isNotBlank()) title = sender
            }
            if (title.isBlank()) title = try {
                c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) { pkg }
            if (title.isBlank() && body.isBlank()) return
            title = NumberNames.apply(c, title)
            if (hideBody(c)) body = "New message"

            val sig = (title + "\u0000" + body).hashCode()
            synchronized(lastSeen) {
                if (lastSeen[sbn.key] == sig) return
                lastSeen[sbn.key] = sig
                while (lastSeen.size > 200) lastSeen.remove(lastSeen.keys.first())
            }
            QuickReply.remember(sbn)
            // WATCHMENU_NOTIFICATIONS_LEFT_TO_NOTHING_X: Watch Replies has its own app channel.
        } catch (e: Exception) {
            AppLogger.logError("Notify", "onPosted", e)
        }
    }

    fun send(p: ByteArray) {
        val c = appContext
        if (c != null && (!enabled(c) || !WatchFeatureSwitches.isEnabled(c, WatchFeature.NOTIFICATIONS))) return
        // FMC_SHARED_NO_NATIVE_NOTIFICATION_QUEUE: chat forwarding is handled by Watch Replies' app channel.
        // Do not queue the separate native 0065 command while K-session mode is active.
        if (c != null && FmcSharedConnection.isSelected(c)) {
            status.value = "Native notification forwarding requires Direct mode"
            return
        }
        if (WatchLink.sendNotification(p)) {
            status.value = "Sent, waiting for the watch"
        } else {
            // Do not retain notifications while this feature is disabled; only queue for a missing link.
            synchronized(queue) {
                val now = System.currentTimeMillis()
                while (queue.isNotEmpty() && now - queue.first().at > QUEUE_TTL_MS) queue.removeFirst()
                if (queue.size >= QUEUE_MAX) queue.removeFirst()
                queue.addLast(Queued(p, now))
            }
            status.value = "Watch not connected - kept for 2 minutes"
        }
    }

    /** Called when the watch is connected again: delivers only recent messages. */
    fun clearQueue() {
        synchronized(queue) { queue.clear() }
    }

    fun flush() {
        // WATCHMENU_NOTIFICATIONS_LEFT_TO_NOTHING_X: discard any legacy queued native mirrors.
        clearQueue()
    }

    fun sendTest(title: String = "Test Message", body: String = "Notification forwarding is working!") =
        send(payload(0xFF, System.currentTimeMillis() / 1000, title, body))

    fun onAck() { status.value = "Acknowledged by the watch" }
}

/**
 * Quick reply: the watch sends back a reply text (opcode 0x9073); it is answered through the
 * reply action of the most recent notification that was forwarded to the watch.
 */
object QuickReply {
    private class Target(val pkg: String, val intent: android.app.PendingIntent, val inputs: Array<android.app.RemoteInput>, val at: Long)
    @Volatile private var last: Target? = null

    fun remember(sbn: StatusBarNotification) {
        val actions = sbn.notification?.actions ?: return
        val action = actions.firstOrNull { !it.remoteInputs.isNullOrEmpty() && it.actionIntent != null } ?: return
        val inputs = action.remoteInputs ?: return
        last = Target(sbn.packageName, action.actionIntent, inputs, System.currentTimeMillis())
    }

    /** Pulls readable text out of the watch's reply frame, whatever small header it has. */
    fun textFrom(p: ByteArray): String {
        if (p.isEmpty()) return ""
        var data = p
        val n0 = p[0].toInt() and 0xFF
        if (p.size > 1 && n0 == p.size - 1) data = p.copyOfRange(1, p.size)
        else if (p.size > 2 && (((p[0].toInt() and 0xFF) shl 8) or (p[1].toInt() and 0xFF)) == p.size - 2) data = p.copyOfRange(2, p.size)
        val text = String(data, Charsets.UTF_8)
        return text.filter { it.code >= 0x20 && it != '\uFFFD' }.trim()
    }

    fun onWatchReply(c: Context, p: ByteArray) {
        val text = textFrom(p)
        AppLogger.log("QuickReply", "Watch reply frame: ${p.joinToString("") { "%02x".format(it) }} -> \"$text\"")
        if (text.isBlank()) return
        val t = last
        if (t == null || System.currentTimeMillis() - t.at > 30 * 60_000L) {
            AppLogger.log("QuickReply", "No recent notification with a reply action to answer")
            return
        }
        try {
            val intent = android.content.Intent()
            val results = android.os.Bundle()
            t.inputs.forEach { results.putCharSequence(it.resultKey, text) }
            android.app.RemoteInput.addResultsToIntent(t.inputs, intent, results)
            t.intent.send(c, 0, intent)
            AppLogger.log("QuickReply", "Sent reply to ${t.pkg}")
        } catch (e: Exception) {
            AppLogger.logError("QuickReply", "send", e)
        }
    }
}
