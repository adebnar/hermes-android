package com.hermes.client.notifications

import com.hermes.client.data.network.ServerEvent
import com.hermes.client.data.network.str

/**
 * Pure mapping from a gateway event to a notification (or null). `approval.request` and
 * `clarify.request` always notify (they need the user's input regardless of whether the app is
 * foregrounded); `run.completed`/`run.failed` only notify when the app is backgrounded — see the
 * note on [Notif]. A stable id is derived from the session so repeats of the same event update
 * rather than stack.
 */
fun toNotificationSpec(event: ServerEvent, prefs: NotificationPrefs, appInForeground: Boolean): NotificationSpec? {
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
        // Needs-you: always notify (ignores foreground); tap opens the chat to answer.
        Notif.EVENT_CLARIFY -> if (!prefs.approvals) null else NotificationSpec(
            id = id, channelId = Notif.CHANNEL_APPROVALS, title = "Needs your input",
            body = event.str("question") ?: "The agent has a question.",
            route = "chat/$sid", actions = emptyList(), groupKey = "approval",
        )
        // Run finished: only when backgrounded; generic body (no showable payload fields client-side).
        Notif.EVENT_RUN_COMPLETED -> if (!prefs.runFinished || appInForeground) null else NotificationSpec(
            id = id, channelId = Notif.CHANNEL_ACTIVITY, title = "Run finished",
            body = "Your agent finished — tap to view.", route = "chat/$sid", actions = emptyList(), groupKey = "run",
        )
        Notif.EVENT_RUN_FAILED -> if (!prefs.runFinished || appInForeground) null else NotificationSpec(
            id = id, channelId = Notif.CHANNEL_ACTIVITY, title = "Run failed",
            body = "The agent run failed — tap to view.", route = "chat/$sid", actions = emptyList(), groupKey = "run",
        )
        else -> null
    }
}
