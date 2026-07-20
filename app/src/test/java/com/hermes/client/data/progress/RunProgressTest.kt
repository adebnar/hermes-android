package com.hermes.client.data.progress

import com.hermes.client.data.network.ServerEvent
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunProgressTest {
    private fun ev(type: String, session: String = "s1", build: (JsonObjectBuilder.() -> Unit) = {}) =
        ServerEvent(type, session, buildJsonObject { put("session_id", session); build() })

    /** A todo tool.complete carrying an explicit list of {id, content, status} items. */
    private fun todoEvent(vararg statuses: String) = ev("tool.complete") {
        put("name", "todo")
        putJsonArray("todos") {
            statuses.forEachIndexed { i, s ->
                addJsonObject { put("id", "$i"); put("content", "task $i"); put("status", s) }
            }
        }
    }

    // Case 1
    @Test fun message_start_marks_running_and_indeterminate() {
        val s = RunProgress().reduce(ev("message.start"), "acme")
        assertTrue(s.running)
        assertFalse(s.determinate)
        assertEquals("s1", s.sessionId)
    }

    // Case 2
    @Test fun tool_start_records_tool_name() {
        var s = RunProgress().reduce(ev("message.start"), "acme")
        s = s.reduce(ev("tool.start") { put("name", "web_search") }, "acme")
        assertEquals("web_search", s.tool)
    }

    // Case 3
    @Test fun todo_tool_complete_yields_determinate_counts() {
        var s = RunProgress().reduce(ev("message.start"), "acme")
        s = s.reduce(todoEvent("completed", "completed", "in_progress", "pending", "pending"), "acme")
        assertTrue(s.determinate)
        assertEquals(2, s.done)
        assertEquals(5, s.total)
    }

    // Case 4
    @Test fun cancelled_todos_are_excluded_from_total() {
        var s = RunProgress().reduce(ev("message.start"), "acme")
        s = s.reduce(todoEvent("completed", "completed", "completed", "cancelled"), "acme")
        assertEquals(3, s.done)
        assertEquals(3, s.total)
    }

    // Case 5
    @Test fun a_new_run_resets_counts_from_the_previous_run() {
        var s = RunProgress().reduce(ev("message.start"), "acme")
        s = s.reduce(todoEvent("completed", "pending"), "acme")
        s = s.reduce(ev("message.complete"), "acme")
        s = s.reduce(ev("message.start"), "acme")
        assertTrue(s.running)
        assertEquals(0, s.done)
        assertEquals(0, s.total)
        assertNull(s.tool)
    }

    // Case 6
    @Test fun message_complete_returns_to_idle() {
        var s = RunProgress().reduce(ev("message.start"), "acme")
        s = s.reduce(ev("message.complete"), "acme")
        assertFalse(s.running)
    }

    // Case 7
    @Test fun error_returns_to_idle() {
        var s = RunProgress().reduce(ev("message.start"), "acme")
        s = s.reduce(ev("error") { put("message", "boom") }, "acme")
        assertFalse(s.running)
    }

    // Case 8
    @Test fun session_info_running_false_is_the_interrupt_backstop() {
        var s = RunProgress().reduce(ev("message.start"), "acme")
        s = s.reduce(todoEvent("completed", "pending"), "acme")
        s = s.reduce(ev("session.info") { put("running", false) }, "acme")
        assertFalse(s.running)
        assertEquals(0, s.total)
    }

    @Test fun session_info_running_true_marks_running_when_connecting_mid_run() {
        val s = RunProgress().reduce(ev("session.info") { put("running", true) }, "acme")
        assertTrue(s.running)
        assertEquals("s1", s.sessionId)
    }

    // Case 9 — tool_progress_mode "off" means no tool.* events ever arrive.
    @Test fun run_without_tool_events_stays_indeterminate() {
        var s = RunProgress().reduce(ev("message.start"), "acme")
        s = s.reduce(ev("message.delta") { put("text", "thinking") }, "acme")
        assertTrue(s.running)
        assertFalse(s.determinate)
    }

    // Case 10
    @Test fun malformed_todos_payload_does_not_crash_and_stays_indeterminate() {
        var s = RunProgress().reduce(ev("message.start"), "acme")
        s = s.reduce(ev("tool.complete") { put("name", "todo"); put("todos", "not-an-array") }, "acme")
        assertFalse(s.determinate)
        assertEquals(0, s.total)
    }

    @Test fun todo_items_with_missing_status_count_toward_total_but_not_done() {
        var s = RunProgress().reduce(ev("message.start"), "acme")
        s = s.reduce(
            ev("tool.complete") {
                put("name", "todo")
                putJsonArray("todos") { addJsonObject { put("id", "1"); put("content", "x") } }
            },
            "acme",
        )
        assertEquals(0, s.done)
        assertEquals(1, s.total)
    }

    @Test fun junk_array_elements_are_excluded_from_both_counters() {
        var s = RunProgress().reduce(ev("message.start"), "acme")
        s = s.reduce(
            ev("tool.complete") {
                put("name", "todo")
                putJsonArray("todos") {
                    addJsonObject { put("id", "1"); put("content", "task 1"); put("status", "completed") }
                    add(kotlinx.serialization.json.JsonPrimitive("not-an-object"))
                    add(kotlinx.serialization.json.JsonNull)
                    addJsonObject { put("id", "2"); put("content", "task 2"); put("status", "pending") }
                }
            },
            "acme",
        )
        assertEquals(1, s.done)
        assertEquals(2, s.total)
    }

    @Test fun non_todo_tool_complete_clears_the_tool_without_touching_counts() {
        var s = RunProgress().reduce(ev("message.start"), "acme")
        s = s.reduce(todoEvent("completed", "pending"), "acme")
        s = s.reduce(ev("tool.start") { put("name", "web_search") }, "acme")
        s = s.reduce(ev("tool.complete") { put("name", "web_search") }, "acme")
        assertNull(s.tool)
        assertEquals(1, s.done)
        assertEquals(2, s.total)
    }

    @Test fun tool_events_while_idle_do_not_start_a_phantom_run() {
        val s = RunProgress().reduce(ev("tool.start") { put("name", "web_search") }, "acme")
        assertFalse(s.running)
        assertNull(s.tool)
    }

    @Test fun unrelated_events_leave_state_untouched() {
        val running = RunProgress().reduce(ev("message.start"), "acme")
        assertEquals(running, running.reduce(ev("approval.request") { put("command", "ls") }, "acme"))
    }

    // --- Tenant-latching tests (FIX 1) ---

    @Test fun message_start_latches_the_active_profile() {
        val s = RunProgress().reduce(ev("message.start"), "acme")
        assertEquals("acme", s.profile)
    }

    @Test fun a_later_profile_does_not_retroactively_change_an_in_flight_runs_latched_profile() {
        var s = RunProgress().reduce(ev("message.start"), "acme")
        s = s.reduce(ev("tool.start") { put("name", "web_search") }, "globex")
        assertEquals("acme", s.profile)
    }

    @Test fun session_info_running_true_with_a_differing_session_id_starts_a_fresh_run() {
        var s = RunProgress().reduce(ev("message.start", "s1"), "acme")
        s = s.reduce(todoEvent("completed", "pending"), "acme")
        s = s.reduce(ev("session.info", "s2") { put("running", true) }, "globex")
        assertTrue(s.running)
        assertEquals("s2", s.sessionId)
        assertEquals("globex", s.profile)
        assertEquals(0, s.done)
        assertEquals(0, s.total)
    }

    @Test fun session_info_running_true_with_the_same_session_id_preserves_existing_counts() {
        var s = RunProgress().reduce(ev("message.start", "s1"), "acme")
        s = s.reduce(todoEvent("completed", "pending"), "acme")
        s = s.reduce(ev("session.info", "s1") { put("running", true) }, "globex")
        assertTrue(s.running)
        assertEquals("s1", s.sessionId)
        assertEquals("acme", s.profile)
        assertEquals(1, s.done)
        assertEquals(2, s.total)
    }
}
