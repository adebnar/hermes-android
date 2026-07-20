package com.hermes.client.data.progress

import com.hermes.client.data.network.ServerEvent
import com.hermes.client.data.network.bool
import com.hermes.client.data.network.str
import com.hermes.client.data.network.todoCounts

/**
 * State of the agent run currently in flight, derived purely from gateway WebSocket events.
 *
 * Deliberately NOT part of ChatUiState: that is scoped to an open chat screen and dies when the
 * app is backgrounded, which is exactly when this state must survive to drive a notification.
 */
data class RunProgress(
    val running: Boolean = false,
    val sessionId: String? = null,
    val profile: String? = null,
    val tool: String? = null,
    val done: Int = 0,
    val total: Int = 0,
) {
    /** A determinate bar is only possible once the `todo` tool has reported a non-empty list. */
    val determinate: Boolean get() = total > 0
}

/**
 * Folds one gateway event into run state. Pure — no Android, no IO.
 *
 * `activeProfile` is latched into the run at the moment it starts (or restarts) so the
 * tenant travels with the run itself rather than being re-read from a mutable source at
 * notification-post time — a profile switch mid-run must never re-tag an in-flight run's
 * notification with a different tenant while its route still points at the original session.
 *
 * `session.info.running` is the authoritative backstop: `message.complete` alone misses
 * interrupted and compacted turns, which would otherwise strand a permanent "running" state.
 * Tool events are ignored while idle so a late/stray `tool.*` cannot resurrect a finished run.
 */
fun RunProgress.reduce(event: ServerEvent, activeProfile: String?): RunProgress = when (event.type) {
    "message.start" -> RunProgress(running = true, sessionId = event.sessionId, profile = activeProfile)

    "tool.start" -> if (!running) this else copy(tool = event.str("name")?.ifBlank { null })

    "tool.complete" -> when {
        !running -> this
        event.str("name") == "todo" -> {
            val (d, t) = event.todoCounts()
            copy(tool = null, done = d, total = t)
        }
        else -> copy(tool = null)
    }

    "message.complete", "error" -> RunProgress()

    // Authoritative busy/idle signal. A missing `running` field leaves state untouched.
    "session.info" -> when (event.bool("running")) {
        false -> RunProgress()
        true -> when {
            !running -> RunProgress(running = true, sessionId = event.sessionId, profile = activeProfile)
            // A differing (non-null) sessionId means a different run started — start fresh
            // rather than silently keeping the stale run's counts/sessionId.
            event.sessionId != null && event.sessionId != sessionId ->
                RunProgress(running = true, sessionId = event.sessionId, profile = activeProfile)
            else -> this
        }
        null -> this
    }

    else -> this
}
