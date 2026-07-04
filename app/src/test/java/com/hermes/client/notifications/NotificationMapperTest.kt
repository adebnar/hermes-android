package com.hermes.client.notifications

import com.hermes.client.data.network.ServerEvent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationMapperTest {
    private val on = NotificationPrefs(enabled = true)

    private fun event(type: String, sid: String? = "s1", vararg pairs: Pair<String, String>) =
        ServerEvent(type, sid, buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } })

    @Test fun approval_makes_high_priority_spec_with_actions() {
        val spec = toNotificationSpec(event(Notif.EVENT_APPROVAL, "s1", "prompt" to "Delete file?"), on)!!
        assertEquals(Notif.CHANNEL_APPROVALS, spec.channelId)
        assertEquals("chat/s1", spec.route)
        assertTrue(spec.body.contains("Delete file?"))
        assertEquals(listOf(Notif.ACTION_APPROVE, Notif.ACTION_DENY), spec.actions.map { it.action })
        assertTrue(spec.actions.all { it.sessionId == "s1" })
    }

    @Test fun approvals_toggle_and_master_toggle_suppress() {
        assertNull(toNotificationSpec(event(Notif.EVENT_APPROVAL), on.copy(approvals = false)))
        assertNull(toNotificationSpec(event(Notif.EVENT_APPROVAL), NotificationPrefs(enabled = false)))
    }

    @Test fun non_approval_and_sessionless_events_are_null() {
        // Only approval.request is notifiable; run/cron/messaging-style events map to nothing.
        assertNull(toNotificationSpec(event("run.completed", "c1", "status" to "success"), on))
        assertNull(toNotificationSpec(event("message.received", "m1", "platform" to "Telegram"), on))
        assertNull(toNotificationSpec(event("message.delta"), on))
        assertNull(toNotificationSpec(event(Notif.EVENT_APPROVAL, sid = null), on))
    }
}
