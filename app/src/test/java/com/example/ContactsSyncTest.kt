package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContactsSyncTest {

    @Test
    fun testBuildContactsPayloadEmpty() {
        val payload = WatchLink.buildContactsPayload(emptyList())
        assertEquals("Empty contacts list should produce empty payload", 0, payload.size)
    }

    @Test
    fun testBuildContactsPayloadSingleContact57Bytes() {
        val contacts = listOf(
            WatchLink.WatchContact("Alice Smith", "+15551234567")
        )
        val payload = WatchLink.buildContactsPayload(contacts)

        assertEquals("One contact must serialize to exactly 57 bytes", 57, payload.size)

        // First 32 bytes: UTF-8 name (null-padded)
        val nameBytes = payload.copyOfRange(0, 32)
        val nameStr = String(nameBytes, Charsets.UTF_8).trimEnd { it == '\u0000' }
        assertEquals("Alice Smith", nameStr)

        // Next 25 bytes: ASCII phone number (null-padded)
        val phoneBytes = payload.copyOfRange(32, 57)
        val phoneStr = String(phoneBytes, Charsets.US_ASCII).trimEnd { it == '\u0000' }
        assertEquals("+15551234567", phoneStr)
    }

    @Test
    fun testBuildContactsPayloadMax20ContactsCap() {
        // Create 25 contacts
        val contacts = (1..25).map { i ->
            WatchLink.WatchContact("Contact $i", "555000$i")
        }
        val payload = WatchLink.buildContactsPayload(contacts)

        assertEquals(
            "Payload must cap at 20 contacts (20 * 57 = 1140 bytes)",
            20 * 57,
            payload.size
        )

        // Verify the 20th contact is included
        val c20Offset = 19 * 57
        val c20Name = String(payload.copyOfRange(c20Offset, c20Offset + 32), Charsets.UTF_8).trimEnd { it == '\u0000' }
        assertEquals("Contact 20", c20Name)
    }

    @Test
    fun testBuildContactsPayloadLongUnicodeNameTruncation() {
        val longUnicodeName = "Alexander Wolfeschlegelsteinhausenbergerdorff"
        val longPhone = "+123456789012345678901234567890"

        val contacts = listOf(WatchLink.WatchContact(longUnicodeName, longPhone))
        val payload = WatchLink.buildContactsPayload(contacts)

        assertEquals(57, payload.size)

        // Name buffer is 32 bytes, null terminated
        val nameBytes = payload.copyOfRange(0, 32)
        assertTrue("32nd byte must be null terminator", nameBytes[31] == 0.toByte())

        // Phone buffer is 25 bytes, null terminated
        val phoneBytes = payload.copyOfRange(32, 57)
        assertTrue("25th byte of phone must be null terminator", phoneBytes[24] == 0.toByte())
    }
}
