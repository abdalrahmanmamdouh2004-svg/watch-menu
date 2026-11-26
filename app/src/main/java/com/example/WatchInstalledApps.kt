package com.example

import kotlinx.coroutines.flow.MutableStateFlow

/** Exact WP2 package names owned by this companion. Add future Watch Menu app IDs here and add their Home tile action. */
object WatchInstalledApps {
    const val WATCH_MUSIC = "watch-music"
    const val WATCH_REPLIES = "watch-replies"
    const val WATCH_REMOTE = "watch-remote"

    private val ownedPackageIds = setOf(WATCH_MUSIC, WATCH_REPLIES, WATCH_REMOTE)
    val installedIds = MutableStateFlow<Set<String>>(emptySet())

    internal fun update(packages: List<FmcInstalledPackage>) {
        val installed = packages.map { it.name.trim().lowercase() }.toSet()
        installedIds.value = ownedPackageIds.filterTo(linkedSetOf()) { it in installed }
    }
}
