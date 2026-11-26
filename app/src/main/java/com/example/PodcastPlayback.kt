package com.example

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Keeps the selected podcast active and asks before moving to the next RSS episode. */
object PodcastPlayback {
    private const val PREFS = "podcast_playback"
    private const val KEY_ACTIVE_ID = "active_item_id"
    private const val KEY_PENDING_ID = "pending_item_id"
    private const val KEY_PENDING_INDEX = "pending_episode_index"
    private const val CHANNEL_ID = "podcast_episode_queue"
    private const val NOTIFICATION_BASE = 7200
    private const val EXTRA_ACTION = "podcast_action"
    private const val EXTRA_ITEM_ID = "podcast_item_id"

    const val ACTION_PLAY = "com.example.watchmenu.PODCAST_PLAY"
    const val ACTION_SKIP = "com.example.watchmenu.PODCAST_SKIP"
    const val ACTION_TIMEOUT = "com.example.watchmenu.PODCAST_TIMEOUT"

    fun setActive(context: Context, itemId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        cancelPending(context, prefs.getString(KEY_PENDING_ID, null))
        prefs.edit().putString(KEY_ACTIVE_ID, itemId).remove(KEY_PENDING_ID).remove(KEY_PENDING_INDEX).apply()
    }

    fun activeItemId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ACTIVE_ID, null)

    fun clearActive(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        cancelPending(context, prefs.getString(KEY_PENDING_ID, null))
        prefs.edit().remove(KEY_ACTIVE_ID).remove(KEY_PENDING_ID).remove(KEY_PENDING_INDEX).apply()
    }

    private fun cancelPending(context: Context, pendingId: String?) {
        if (pendingId.isNullOrBlank()) return
        runCatching {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarm.cancel(decisionPendingIntent(context, ACTION_TIMEOUT, pendingId, 100))
        }
        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(notificationId(pendingId))
        }
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun dueForRefresh(context: Context, itemId: String): Boolean =
        System.currentTimeMillis() - context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("refreshed_$itemId", 0L) > DAY_MS

    /** Loads the feed again and adds new episodes after the ones we have. Returns the updated item. */
    suspend fun refresh(context: Context, itemId: String): MenuItem? {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("refreshed_$itemId", System.currentTimeMillis()).apply()
        var item = findItem(itemId) ?: return null
        val savedEpisodes = PodcastFeed.decodeEpisodes(item.podcastEpisodes)
        if (item.podcastFeedUrl.isNotBlank()) {
            val loaded = PodcastFeed.load(item.podcastFeedUrl).feed?.episodes.orEmpty()
            // Episodes we already trimmed away must not come back: ignore anything older than the oldest one we keep.
            val oldestKept = savedEpisodes.filter { it.publishedAt > 0L }.minOfOrNull { it.publishedAt } ?: 0L
            val refreshed = if (oldestKept > 0L) loaded.filter { it.publishedAt <= 0L || it.publishedAt >= oldestKept } else loaded
            if (refreshed.isNotEmpty()) {
                val limit = MediaMenuConfig.podcastLoadCount(context)
                val mergedAll = PodcastFeed.mergeEpisodes(savedEpisodes, refreshed)
                val merged = mergedAll.take(maxOf(item.podcastIndex + 1, item.podcastIndex + limit).coerceAtLeast(1))
                if (merged != savedEpisodes) {
                    val current = savedEpisodes.getOrNull(item.podcastIndex)
                    val currentIndex = if (current == null) item.podcastIndex else merged.indexOfFirst { episode ->
                        (current.guid.isNotBlank() && episode.guid == current.guid) ||
                            (current.link.isNotBlank() && normalizeEpisodeLink(current.link) == normalizeEpisodeLink(episode.link)) ||
                            (current.title == episode.title && current.publishedAt == episode.publishedAt)
                    }.takeIf { it >= 0 } ?: item.podcastIndex
                    val updated = item.copy(
                        podcastEpisodes = PodcastFeed.encodeEpisodes(merged),
                        podcastIndex = currentIndex
                    )
                    MenuStore.update(context) { list ->
                        list.map { g -> if (g.items.any { it.id == itemId }) g.copy(items = g.items.map { if (it.id == itemId) updated else it }) else g }
                    }
                    item = updated
                    AppLogger.log("Podcast", "Refreshed ${item.name}: ${merged.size} episodes in queue")
                }
            }
        }
        return item
    }

    suspend fun offerNext(context: Context, itemId: String) {
        val item = refresh(context, itemId) ?: findItem(itemId) ?: return
        val episodes = PodcastFeed.decodeEpisodes(item.podcastEpisodes)
        val nextIndex = item.podcastIndex + 1
        val next = episodes.getOrNull(nextIndex) ?: run {
            AppLogger.log("Podcast", "No next episode found for ${item.name}")
            return
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_PENDING_ID, null) == itemId && prefs.getInt(KEY_PENDING_INDEX, -1) == nextIndex) return
        prefs.edit().putString(KEY_PENDING_ID, itemId).putInt(KEY_PENDING_INDEX, nextIndex).apply()

        val timeoutIntent = decisionPendingIntent(context, ACTION_TIMEOUT, itemId, 100)

        // No notification any more: the app only shows its one quiet "running" notice. The episode still advances by itself.

        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            alarm.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 60_000L,
                timeoutIntent
            )
        } catch (e: Exception) {
            AppLogger.logError("Podcast", "schedule auto-advance", e)
        }
        AppLogger.log("Podcast", "Next episode decision shown for ${item.name}: ${next.title}")
    }

    /** Plays the episode at [index] and remembers it as the current one. */
    suspend fun playEpisode(context: Context, itemId: String, index: Int) {
        val current = findItem(itemId) ?: return
        val group = MenuStore.groups.value.firstOrNull { g -> g.items.any { it.id == itemId } } ?: return
        val episode = PodcastFeed.decodeEpisodes(current.podcastEpisodes).getOrNull(index) ?: return
        val updated = current.copy(
            episode = episode.title,
            podcastIndex = index,
            podcastCurrentUrl = episode.link.ifBlank { episode.audioUrl },
            podcastPositionMs = 0L,
            podcastDurationMs = 0L
        )
        MenuStore.update(context) { list ->
            list.map { g -> if (g.id == group.id) g.copy(items = g.items.map { if (it.id == itemId) updated else it }) else g }
        }
        setActive(context, itemId)
        val updatedGroup = MenuStore.groups.value.firstOrNull { it.id == group.id } ?: group
        val result = ActionRunner.run(context.applicationContext, updatedGroup, updated)
        if (!result.startsWith("SUCCESS")) {
            MenuStore.update(context) { list ->
                list.map { g -> if (g.id == group.id) g.copy(items = g.items.map { if (it.id == itemId) current else it }) else g }
            }
            AppLogger.log("Podcast", "Could not open ${episode.title}: $result")
        }
    }

    internal suspend fun decide(context: Context, action: String, itemId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_PENDING_ID, null) != itemId) return
        val pendingIndex = prefs.getInt(KEY_PENDING_INDEX, -1)
        if (pendingIndex < 0) return

        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { alarm.cancel(decisionPendingIntent(context, ACTION_TIMEOUT, itemId, 100)) }
        runCatching { (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(notificationId(itemId)) }
        prefs.edit().remove(KEY_PENDING_ID).remove(KEY_PENDING_INDEX).apply()

        val current = findItem(itemId) ?: return
        val group = MenuStore.groups.value.firstOrNull { g -> g.items.any { it.id == itemId } } ?: return
        val episodes = PodcastFeed.decodeEpisodes(current.podcastEpisodes)
        // Skip means skip the episode in the notification and play the one after it.
        val chosenIndex = pendingIndex + if (action == ACTION_SKIP) 1 else 0
        val episode = episodes.getOrNull(chosenIndex)
        if (episode == null) {
            AppLogger.log("Podcast", "No more episodes after ${current.name}")
            return
        }

        val updated = current.copy(
            episode = episode.title,
            podcastIndex = chosenIndex,
            podcastCurrentUrl = episode.link.ifBlank { episode.audioUrl },
            podcastPositionMs = 0L,
            podcastDurationMs = 0L
        )
        MenuStore.update(context) { list ->
            list.map { g -> if (g.id == group.id) g.copy(items = g.items.map { if (it.id == itemId) updated else it }) else g }
        }
        setActive(context, itemId)
        val updatedGroup = MenuStore.groups.value.firstOrNull { it.id == group.id } ?: group
        val result = ActionRunner.run(context.applicationContext, updatedGroup, updated)
        if (!result.startsWith("SUCCESS")) {
            // Do not consume an episode that Android/the target player failed to open.
            MenuStore.update(context) { list ->
                list.map { g ->
                    if (g.id == group.id) g.copy(items = g.items.map { if (it.id == itemId) current else it }) else g
                }
            }
            clearActive(context)
            AppLogger.log("Podcast", "Could not open ${episode.title}; kept the previous episode: $result")
        } else {
            AppLogger.log("Podcast", "Automatic episode ${episode.title}: $result")
            // Every 2 episodes played, forget the oldest one behind us, so the list stays short.
            val played = prefs.getInt("played_$itemId", 0) + 1
            if (played % 2 == 0 && updated.podcastIndex > 0) {
                val trimmed = episodes.drop(1)
                MenuStore.update(context) { list ->
                    list.map { g ->
                        if (g.id == group.id) g.copy(items = g.items.map {
                            if (it.id == itemId) it.copy(podcastEpisodes = PodcastFeed.encodeEpisodes(trimmed), podcastIndex = (it.podcastIndex - 1).coerceAtLeast(0)) else it
                        }) else g
                    }
                }
                AppLogger.log("Podcast", "Removed the oldest episode; ${trimmed.size} left")
            }
            prefs.edit().putInt("played_$itemId", played).apply()
        }
    }

    private fun normalizeEpisodeLink(value: String): String = runCatching {
        android.net.Uri.parse(value).buildUpon().fragment(null).build().toString().trimEnd('/')
    }.getOrDefault(value.trim().trimEnd('/'))

    private fun findItem(id: String): MenuItem? =
        MenuStore.groups.value.asSequence().filter { it.type == GroupType.PODCAST }.flatMap { it.items.asSequence() }.firstOrNull { it.id == id }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Podcast episodes", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Choose when Watch Menu advances to the next podcast episode"
                }
            )
        }
    }

    private fun notificationId(itemId: String): Int = NOTIFICATION_BASE + (itemId.hashCode() and 0x3fff)

    private fun decisionPendingIntent(context: Context, action: String, itemId: String, code: Int): PendingIntent {
        val intent = Intent(context, PodcastDecisionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_ACTION, action)
            putExtra(EXTRA_ITEM_ID, itemId)
        }
        return PendingIntent.getBroadcast(
            context,
            code + (itemId.hashCode() and 0x3fff),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun actionFrom(intent: Intent): String? = intent.getStringExtra(EXTRA_ACTION) ?: intent.action
    fun itemIdFrom(intent: Intent): String? = intent.getStringExtra(EXTRA_ITEM_ID)
}

class PodcastDecisionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        intent ?: return
        val action = PodcastPlayback.actionFrom(intent) ?: return
        val itemId = PodcastPlayback.itemIdFrom(intent) ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            try {
                MenuStore.init(context.applicationContext)
                PodcastPlayback.decide(context.applicationContext, action, itemId)
            } catch (e: Exception) {
                AppLogger.logError("Podcast", "episode decision", e)
            } finally {
                pending.finish()
            }
        }
    }
}
