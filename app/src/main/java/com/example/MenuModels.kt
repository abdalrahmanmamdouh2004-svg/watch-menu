package com.example

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class GroupType(val label: String) {
    MUSIC("Music"),
    PODCAST("Podcasts"),
    APPS("Apps"),
    TOGGLES("Phone toggles"),
    CUSTOM("Custom")
}

enum class ToggleKind(val label: String, val legacy: Boolean = false) {
    TORCH("Torch"),
    DND("Do Not Disturb"),
    SILENT("Silent"),
    VIBRATE("Vibrate"),
    TORCH_ON("Torch on", true),
    TORCH_OFF("Torch off", true),
    VOLUME_UP("Volume up"),
    VOLUME_DOWN("Volume down"),
    DND_ON("Do Not Disturb on", true),
    DND_OFF("Do Not Disturb off", true),
    RINGER_SILENT("Silent ringer", true),
    RINGER_VIBRATE("Vibrate ringer", true),
    RINGER_NORMAL("Normal ringer", true),
    LOCK_SCREEN("Lock the screen"),
    SCREENSHOT("Screenshot")
}

/** value = URL (music/custom), package name (apps) or ToggleKind name (toggles). */
data class MenuItem(
    val id: String = newId(),
    val name: String,
    val value: String,
    val silent: Boolean = false,
    /** Podcasts only: the last episode heard, and where it stopped. */
    val episode: String = "",
    /** Public RSS/Atom feed and ordered episode list for API-free podcast tracking. */
    val podcastFeedUrl: String = "",
    val podcastEpisodes: String = "",
    val podcastIndex: Int = -1,
    /** Current episode URL used for playback; updated when the queue advances. */
    val podcastCurrentUrl: String = "",
    /** Original episode share URL used to identify the preferred playback app. */
    val podcastPlayerHint: String = "",
    /** Last known podcast playback position; deliberately independent of other music sessions. */
    val podcastPositionMs: Long = 0L,
    val podcastDurationMs: Long = 0L
)

data class MenuGroup(
    val id: String = newId(),
    val type: GroupType,
    val name: String,
    val items: List<MenuItem> = emptyList(),
    /** Keep playback advancing through only this group's saved items when enabled. */
    val stayInGroup: Boolean = false,
    /** Restart at the first item after the last item completes. */
    val repeatGroup: Boolean = false
)

fun newId(): String = UUID.randomUUID().toString()

/** All groups, persisted as JSON in SharedPreferences. Shared by the UI and the media service. */
object MenuStore {
    private const val PREFS = "menu_config"
    private const val KEY = "groups"

    private val _groups = MutableStateFlow<List<MenuGroup>>(emptyList())
    val groups: StateFlow<List<MenuGroup>> = _groups.asStateFlow()

    @Volatile
    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val raw = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, "[]") ?: "[]"
            val cleaned = parse(raw).filter { it.type == GroupType.MUSIC || it.type == GroupType.PODCAST || it.type == GroupType.CUSTOM }
            _groups.value = cleaned
            if (cleaned.size != parse(raw).size) {
                context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY, toJson(cleaned)).apply()
            }
            loaded = true
        }
    }

    fun update(context: Context, transform: (List<MenuGroup>) -> List<MenuGroup>) {
        init(context)
        val next = transform(_groups.value).filter { it.type == GroupType.MUSIC || it.type == GroupType.PODCAST || it.type == GroupType.CUSTOM }
        _groups.value = next
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, toJson(next)).apply()
    }

    /** Re-read the saved groups (used after an import). */
    fun reload(context: Context) {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val parsed = parse(raw)
        val cleaned = parsed.filter { it.type == GroupType.MUSIC || it.type == GroupType.PODCAST || it.type == GroupType.CUSTOM }
        _groups.value = cleaned
        if (cleaned.size != parsed.size) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, toJson(cleaned)).apply()
        }
        loaded = true
    }

    fun group(id: String): MenuGroup? = _groups.value.firstOrNull { it.id == id }

    private const val KEY_SELECTED_GROUP = "last_selected_group_id"
    private const val KEY_SELECTED_ITEM = "last_selected_item_id"

    fun saveSelection(context: Context, groupId: String, itemId: String) {
        if (groupId.isBlank()) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SELECTED_GROUP, groupId)
            .putString(KEY_SELECTED_ITEM, itemId)
            .apply()
    }

    fun lastSelectedGroupId(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_GROUP, "") ?: ""

    fun lastSelectedItemId(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_ITEM, "") ?: ""

    private fun toJson(list: List<MenuGroup>): String {
        val arr = JSONArray()
        for (g in list) {
            val items = JSONArray()
            for (it in g.items) {
                items.put(
                    JSONObject()
                        .put("id", it.id).put("name", it.name)
                        .put("value", it.value).put("silent", it.silent)
                        .put("episode", it.episode)
                        .put("podcastFeedUrl", it.podcastFeedUrl)
                        .put("podcastEpisodes", it.podcastEpisodes)
                        .put("podcastIndex", it.podcastIndex)
                        .put("podcastCurrentUrl", it.podcastCurrentUrl)
                        .put("podcastPlayerHint", it.podcastPlayerHint)
                        .put("podcastPositionMs", it.podcastPositionMs)
                        .put("podcastDurationMs", it.podcastDurationMs)
                )
            }
            arr.put(
                JSONObject()
                    .put("id", g.id).put("type", g.type.name)
                    .put("name", g.name).put("items", items)
                    .put("stayInGroup", g.stayInGroup)
                    .put("repeatGroup", g.repeatGroup)
            )
        }
        return arr.toString()
    }

    private fun parse(raw: String): List<MenuGroup> {
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { gi ->
                try {
                    val o = arr.getJSONObject(gi)
                    val itemsArr = o.optJSONArray("items") ?: JSONArray()
                    val items = (0 until itemsArr.length()).map { ii ->
                        val io = itemsArr.getJSONObject(ii)
                        MenuItem(
                            id = io.optString("id", newId()),
                            name = io.optString("name"),
                            value = io.optString("value"),
                            silent = io.optBoolean("silent", false),
                            episode = io.optString("episode"),
                            podcastFeedUrl = io.optString("podcastFeedUrl"),
                            podcastEpisodes = io.optString("podcastEpisodes"),
                            podcastIndex = io.optInt("podcastIndex", -1),
                            podcastCurrentUrl = io.optString("podcastCurrentUrl"),
                            podcastPlayerHint = io.optString("podcastPlayerHint"),
                            podcastPositionMs = io.optLong("podcastPositionMs", 0L),
                            podcastDurationMs = io.optLong("podcastDurationMs", 0L)
                        )
                    }
                    MenuGroup(
                        id = o.optString("id", newId()),
                        type = GroupType.valueOf(o.getString("type")),
                        name = o.optString("name", "Group"),
                        items = items,
                        stayInGroup = o.optBoolean("stayInGroup", false),
                        repeatGroup = o.optBoolean("repeatGroup", false)
                    )
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
