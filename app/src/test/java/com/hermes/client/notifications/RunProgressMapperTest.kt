package com.hermes.client.notifications

import com.hermes.client.data.progress.RunProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunProgressMapperTest {
    private val on = NotificationPrefs(enabled = true, runProgress = true)

    @Test fun idle_state_maps_to_null() {
        assertNull(RunProgress(profile = "acme").toSpec(on))
    }

    @Test fun running_maps_to_an_indeterminate_spec_titled_with_the_tenant() {
        val spec = RunProgress(running = true, sessionId = "s1", profile = "acme").toSpec(on)!!
        assertEquals("acme · agent running", spec.title)
        assertEquals("Working…", spec.body)
        assertTrue(spec.indeterminate)
        assertEquals("chat/s1", spec.route)
        assertNull(spec.shortText)
    }

    @Test fun active_tool_appears_in_the_body() {
        val spec = RunProgress(running = true, tool = "web_search", profile = "acme").toSpec(on)!!
        assertEquals("Calling tool: web_search", spec.body)
    }

    @Test fun todo_counts_make_the_spec_determinate_with_a_short_chip() {
        val spec = RunProgress(running = true, done = 3, total = 5, profile = "globex").toSpec(on)!!
        assertFalse(spec.indeterminate)
        assertEquals(3, spec.done)
        assertEquals(5, spec.total)
        assertEquals("3/5", spec.shortText)
    }

    @Test fun a_missing_profile_falls_back_to_a_generic_title() {
        val spec = RunProgress(running = true, profile = null).toSpec(on)!!
        assertEquals("Agent running", spec.title)
    }

    @Test fun a_blank_profile_falls_back_to_a_generic_title() {
        val spec = RunProgress(running = true, profile = "   ").toSpec(on)!!
        assertEquals("Agent running", spec.title)
    }

    @Test fun master_notification_toggle_off_suppresses_progress() {
        val prefs = NotificationPrefs(enabled = false, runProgress = true)
        assertNull(RunProgress(running = true, profile = "acme").toSpec(prefs))
    }

    @Test fun run_progress_toggle_off_suppresses_progress() {
        val prefs = NotificationPrefs(enabled = true, runProgress = false)
        assertNull(RunProgress(running = true, profile = "acme").toSpec(prefs))
    }

    @Test fun run_progress_defaults_to_on() {
        assertTrue(NotificationPrefs().runProgress)
    }

    @Test fun a_run_with_no_session_id_has_no_route() {
        val spec = RunProgress(running = true, sessionId = null, profile = "acme").toSpec(on)!!
        assertNull(spec.route)
    }

    @Test fun the_title_comes_from_the_runs_latched_profile_not_a_passed_in_name() {
        val spec = RunProgress(running = true, profile = "globex").toSpec(on)!!
        assertEquals("globex · agent running", spec.title)
    }
}
