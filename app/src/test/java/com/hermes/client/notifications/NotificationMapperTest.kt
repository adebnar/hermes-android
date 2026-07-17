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
        val e = ServerEvent("approval.request", "s1", buildJsonObject {
            put("session_id", "s1"); put("command", "Delete file?"); put("allow_permanent", true)
        })
        val spec = toNotificationSpec(e, on, appInForeground = false)!!
        assertEquals(Notif.CHANNEL_APPROVALS, spec.channelId)
        assertEquals("chat/s1", spec.route)
        assertTrue(spec.body.contains("Delete file?"))
        assertEquals(
            listOf(Notif.ACTION_ALLOW_ONCE, Notif.ACTION_ALLOW_SESSION, Notif.ACTION_DENY),
            spec.actions.map { it.action },
        )
        assertTrue(spec.actions.none { it.reply })
        assertTrue(spec.actions.all { it.sessionId == "s1" })
    }

    @Test fun standard_approval_offers_allow_once_session_and_deny() {
        val e = ServerEvent("approval.request", "s1", buildJsonObject {
            put("session_id", "s1"); put("command", "git push -f"); put("allow_permanent", true)
        })
        val spec = toNotificationSpec(e, on, appInForeground = false)!!
        assertEquals(
            listOf(Notif.ACTION_ALLOW_ONCE, Notif.ACTION_ALLOW_SESSION, Notif.ACTION_DENY),
            spec.actions.map { it.action },
        )
    }

    @Test fun elevated_approval_offers_deny_only() {
        val e = ServerEvent("approval.request", "s1", buildJsonObject {
            put("session_id", "s1"); put("command", "rm -rf /"); put("allow_permanent", false)
        })
        val spec = toNotificationSpec(e, on, appInForeground = false)!!
        assertEquals(listOf(Notif.ACTION_DENY), spec.actions.map { it.action })
    }

    @Test fun elevated_approval_has_no_session_action() {
        val e = ServerEvent("approval.request", "s1", buildJsonObject {
            put("session_id", "s1"); put("command", "rm -rf /"); put("allow_permanent", false)
        })
        val spec = toNotificationSpec(e, on, appInForeground = false)!!
        assertEquals(listOf(Notif.ACTION_DENY), spec.actions.map { it.action })
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
        assertEquals(1, spec.actions.size)
        val reply = spec.actions.single()
        assertEquals(Notif.ACTION_REPLY, reply.action)
        assertEquals("Reply", reply.label)
        assertTrue(reply.reply)
        assertEquals("c1", reply.sessionId)
    }

    @Test fun clarify_off_when_approvals_off() {
        val e = event(Notif.EVENT_CLARIFY, "c1", "question" to "x")
        assertNull(toNotificationSpec(e, on.copy(approvals = false), appInForeground = false))
    }

    @Test fun message_complete_is_run_finished_only_when_backgrounded() {
        val e = event(Notif.EVENT_MESSAGE_COMPLETE, "c1")
        val spec = toNotificationSpec(e, on, appInForeground = false)!!
        assertEquals("Run finished", spec.title)
        assertEquals(Notif.CHANNEL_ACTIVITY, spec.channelId)
        assertEquals("chat/c1", spec.route)
        assertNull(toNotificationSpec(e, on, appInForeground = true))
    }

    @Test fun message_complete_off_when_pref_off() {
        val e = event(Notif.EVENT_MESSAGE_COMPLETE, "c1")
        assertNull(toNotificationSpec(e, on.copy(runFinished = false), appInForeground = false))
    }

    @Test fun error_maps_to_run_failed_with_message() {
        val spec = toNotificationSpec(event(Notif.EVENT_ERROR, "c1", "message" to "boom"), on, appInForeground = false)!!
        assertEquals("Run failed", spec.title)
        assertEquals("boom", spec.body)
        assertEquals(Notif.CHANNEL_ACTIVITY, spec.channelId)
        assertNull(toNotificationSpec(event(Notif.EVENT_ERROR, "c1"), on, appInForeground = true))
    }

    @Test fun error_falls_back_to_default_body_when_message_missing_or_blank() {
        val fallback = "The agent run failed — tap to view."
        assertEquals(fallback, toNotificationSpec(event(Notif.EVENT_ERROR, "c1"), on, appInForeground = false)!!.body)
        assertEquals(fallback, toNotificationSpec(event(Notif.EVENT_ERROR, "c1", "message" to "   "), on, appInForeground = false)!!.body)
    }

    // Regression: the gateway's /api/ws never emits run.completed/run.failed (those are on the
    // separate messaging API), so the app must NOT key on them — the 0.1.44 feature did and could
    // never fire. message.complete/error are the real end-of-turn events.
    @Test fun legacy_run_events_do_not_notify() {
        assertNull(toNotificationSpec(event("run.completed", "c1"), on, appInForeground = false))
        assertNull(toNotificationSpec(event("run.failed", "c1"), on, appInForeground = false))
    }

    // A turn can emit several message.completes; the per-session id keeps them one updating
    // notification rather than a growing stack.
    @Test fun repeated_message_complete_share_one_notification_id() {
        val a = toNotificationSpec(event(Notif.EVENT_MESSAGE_COMPLETE, "c1"), on, appInForeground = false)!!
        val b = toNotificationSpec(event(Notif.EVENT_MESSAGE_COMPLETE, "c1"), on, appInForeground = false)!!
        assertEquals(a.id, b.id)
    }

    @Test fun no_session_is_null() {
        assertNull(toNotificationSpec(event(Notif.EVENT_MESSAGE_COMPLETE, null), on, appInForeground = false))
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
