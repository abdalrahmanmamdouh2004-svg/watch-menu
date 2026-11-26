package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RepliesUtf8Test {
    @Test
    fun arabicNamesAndMessagesStayUtf8AtEveryByteLimit() {
        val sample = "أحمد: مرحباً بالعالم"
        val bytes = sample.toByteArray(Charsets.UTF_8)
        for (limit in 0..(bytes.size + 2)) {
            val result = Replies.fitUtf8(sample, limit)
            val encoded = result.toByteArray(Charsets.UTF_8)
            assertTrue("result exceeds $limit bytes", encoded.size <= limit)
            assertEquals(result, String(encoded, Charsets.UTF_8))
            assertFalse("Arabic was replaced by dots", result.contains('·'))
        }
    }

    @Test
    fun truncationNeverLeavesHalfAnEmojiOrArabicCharacter() {
        val sample = "أحمد😀رسالة"
        val bytes = sample.toByteArray(Charsets.UTF_8)
        for (limit in 0..bytes.size) {
            val result = Replies.fitUtf8(sample, limit)
            assertEquals(result, String(result.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        }
    }

    @Test
    fun eachSupportedAppKeepsDifferentPeopleInSeparateInboxChats() {
        val packages = listOf("com.whatsapp", "com.facebook.orca", "org.telegram.messenger")
        for (pkg in packages) {
            val mother = Replies.conversationKey(pkg, null, "notification-1", null, "Mother")
            val motherUpdate = Replies.conversationKey(pkg, null, "notification-2", null, "Mother")
            val father = Replies.conversationKey(pkg, null, "notification-3", null, "Father")
            assertEquals(mother, motherUpdate)
            assertFalse(mother == father)
        }
    }

    @Test
    fun senderIdentitySeparatesDirectChatsWhenTheNotificationHasNoUsefulTitle() {
        val first = Replies.conversationKey("com.facebook.orca", null, "same-notification", null, "Messenger", "person:mum")
        val second = Replies.conversationKey("com.facebook.orca", null, "same-notification", null, "Messenger", "person:dad")
        assertFalse(first == second)
    }

    @Test
    fun groupTitleKeepsMessagesFromDifferentSendersInOneGroupConversation() {
        val first = Replies.conversationKey("com.whatsapp", null, "notification-1", "Family", "WhatsApp", "person:mum")
        val second = Replies.conversationKey("com.whatsapp", null, "notification-2", "Family", "WhatsApp", "person:dad")
        val other = Replies.conversationKey("com.whatsapp", null, "notification-3", "Friends", "WhatsApp", "person:mum")
        assertEquals(first, second)
        assertFalse(first == other)
    }

    @Test
    fun notificationShortcutKeepsItsConversationIdentityStable() {
        val a = Replies.conversationKey("org.telegram.messenger", "chat-shortcut-a", "notification-a", "Family")
        val b = Replies.conversationKey("org.telegram.messenger", "chat-shortcut-b", "notification-b", "Family")
        val aUpdate = Replies.conversationKey("org.telegram.messenger", "chat-shortcut-a", "notification-c", "Different title after rename")
        assertFalse(a == b)
        assertEquals(a, aUpdate)
    }

    @Test
    fun repliesIgnorePreferenceMatchesOnlySelectedMessagingApps() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        RepliesIgnoreConfig.setIgnored(context, emptySet())
        RepliesIgnoreConfig.setIgnored(context, setOf("com.facebook.orca", "com.facebook.mlite"))
        assertTrue(RepliesIgnoreConfig.isIgnored(context, "com.facebook.orca"))
        assertFalse(RepliesIgnoreConfig.isIgnored(context, "com.whatsapp"))
        RepliesIgnoreConfig.setIgnored(context, emptySet())
    }

}
