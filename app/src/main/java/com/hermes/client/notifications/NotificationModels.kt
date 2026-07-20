package com.hermes.client.notifications

/** User's notification preferences (persisted); off by default. */
data class NotificationPrefs(
    val enabled: Boolean = false,
    val approvals: Boolean = true,
    val runFinished: Boolean = true,
    val runProgress: Boolean = true,
)

/**
 * An inline notification action carrying the target session. [reply] = true marks a direct-reply
 * action (Android RemoteInput text field) rather than a plain button.
 */
data class NotifAction(
    val label: String,
    val action: String,
    val sessionId: String,
    val reply: Boolean = false,
    val requestId: String? = null,
)

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

/**
 * A platform-independent description of the live run-progress notification, so mapping stays
 * unit-testable. [indeterminate] means no todo counts are available yet; [shortText] is the
 * status-bar chip text used on API 36+ promoted notifications (null when indeterminate).
 */
data class RunProgressSpec(
    val title: String,
    val body: String,
    val done: Int,
    val total: Int,
    val indeterminate: Boolean,
    val route: String?,
    val shortText: String?,
)

/** Channel ids, gateway event-type strings, and action names in one place. */
object Notif {
    const val CHANNEL_APPROVALS = "approvals"
    const val CHANNEL_SERVICE = "service"
    const val CHANNEL_ACTIVITY = "activity"
    // Live in-flight run progress. IMPORTANCE_LOW (not MIN like CHANNEL_SERVICE) so the ongoing
    // progress notification is actually glanceable in the shade and eligible for promotion to a
    // status-bar Live Update on API 36+, while still making no sound.
    const val CHANNEL_RUN_PROGRESS = "run_progress"

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
    // Run-lifecycle events consumed by the run-progress reducer (not by toNotificationSpec).
    // `session.info` carries "running": bool and is the authoritative busy/idle backstop.
    const val EVENT_MESSAGE_START = "message.start"
    const val EVENT_TOOL_START = "tool.start"
    const val EVENT_TOOL_COMPLETE = "tool.complete"
    const val EVENT_SESSION_INFO = "session.info"

    const val ACTION_ALLOW_ONCE = "allow_once"
    const val ACTION_ALLOW_SESSION = "allow_session"
    const val ACTION_DENY = "deny"
    const val ACTION_REPLY = "reply"

    // RemoteInput result key for the inline reply on a clarify ("Needs your input") notification.
    const val KEY_REPLY_TEXT = "reply_text"
}
