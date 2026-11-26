package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuStoreTest {

    @Test
    fun testMenuGroupStayInGroupAndRepeatGroupDefaults() {
        val group = MenuGroup(
            name = "Chill Vibes",
            type = GroupType.MUSIC,
            items = listOf(
                MenuItem(name = "Song 1", value = "https://open.spotify.com/track/1"),
                MenuItem(name = "Song 2", value = "https://open.spotify.com/track/2")
            )
        )
        assertFalse("stayInGroup should default to false", group.stayInGroup)
        assertFalse("repeatGroup should default to false", group.repeatGroup)
    }

    @Test
    fun testMenuGroupStayInGroupAndRepeatGroupCustomization() {
        val original = MenuGroup(
            id = "grp_album_1",
            name = "Strict Album",
            type = GroupType.MUSIC,
            items = listOf(
                MenuItem(name = "Track 1", value = "spotify:track:111"),
                MenuItem(name = "Track 2", value = "spotify:track:222")
            ),
            stayInGroup = true,
            repeatGroup = true
        )

        assertEquals("grp_album_1", original.id)
        assertEquals("Strict Album", original.name)
        assertEquals(GroupType.MUSIC, original.type)
        assertEquals(2, original.items.size)
        assertTrue("stayInGroup must be true when configured", original.stayInGroup)
        assertTrue("repeatGroup must be true when configured", original.repeatGroup)

        // Test copy semantics
        val updated = original.copy(repeatGroup = false)
        assertTrue("stayInGroup should remain true after copy", updated.stayInGroup)
        assertFalse("repeatGroup should be updated to false", updated.repeatGroup)
    }

    @Test
    fun testPodcastGroupModelIntegrity() {
        val podcastGroup = MenuGroup(
            name = "Tech Talks",
            type = GroupType.PODCAST,
            items = listOf(
                MenuItem(
                    name = "Episode 42",
                    value = "https://feed.url/ep42",
                    episode = "Quantum Computing",
                    podcastFeedUrl = "https://feed.url/rss",
                    podcastIndex = 41,
                    podcastPositionMs = 125000L
                )
            )
        )

        assertEquals(GroupType.PODCAST, podcastGroup.type)
        assertEquals(1, podcastGroup.items.size)
        val item = podcastGroup.items[0]
        assertEquals("Episode 42", item.name)
        assertEquals("Quantum Computing", item.episode)
        assertEquals("https://feed.url/rss", item.podcastFeedUrl)
        assertEquals(41, item.podcastIndex)
        assertEquals(125000L, item.podcastPositionMs)
    }

    @Test
    fun testGroupTypeLabels() {
        assertEquals("Music", GroupType.MUSIC.label)
        assertEquals("Podcasts", GroupType.PODCAST.label)
        assertEquals("Apps", GroupType.APPS.label)
        assertEquals("Phone toggles", GroupType.TOGGLES.label)
        assertEquals("Custom", GroupType.CUSTOM.label)
    }
}
