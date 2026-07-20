package com.hermes.client.notifications

import com.hermes.client.data.progress.RunProgress

/**
 * Pure mapping from run state to a notification description, or null when nothing should be
 * shown. Mirrors [toNotificationSpec]: all decisions live here so they are testable without
 * Android, and [HermesNotifier] only renders.
 */
fun RunProgress.toSpec(prefs: NotificationPrefs): RunProgressSpec? {
    if (!prefs.enabled || !prefs.runProgress) return null
    if (!running) return null
    val tenant = profile?.takeIf { it.isNotBlank() }
    return RunProgressSpec(
        title = if (tenant != null) "$tenant · agent running" else "Agent running",
        body = tool?.let { "Calling tool: $it" } ?: "Working…",
        done = done,
        total = total,
        indeterminate = !determinate,
        route = sessionId?.let { "chat/$it" },
        shortText = if (determinate) "$done/$total" else null,
    )
}
