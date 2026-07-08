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

    // `approval.request` is the only notifiable event on the app's WebSocket (/api/ws). Verified
    // against the gateway source: it broadcasts approval + run/tool/message-stream lifecycle events,
    // but NOT cron-finished (cron delivers to messaging platforms) or a messaging-inbound event —
    // so those aren't offered here. See docs/superpowers/specs/2026-07-03-notifications-approvals-only.
    const val EVENT_APPROVAL = "approval.request"
    const val EVENT_RUN_COMPLETED = "run.completed"
    const val EVENT_RUN_FAILED = "run.failed"
    const val EVENT_CLARIFY = "clarify.request"

    const val ACTION_APPROVE = "approve"
    const val ACTION_DENY = "deny"
}
