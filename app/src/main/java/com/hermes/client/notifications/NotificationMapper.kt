package com.hermes.client.notifications

import com.hermes.client.data.network.ServerEvent
import com.hermes.client.data.network.bool
import com.hermes.client.data.network.str
import com.hermes.client.ui.chat.ApprovalTier
import com.hermes.client.ui.chat.tierFor

/**
 * Pure mapping from a gateway event to a notification (or null). `approval.request` and
 * `clarify.request` always notify (they need the user's input regardless of whether the app is
 * foregrounded); `message.complete` (run finished) and `error` (run failed) only notify when the
 * app is backgrounded — see the note on [Notif] for why these are the real /api/ws events. A stable
 * id is derived from the session so a turn's repeated `message.complete`s update one notification
 * rather than stack.
 */
fun toNotificationSpec(event: ServerEvent, prefs: NotificationPrefs, appInForeground: Boolean): NotificationSpec? {
    if (!prefs.enabled) return null
    val sid = event.sessionId ?: return null
    var id = (event.type + sid).hashCode()
    // Never collide with HermesNotifier.SERVICE_NOTIFICATION_ID (1001) or the run-progress
    // notification id (1003) — those ids belong to the ongoing foreground-service notification
    // and the live run-progress notification, and notify()-ing over either would clobber it.
    if (id == 1001 || id == 1003) id = 1002
    return when (event.type) {
        Notif.EVENT_APPROVAL -> if (!prefs.approvals) null else {
            val elevated = tierFor(event.bool("allow_permanent") ?: false) == ApprovalTier.ELEVATED
            NotificationSpec(
                id = id,
                channelId = Notif.CHANNEL_APPROVALS,
                title = "Approval needed",
                body = event.str("description")?.ifBlank { null }
                    ?: event.str("command")?.ifBlank { null }
                    ?: "The agent is waiting for your approval.",
                route = "chat/$sid",
                actions = if (elevated) listOf(NotifAction("Deny", Notif.ACTION_DENY, sid))
                          else listOf(
                              NotifAction("Allow once", Notif.ACTION_ALLOW_ONCE, sid),
                              NotifAction("Session", Notif.ACTION_ALLOW_SESSION, sid),
                              NotifAction("Deny", Notif.ACTION_DENY, sid),
                          ),
                groupKey = "approval",
            )
        }
        // Needs-you: always notify (ignores foreground); tap opens the chat to answer.
        Notif.EVENT_CLARIFY -> if (!prefs.approvals) null else NotificationSpec(
            id = id, channelId = Notif.CHANNEL_APPROVALS, title = "Needs your input",
            body = event.str("question") ?: "The agent has a question.",
            route = "chat/$sid",
            actions = listOf(NotifAction("Reply", Notif.ACTION_REPLY, sid, reply = true, requestId = event.str("request_id"))),
            groupKey = "approval",
        )
        // Run finished: `message.complete` is the end-of-turn event on /api/ws (the app also uses it
        // to stop the "generating" spinner). Only notify when backgrounded; the per-session id above
        // collapses a turn's repeated message.completes into one updating notification, not a stack.
        Notif.EVENT_MESSAGE_COMPLETE -> if (!prefs.runFinished || appInForeground) null else NotificationSpec(
            id = id, channelId = Notif.CHANNEL_ACTIVITY, title = "Run finished",
            body = "Your agent finished — tap to view.", route = "chat/$sid", actions = emptyList(), groupKey = "run",
        )
        // Run failed: the gateway emits `error` on a turn-fatal failure (carries a message).
        Notif.EVENT_ERROR -> if (!prefs.runFinished || appInForeground) null else NotificationSpec(
            id = id, channelId = Notif.CHANNEL_ACTIVITY, title = "Run failed",
            body = event.str("message")?.takeIf { it.isNotBlank() } ?: "The agent run failed — tap to view.",
            route = "chat/$sid", actions = emptyList(), groupKey = "run",
        )
        else -> null
    }
}
