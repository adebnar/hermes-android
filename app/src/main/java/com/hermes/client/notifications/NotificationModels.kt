package com.hermes.client.notifications

/** User's notification preferences (persisted); off by default. */
data class NotificationPrefs(
    val enabled: Boolean = false,
    val approvals: Boolean = true,
    val runFinished: Boolean = true,
)

/** An inline notification action (Approve/Deny) carrying the target session. */
data class NotifAction(val label: String, val action: String, val sessionId: String)

/** A platform-independent description of a notification, so mapping stays unit-testable. */
data class NotificationSpec(
    val id: Int,
    val channelId: String,
    val title: String,
    val body: String,
    val route: String?,
    val actions: List<NotifAction>,
    val groupKey: String,
)

/** Channel ids, gateway event-type strings, and action names in one place. */
object Notif {
    const val CHANNEL_APPROVALS = "approvals"
    const val CHANNEL_SERVICE = "service"
    const val CHANNEL_ACTIVITY = "activity"

    // Notifiable events on the app's WebSocket (/api/ws), verified against the gateway source:
    //  - approval.request / clarify.request -> the agent needs the user (always notify)
    //  - message.complete                   -> a turn finished (the "Run finished" alert)
    //  - error                              -> a turn failed (the "Run failed" alert)
    // The gateway's /api/ws emits `message.complete` at end-of-turn (tui_gateway/server.py); it does
    // NOT emit `run.completed`/`run.failed` (those belong to the separate messaging-platform HTTP/SSE
    // API the app never connects to) — which is why the earlier run.completed mapping never fired.
    // Cron-finished isn't offered (cron delivers to messaging platforms).
    const val EVENT_APPROVAL = "approval.request"
    const val EVENT_CLARIFY = "clarify.request"
    const val EVENT_MESSAGE_COMPLETE = "message.complete"
    const val EVENT_ERROR = "error"

    const val ACTION_ALLOW_ONCE = "allow_once"
    const val ACTION_DENY = "deny"
}
