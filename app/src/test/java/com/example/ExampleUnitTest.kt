package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun testManufacturerHintGeneration() {
        val hint = PermissionHelper.getManufacturerHint()
        assertNotNull(hint)
        assertTrue(hint.isNotEmpty())
    }

    @Test
    fun testUnicodeCodePointFormatting() {
        val text = "okay"
        val codePoints = text.codePoints()
            .toArray()
            .joinToString(" ") { String.format("U+%04X", it) }
        assertEquals("U+006F U+006B U+0061 U+0079", codePoints)
    }

    @Test
    fun testSummaryTableFormat() {
        val results = listOf(
            Triple("2. playFromUri", "PASS", "SUCCESS: Dispatched"),
            Triple("3. playFromSearch", "FAIL", "FAIL: No active session")
        )
        val sb = StringBuilder()
        for ((name, status, detail) in results) {
            sb.append(String.format("%-25s | %-6s | %s\n", name, status, detail))
        }
        val output = sb.toString()
        assertTrue(output.contains("2. playFromUri"))
        assertTrue(output.contains("PASS"))
        assertTrue(output.contains("3. playFromSearch"))
        assertTrue(output.contains("FAIL"))
    }
}
