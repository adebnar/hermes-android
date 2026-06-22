package com.hermes.client.data.diagnostics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DebugLogTest {
    @Before fun setUp() {
        DebugLog.setTokenToRedact(null)
        DebugLog.setEnabled(true)
        DebugLog.clear()
    }

    @After fun tearDown() {
        DebugLog.setEnabled(false)
        DebugLog.setTokenToRedact(null)
        DebugLog.clear()
    }

    @Test fun disabled_log_is_a_noop() {
        DebugLog.clear()
        DebugLog.setEnabled(false)
        DebugLog.log("ws", "should not be recorded")
        assertTrue("disabled logging must record nothing", DebugLog.entries.value.isEmpty())
    }

    @Test fun enabled_log_is_recorded_with_category_and_message() {
        DebugLog.log("session", "open(s1)")
        val e = DebugLog.entries.value
        assertEquals(1, e.size)
        assertEquals("session", e.first().category)
        assertEquals("open(s1)", e.first().message)
    }

    @Test fun ring_buffer_caps_at_max() {
        repeat(DebugLog.MAX_ENTRIES + 50) { DebugLog.log("ws", "line $it") }
        val e = DebugLog.entries.value
        assertEquals(DebugLog.MAX_ENTRIES, e.size)
        // Oldest entries are evicted; the newest must be retained.
        assertEquals("line ${DebugLog.MAX_ENTRIES + 49}", e.last().message)
    }

    @Test fun registered_token_is_redacted() {
        DebugLog.setTokenToRedact("SECRET-TOKEN-123")
        DebugLog.log("rest", "GET /api/sessions  token=SECRET-TOKEN-123 end")
        val msg = DebugLog.entries.value.single().message
        assertFalse("raw token must not appear", msg.contains("SECRET-TOKEN-123"))
        assertTrue("token must be masked", msg.contains("***"))
    }

    @Test fun blank_token_does_not_redact_everything() {
        DebugLog.setTokenToRedact("")
        DebugLog.log("rest", "GET /api/status")
        assertEquals("GET /api/status", DebugLog.entries.value.single().message)
    }

    @Test fun clear_empties_the_buffer() {
        DebugLog.log("ws", "a")
        DebugLog.log("ws", "b")
        DebugLog.clear()
        assertTrue(DebugLog.entries.value.isEmpty())
    }

    @Test fun export_contains_category_and_messages_in_order() {
        DebugLog.log("session", "open(s1)")
        DebugLog.log("error", "message not found")
        val text = DebugLog.export()
        assertTrue(text.contains("session"))
        assertTrue(text.contains("open(s1)"))
        assertTrue(text.contains("message not found"))
        // newest-last ordering
        assertTrue(text.indexOf("open(s1)") < text.indexOf("message not found"))
    }
}
