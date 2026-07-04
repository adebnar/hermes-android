package com.hermes.client.notifications

import com.hermes.client.data.network.ServerEvent
import com.hermes.client.data.network.str

/**
 * Pure mapping from a gateway event to a notification (or null). Only `approval.request` is a
 * notifiable event on the app's WebSocket (see the note on [Notif]); everything else returns null.
 * A stable id is derived from the session so repeats of the same event update rather than stack.
 */
fun toNotificationSpec(event: ServerEvent, prefs: NotificationPrefs): NotificationSpec? {
    if (!prefs.enabled) return null
    val sid = event.sessionId ?: return null
    var id = (event.type + sid).hashCode()
    // Never collide with HermesNotifier.SERVICE_NOTIFICATION_ID (1001) — that id belongs to the
    // ongoing foreground-service notification, and notify()-ing over it would clobber it.
    if (id == 1001) id = 1002
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
        else -> null
    }
}
