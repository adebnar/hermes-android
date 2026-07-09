package com.hermes.client.notifications

import com.hermes.client.data.network.ServerEvent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationMapperTest {
    private val on = NotificationPrefs(enabled = true, approvals = true, runFinished = true)

    private fun event(type: String, sid: String? = "s1", vararg pairs: Pair<String, String>) =
        ServerEvent(type, sid, buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } })

    @Test fun approval_makes_high_priority_spec_with_actions() {
        val spec = toNotificationSpec(event(Notif.EVENT_APPROVAL, "s1", "prompt" to "Delete file?"), on, appInForeground = false)!!
        assertEquals(Notif.CHANNEL_APPROVALS, spec.channelId)
        assertEquals("chat/s1", spec.route)
        assertTrue(spec.body.contains("Delete file?"))
        assertEquals(listOf(Notif.ACTION_APPROVE, Notif.ACTION_DENY), spec.actions.map { it.action })
        assertTrue(spec.actions.all { it.sessionId == "s1" })
    }

    @Test fun approval_notifies_regardless_of_foreground() {
        val e = event(Notif.EVENT_APPROVAL, "c1", "prompt" to "May I run rm?")
        assertNotNull(toNotificationSpec(e, on, appInForeground = false))
        assertNotNull(toNotificationSpec(e, on, appInForeground = true))
    }

    @Test fun approvals_toggle_and_master_toggle_suppress() {
        assertNull(toNotificationSpec(event(Notif.EVENT_APPROVAL), on.copy(approvals = false), appInForeground = false))
        assertNull(toNotificationSpec(event(Notif.EVENT_APPROVAL), NotificationPrefs(enabled = false), appInForeground = false))
    }

    @Test fun approval_off_when_pref_off() {
        val e = event(Notif.EVENT_APPROVAL, "c1", "prompt" to "x")
        assertNull(toNotificationSpec(e, on.copy(approvals = false), appInForeground = false))
    }

    @Test fun clarify_notifies_with_question_regardless_of_foreground() {
        val e = event(Notif.EVENT_CLARIFY, "c1", "question" to "Which repo?")
        val spec = toNotificationSpec(e, on, appInForeground = true)!!
        assertEquals("Needs your input", spec.title)
        assertEquals("Which repo?", spec.body)
        assertEquals("chat/c1", spec.route)
        assertTrue(spec.actions.isEmpty())
    }

    @Test fun clarify_off_when_approvals_off() {
        val e = event(Notif.EVENT_CLARIFY, "c1", "question" to "x")
        assertNull(toNotificationSpec(e, on.copy(approvals = false), appInForeground = false))
    }

    @Test fun run_completed_only_when_backgrounded() {
        val e = event(Notif.EVENT_RUN_COMPLETED, "c1")
        assertNotNull(toNotificationSpec(e, on, appInForeground = false))
        assertNull(toNotificationSpec(e, on, appInForeground = true))
    }

    @Test fun run_completed_off_when_pref_off() {
        val e = event(Notif.EVENT_RUN_COMPLETED, "c1")
        assertNull(toNotificationSpec(e, on.copy(runFinished = false), appInForeground = false))
    }

    @Test fun run_failed_title() {
        val spec = toNotificationSpec(event(Notif.EVENT_RUN_FAILED, "c1"), on, appInForeground = false)!!
        assertEquals("Run failed", spec.title)
        assertEquals(Notif.CHANNEL_ACTIVITY, spec.channelId)
    }

    @Test fun no_session_is_null() {
        assertNull(toNotificationSpec(event(Notif.EVENT_RUN_COMPLETED, null), on, appInForeground = false))
    }

    @Test fun disabled_is_null() {
        assertNull(toNotificationSpec(event(Notif.EVENT_APPROVAL, "c1", "prompt" to "x"), on.copy(enabled = false), appInForeground = false))
    }

    @Test fun non_approval_and_sessionless_events_are_null() {
        // message.received / message.delta aren't notifiable events on the app's WebSocket.
        assertNull(toNotificationSpec(event("message.received", "m1", "platform" to "Telegram"), on, appInForeground = false))
        assertNull(toNotificationSpec(event("message.delta"), on, appInForeground = false))
        assertNull(toNotificationSpec(event(Notif.EVENT_APPROVAL, sid = null), on, appInForeground = false))
    }
}
