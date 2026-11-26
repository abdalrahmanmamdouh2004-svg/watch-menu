package com.example

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Export and import of groups, passcodes and settings as one JSON file. */
object Backup {
    private val PREFS = listOf("menu_config", "media_menu", "watch_link")

    fun export(context: Context): String {
        val root = JSONObject()
        for (name in PREFS) {
            val o = JSONObject()
            for ((k, v) in context.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
                val e = JSONObject()
                when (v) {
                    is Boolean -> e.put("t", "b").put("v", v)
                    is Int -> e.put("t", "i").put("v", v)
                    is Long -> e.put("t", "l").put("v", v)
                    is Float -> e.put("t", "f").put("v", v.toDouble())
                    is Set<*> -> e.put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                    else -> e.put("t", "s").put("v", v?.toString() ?: "")
                }
                o.put(k, e)
            }
            root.put(name, o)
        }
        return root.toString(2)
    }

    fun import(context: Context, text: String): String {
        return try {
            val root = JSONObject(text)
            var n = 0
            for (name in PREFS) {
                val o = root.optJSONObject(name) ?: continue
                val ed = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
                for (k in o.keys()) {
                    val e = o.getJSONObject(k)
                    when (e.getString("t")) {
                        "b" -> ed.putBoolean(k, e.getBoolean("v"))
                        "i" -> ed.putInt(k, e.getInt("v"))
                        "l" -> ed.putLong(k, e.getLong("v"))
                        "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                        "set" -> {
                            val a = e.getJSONArray("v")
                            ed.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet())
                        }
                        else -> ed.putString(k, e.getString("v"))
                    }
                    n++
                }
                ed.commit()
            }
            MenuStore.reload(context)
            MediaMenuService.instance?.reapplySettings()
            "SUCCESS: imported $n settings. Reopen the app to see everything."
        } catch (e: Exception) {
            "FAIL: ${e.javaClass.simpleName}: ${e.message}"
        }
    }
}
