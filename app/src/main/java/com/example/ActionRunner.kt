package com.example

import android.content.Context
import android.media.AudioManager

/** Runs one menu item. Every result string starts with "SUCCESS" or "FAIL". */
object ActionRunner {

    suspend fun run(context: Context, group: MenuGroup, item: MenuItem): String {
        return try {
            when (group.type) {
                GroupType.MUSIC -> runMusic(context, item)
                GroupType.PODCAST -> runPodcast(context, item)
                GroupType.APPS -> TestEngine.launchApp(context, item.value, MediaMenuConfig.returnAfterLaunch(context))
                GroupType.TOGGLES -> runToggle(context, item)
                GroupType.CUSTOM ->
                    if (item.silent) TestEngine.sendSilentGet(context, item.value)
                    else TestEngine.openUrlAsLink(context, item.value)
            }
        } catch (e: Exception) {
            AppLogger.logError("ActionRunner", "run(${item.name})", e)
            "FAIL: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** Podcasts use the saved RSS episode queue; platform search is only a fallback. */
    private suspend fun runPodcast(context: Context, item: MenuItem): String {
        val pkg = when {
            item.value.contains("open.spotify.com") || item.podcastCurrentUrl.contains("open.spotify.com") || item.podcastPlayerHint.contains("open.spotify.com") -> "com.spotify.music"
            // A music.youtube.com share link explicitly means the user wants YouTube Music;
            // don't let the app-wide preferred player override that choice.
            item.value.contains("music.youtube.com", true) || item.podcastCurrentUrl.contains("music.youtube.com", true) || item.podcastPlayerHint.contains("music.youtube.com", true) -> "com.google.android.apps.youtube.music"
            item.value.contains("youtube.com") || item.value.contains("youtu.be") || item.podcastCurrentUrl.contains("youtube.com") || item.podcastPlayerHint.contains("youtube.com") || item.podcastPlayerHint.contains("youtu.be") -> MediaMenuConfig.targetPackage(context).ifBlank { "com.google.android.apps.youtube.music" }
            else -> MediaMenuConfig.targetPackage(context)
        }
        val episodes = PodcastFeed.decodeEpisodes(item.podcastEpisodes)
        val current = episodes.getOrNull(item.podcastIndex)
        val episodeUrl = item.podcastCurrentUrl.ifBlank { current?.let { it.link.ifBlank { it.audioUrl } }.orEmpty() }
        // Older entries only store a show and the remembered episode title: still searchable.
        val episodeTitle = (current?.title ?: item.episode).trim()
        val query = "${item.name} $episodeTitle".trim()
        val want = episodeTitle.lowercase().filter { it.isLetterOrDigit() }
        fun matches(title: String): Boolean {
            if (want.isEmpty()) return true
            val actual = title.lowercase().filter { it.isLetterOrDigit() }
            return actual.isNotEmpty() && (actual.contains(want) || want.contains(actual))
        }

        // The player is already playing this very episode: there is nothing to start.
        if (want.isNotEmpty() && isPlayingTitle(context, pkg) { matches(it) }) {
            PodcastPlayback.setActive(context, item.id)
            return "SUCCESS episode: $episodeTitle (already playing)"
        }

        val canOpenFromBackground = android.provider.Settings.canDrawOverlays(context)
        if (!canOpenFromBackground) {
            AppLogger.log("Action", "'Display over other apps' is off: Android blocks opening $pkg from the background")
        }
        try {
            val sm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as android.media.session.MediaSessionManager
            val sessions = sm.getActiveSessions(MediaListenerService.getComponentName(context))
            // A song from another player must not keep playing under the podcast.
            sessions.filter {
                it.packageName != pkg && it.packageName != context.packageName &&
                    !MediaMenuConfig.isIgnored(context, it.packageName) &&
                    it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
            }.forEach {
                AppLogger.log("Action", "Pausing ${it.packageName} before the podcast")
                it.transportControls.pause()
            }
            sessions.firstOrNull { it.packageName == pkg }?.transportControls?.stop()
            kotlinx.coroutines.delay(500)
        } catch (e: Exception) {
            AppLogger.logError("Action", "stop before podcast", e)
        }

        val before = currentTitle(context, pkg)
        /** Waits for the player itself to show the episode. A command that was sent is not proof it worked. */
        suspend fun confirmed(): Boolean {
            repeat(12) {
                kotlinx.coroutines.delay(300)
                val t = currentTitle(context, pkg) ?: return@repeat
                if (matches(t) && (t != before || isPlayingTitle(context, pkg) { matches(it) })) return true
            }
            return false
        }

        // The same ladder as music. The first two need an already running player, the browser connection
        // wakes it up, and opening a link is last because Android blocks that from the background (it still
        // reports "sent"), which is why a podcast started from the watch used to do nothing.
        var last = "FAIL: no episode link or name to play"
        for (label in listOf("SESSION URI", "SESSION SEARCH", "BROWSER SEARCH", "OPEN LINK")) {
            val r = when (label) {
                "SESSION URI" -> if (episodeUrl.isBlank()) continue else TestEngine.playFromUri(context, pkg, episodeUrl)
                "SESSION SEARCH" -> if (query.isBlank()) continue else TestEngine.playFromSearch(context, pkg, query)
                "BROWSER SEARCH" -> if (query.isBlank()) continue else TestEngine.connectMediaBrowserAndPlay(context, pkg, episodeUrl, query)
                else -> if (episodeUrl.isBlank()) continue else openPodcastUrl(context, pkg, episodeUrl)
            }
            AppLogger.log("Action", "Podcast $label: ${r.take(120)}")
            if (!r.startsWith("SUCCESS")) { last = r; continue }
            if (confirmed()) {
                PodcastPlayback.setActive(context, item.id)
                return "SUCCESS via $label: $episodeTitle"
            }
            last = "FAIL: $label was sent but $pkg did not start the episode"
        }

        // Nothing was confirmed by the player. Hand the link to Android's normal app chooser as a last try.
        val fallbackUrl = episodeUrl.ifBlank { current?.audioUrl.orEmpty() }.ifBlank { item.value }
        if (fallbackUrl.isNotBlank()) {
            val opened = openPodcastUrl(context, null, fallbackUrl)
            if (opened.startsWith("SUCCESS") && !canOpenFromBackground) {
                return "FAIL: Android blocked opening the episode. Turn on 'Display over other apps' for this app."
            }
            if (opened.startsWith("SUCCESS")) {
                PodcastPlayback.setActive(context, item.id)
                return "SUCCESS: opened the episode link; the player did not confirm it ($last)"
            }
        }
        return last
    }

    /** True when [pkg] is playing right now and its title passes [ok]. */
    private fun isPlayingTitle(context: Context, pkg: String, ok: (String) -> Boolean): Boolean = try {
        val sm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as android.media.session.MediaSessionManager
        val c = sm.getActiveSessions(MediaListenerService.getComponentName(context)).firstOrNull { it.packageName == pkg }
        val title = c?.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
        c?.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING && title != null && ok(title)
    } catch (e: Exception) { false }

    private fun openPodcastUrl(context: Context, pkg: String?, url: String): String {
        return try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                if (!pkg.isNullOrBlank()) setPackage(pkg)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "SUCCESS: opened episode link"
        } catch (e: Exception) {
            "FAIL: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun currentTitle(context: Context, pkg: String): String? = try {
        val sm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as android.media.session.MediaSessionManager
        sm.getActiveSessions(MediaListenerService.getComponentName(context))
            .firstOrNull { it.packageName == pkg }?.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
    } catch (e: Exception) { null }

    /** A command that was "sent" is not proof it worked: wait for the player to show a new title. */
    private suspend fun verified(context: Context, pkg: String, before: String?, ok: (String) -> Boolean = { true }): Boolean {
        repeat(10) {
            kotlinx.coroutines.delay(300)
            val t = currentTitle(context, pkg)
            if (t != null && t != before && ok(t)) return true
        }
        return false
    }

    private suspend fun runMusic(context: Context, item: MenuItem): String {
        val url = item.value
        // Spotify and Apple Music links open in their own apps; everything else uses the preferred player.
        val pkg = when {
            url.contains("open.spotify.com") -> "com.spotify.music"
            url.contains("music.apple.com") -> "com.apple.android.music"
            else -> MediaMenuConfig.targetPackage(context)
        }
        val name = item.name
        val goHome = MediaMenuConfig.returnAfterLaunch(context)

        suspend fun byUri() = TestEngine.playFromUri(context, pkg, url)
        suspend fun bySearch() = TestEngine.playFromSearch(context, pkg, name)
        suspend fun byBrowser() = TestEngine.connectMediaBrowserAndPlay(context, pkg, url, name)
        suspend fun byLaunch() = TestEngine.openLink(context, pkg, url, goHome)

        return when (MediaMenuConfig.musicMethod(context)) {
            "URI" -> byUri()
            "SEARCH" -> bySearch()
            "BROWSER" -> byBrowser()
            "LAUNCH" -> byLaunch()
            else -> {
                var last = "FAIL: nothing tried"
                val before = currentTitle(context, pkg)
                // Name search is not used here: it can play any song with a similar name.
                for (label in listOf("URI", "BROWSER", "LAUNCH")) {
                    val r = when (label) {
                        "URI" -> byUri()
                        "BROWSER" -> byBrowser()
                        else -> byLaunch()
                    }
                    if (!r.startsWith("SUCCESS")) { last = r; continue }
                    if (label == "LAUNCH" || verified(context, pkg, before)) return "SUCCESS via $label: $r"
                    last = "FAIL: $label was sent but the player did not change"
                }
                last
            }
        }
    }

    private fun runToggle(context: Context, item: MenuItem): String {
        val kind = try {
            ToggleKind.valueOf(item.value)
        } catch (e: Exception) {
            return "FAIL: unknown toggle '${item.value}'"
        }
        ToggleState.init(context)
        return when (kind) {
            ToggleKind.TORCH -> TestEngine.setTorch(context, !ToggleState.isOn(context, kind))
            ToggleKind.DND -> TestEngine.setDnd(context, !ToggleState.isOn(context, kind))
            ToggleKind.SILENT ->
                if (ToggleState.isOn(context, kind)) TestEngine.setRingerMode(context, AudioManager.RINGER_MODE_NORMAL, "NORMAL")
                else TestEngine.setRingerMode(context, AudioManager.RINGER_MODE_SILENT, "SILENT")
            ToggleKind.VIBRATE ->
                if (ToggleState.isOn(context, kind)) TestEngine.setRingerMode(context, AudioManager.RINGER_MODE_NORMAL, "NORMAL")
                else TestEngine.setRingerMode(context, AudioManager.RINGER_MODE_VIBRATE, "VIBRATE")
            ToggleKind.TORCH_ON -> TestEngine.setTorch(context, true)
            ToggleKind.TORCH_OFF -> TestEngine.setTorch(context, false)
            ToggleKind.VOLUME_UP -> TestEngine.adjustVolume(context, true)
            ToggleKind.VOLUME_DOWN -> TestEngine.adjustVolume(context, false)
            ToggleKind.DND_ON -> TestEngine.setDnd(context, true)
            ToggleKind.DND_OFF -> TestEngine.setDnd(context, false)
            ToggleKind.RINGER_SILENT -> TestEngine.setRingerMode(context, AudioManager.RINGER_MODE_SILENT, "SILENT")
            ToggleKind.RINGER_VIBRATE -> TestEngine.setRingerMode(context, AudioManager.RINGER_MODE_VIBRATE, "VIBRATE")
            ToggleKind.RINGER_NORMAL -> TestEngine.setRingerMode(context, AudioManager.RINGER_MODE_NORMAL, "NORMAL")
            ToggleKind.LOCK_SCREEN -> TestEngine.lockScreen(context)
            ToggleKind.SCREENSHOT -> TestEngine.takeScreenshot(context)
        }
    }
}
