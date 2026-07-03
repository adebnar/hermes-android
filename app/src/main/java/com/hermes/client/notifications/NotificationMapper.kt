package com.hermes.client.notifications

import com.hermes.client.data.network.ServerEvent
import com.hermes.client.data.network.str

/**
 * Pure mapping from a gateway event to a notification (or null). Centralizes every event-type
 * decision so verifying/adjusting the gateway's event names is a one-line change. A stable id is
 * derived from the session so repeats of the same event update rather than stack.
 */
fun toNotificationSpec(event: ServerEvent, prefs: NotificationPrefs): NotificationSpec? {
    if (!prefs.enabled) return null
    val sid = event.sessionId ?: return null
    val id = (event.type + sid).hashCode()
    return when (event.type) {
        Notif.EVENT_APPROVAL -> if (!prefs.approvals) null else NotificationSpec(
            id = id,
            channelId = Notif.CHANNEL_APPROVALS,
            title = "Approval needed",
            body = event.str("prompt") ?: "The agent is waiting for your approval.",
            route = "chat/$sid",
            actions = listOf(
                NotifAction("Approve", Notif.ACTION_APPROVE, sid),
                NotifAction("Deny", Notif.ACTION_DENY, sid),
            ),
            groupKey = "approval",
        )
        Notif.EVENT_CRON_DONE -> if (!prefs.cron) null else NotificationSpec(
            id = id,
            channelId = Notif.CHANNEL_ACTIVITY,
            title = event.str("name") ?: "Scheduled job finished",
            body = "Run ${event.str("status") ?: "finished"}",
            route = "chat/$sid",
            actions = emptyList(),
            groupKey = "cron",
        )
        Notif.EVENT_MSG -> if (!prefs.messaging) null else NotificationSpec(
            id = id,
            channelId = Notif.CHANNEL_ACTIVITY,
            title = "Reply on ${event.str("platform") ?: "messaging"}",
            body = event.str("preview") ?: event.str("text") ?: "New reply",
            route = "chat/$sid",
            actions = emptyList(),
            groupKey = "messaging",
        )
        else -> null
    }
}
