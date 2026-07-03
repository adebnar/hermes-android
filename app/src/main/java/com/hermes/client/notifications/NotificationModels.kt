package com.hermes.client.notifications

/** User's notification preferences (persisted); off by default. */
data class NotificationPrefs(
    val enabled: Boolean = false,
    val approvals: Boolean = true,
    val cron: Boolean = true,
    val messaging: Boolean = true,
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
    const val CHANNEL_ACTIVITY = "activity"
    const val CHANNEL_SERVICE = "service"

    // approval.request is confirmed in ChatUiState. The other two are BEST-GUESS — verify in Task 8.
    const val EVENT_APPROVAL = "approval.request"
    const val EVENT_CRON_DONE = "cron.completed"
    const val EVENT_MSG = "message.received"

    const val ACTION_APPROVE = "approve"
    const val ACTION_DENY = "deny"
}
