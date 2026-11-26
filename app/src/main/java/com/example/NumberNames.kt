package com.example

import android.content.Context
import org.json.JSONObject

/** Names for numbers that show up as caller or notification titles (WhatsApp, Messenger, ...). */
object NumberNames {
    private fun p(c: Context) = c.getSharedPreferences("number_names", Context.MODE_PRIVATE)
    private fun digits(s: String) = s.filter { it.isDigit() }
    private fun looksLikeNumber(s: String) = s.trim().let { t -> t.length >= 7 && t.count { it.isDigit() } >= t.length - 4 && t.all { it.isDigit() || it in "+ -()" } }

    fun all(c: Context): Map<String, String> {
        val o = try { JSONObject(p(c).getString("map", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        return o.keys().asSequence().associateWith { o.optString(it) }
    }

    fun set(c: Context, number: String, name: String) {
        val m = all(c).toMutableMap()
        val key = number.trim()
        if (key.isBlank()) return
        if (name.isBlank()) m.remove(key) else m[key] = name.trim()
        p(c).edit().putString("map", JSONObject(m as Map<*, *>).toString()).apply()
    }

    fun lookup(c: Context, number: String): String? {
        val d = digits(number)
        if (d.length < 5) return null
        return all(c).entries.firstOrNull { (k, _) ->
            val kd = digits(k)
            kd.length >= 5 && (kd == d || (minOf(kd.length, d.length) >= 8 && (kd.endsWith(d) || d.endsWith(kd))))
        }?.value
    }

    /** Remembers numbers we could not name, so they can be named later from the app. */
    fun seen(c: Context, number: String) {
        if (!looksLikeNumber(number) || lookup(c, number) != null) return
        val list = recent(c).toMutableList()
        list.remove(number.trim())
        list.add(0, number.trim())
        p(c).edit().putString("recent", list.take(12).joinToString("|")).apply()
    }

    fun recent(c: Context): List<String> =
        (p(c).getString("recent", "") ?: "").split("|").filter { it.isNotBlank() }.filter { lookup(c, it) == null }

    /** Replaces a number title with its name. */
    fun apply(c: Context, text: String): String {
        if (!looksLikeNumber(text)) {
            val t = text.trim()
            return all(c).entries.firstOrNull { it.key.trim().equals(t, true) }?.value ?: text
        }
        return lookup(c, text) ?: text.also { seen(c, it) }
    }
}
