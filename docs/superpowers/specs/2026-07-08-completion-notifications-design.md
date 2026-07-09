# Completion Notifications — Design

**Date:** 2026-07-08
**Status:** Approved (brainstorming) → implementation plan next
**Branch:** `feature/completion-notifications`
**Source:** improvement roadmap Phase 2 · Wave 1 (`docs/ideas/improvement-roadmap-2026-07-07.md`); bridge-API spike corroborated by `docs/superpowers/specs/2026-07-03-notifications-approvals-only.md`.

## Goal

Notify the user when an agent **run finishes** and when the agent **needs input** — by mapping two already-existing gateway WS events (`run.completed`/`run.failed`, `clarify.request`) to system notifications. **Client-only**, reusing the existing `GatewayConnectionService` + `NotificationMapper` + `HermesNotifier`.

## The two rules (drive the design)

- **Needs-you (`approval.request` + `clarify.request`) → always notify** (HIGH channel), matching today's approval behavior. Foreground state is ignored.
- **Run-finished (`run.completed` / `run.failed`) → only when the app is backgrounded** (foreground = the user is watching it live). New DEFAULT-importance "Activity" channel.

## Hard constraints & known limits

- **No gateway/bridge API changes** — map existing events only. Reuse the notifications plumbing + channels + `extra_route` deep-link pattern.
- **Accepted limit (documented, not fixed here):** notifications only fire while the foreground service is alive + the phone can reach the gateway; they go dark when the FGS is killed (Android 15 ~6h `dataSync` cap, force-stop, reboot). A durable push (FCM/gateway) is a deferred follow-up.
- **Deferred:** cron-finished/failed push (no WS event exists — gateway follow-up) and payload enrichment for run-finished.
- No AI/assistant attribution.

## Grounding (from exploration)

- `toNotificationSpec(event: ServerEvent, prefs: NotificationPrefs): NotificationSpec?` is pure + unit-tested (`NotificationMapperTest`); single `approval.request` branch. It early-returns null when `!prefs.enabled` or `event.sessionId == null`; derives `id = (event.type + sid).hashCode()` (bumped off reserved `1001`) so same-type+session **replaces** (per-session de-dup); builds `route = "chat/$sid"`.
- `ServerEvent(type, sessionId, payload: JsonObject)` — `sessionId` is extracted **generically** (`payload["session_id"]` ?: `params["session_id"]`) for every event. `event.str("key")` safely reads a payload string (never throws). **`clarify.request` carries `question`** (`event.str("question")`, read today in `ChatUiState.reduce`). **`run.completed`/`run.failed` have no showable fields referenced client-side** — only the generic `session_id`.
- `NotificationSpec(id, channelId, title, body, route, actions, groupKey)` — **no per-spec importance** (channel-level only). `NotificationPrefs(enabled=false, approvals=true)`.
- `HermesNotifier.ensureChannels()`: `CHANNEL_APPROVALS` (HIGH), `CHANNEL_SERVICE` (MIN). `post(spec)` builds a `BigTextStyle` notification, tap → `MainActivity` `extra_route`, `mgr.notify(spec.id, …)` (same id replaces).
- `GatewayConnectionService`: collects `client.events`, runs each through `toNotificationSpec(event, latestPrefs)` + posts; keeps `@Volatile latestPrefs` fed by `settings.prefs.collect`. **No foreground awareness today.** `ProcessLifecycleOwner`/`lifecycle-process` is **not** a dependency (must add `androidx.lifecycle:lifecycle-process`, version `2.9.4` already pinned).

## Element 1 — Models + channels (`NotificationModels.kt`, `HermesNotifier.kt`)
- `Notif`: add `CHANNEL_ACTIVITY = "activity"`; `EVENT_RUN_COMPLETED = "run.completed"`, `EVENT_RUN_FAILED = "run.failed"`, `EVENT_CLARIFY = "clarify.request"`.
- `NotificationPrefs`: add `val runFinished: Boolean = true`.
- `HermesNotifier.ensureChannels()`: add `NotificationChannel(Notif.CHANNEL_ACTIVITY, "Activity", NotificationManager.IMPORTANCE_DEFAULT)`.

## Element 2 — Pure mapper (`NotificationMapper.kt`, unit-tested)
Add an `appInForeground: Boolean` parameter and the new branches:
```kotlin
fun toNotificationSpec(event: ServerEvent, prefs: NotificationPrefs, appInForeground: Boolean): NotificationSpec? {
    if (!prefs.enabled) return null
    val sid = event.sessionId ?: return null
    var id = (event.type + sid).hashCode(); if (id == 1001) id = 1002
    return when (event.type) {
        Notif.EVENT_APPROVAL -> if (!prefs.approvals) null else NotificationSpec(
            id, Notif.CHANNEL_APPROVALS, "Approval needed",
            event.str("prompt") ?: "The agent is waiting for your approval.",
            "chat/$sid", listOf(NotifAction("Approve", Notif.ACTION_APPROVE, sid), NotifAction("Deny", Notif.ACTION_DENY, sid)), "approval",
        )
        // Needs-you: always notify (ignores foreground); tap opens the chat to answer (no inline actions).
        Notif.EVENT_CLARIFY -> if (!prefs.approvals) null else NotificationSpec(
            id, Notif.CHANNEL_APPROVALS, "Needs your input",
            event.str("question") ?: "The agent has a question.", "chat/$sid", emptyList(), "approval",
        )
        // Run finished: only when backgrounded; generic body (no showable payload fields client-side).
        Notif.EVENT_RUN_COMPLETED -> if (!prefs.runFinished || appInForeground) null else NotificationSpec(
            id, Notif.CHANNEL_ACTIVITY, "Run finished", "Your agent finished — tap to view.", "chat/$sid", emptyList(), "run",
        )
        Notif.EVENT_RUN_FAILED -> if (!prefs.runFinished || appInForeground) null else NotificationSpec(
            id, Notif.CHANNEL_ACTIVITY, "Run failed", "The agent run failed — tap to view.", "chat/$sid", emptyList(), "run",
        )
        else -> null
    }
}
```
(The existing `NotificationMapperTest` case asserting `run.completed → null` must be updated to the new behavior.)

## Element 3 — Foreground flag in the service (`GatewayConnectionService.kt`, `build.gradle.kts`)
- Add dependency `androidx.lifecycle:lifecycle-process` (2.9.4) to the version catalog + `app/build.gradle.kts`.
- In `onCreate`, on the main thread, observe process lifecycle into a `@Volatile private var appInForeground = false`:
```kotlin
android.os.Handler(android.os.Looper.getMainLooper()).post {
    androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(
        androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> appInForeground = true
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> appInForeground = false
                else -> {}
            }
        },
    )
}
```
- Pass the flag into the mapper in the event collector: `toNotificationSpec(event, latestPrefs, appInForeground)?.let { notifier.post(it) }`.

## Element 4 — Settings (`NotificationsScreen.kt` + the prefs store)
- Persist `runFinished` in the same DataStore that backs `enabled`/`approvals` (mirror the `approvals` key). Default `true`.
- Add a **"Run finished"** toggle row to `NotificationsScreen` (shown when notifications are enabled), bound to `runFinished`. Leave the existing "approval alerts" toggle governing both approvals **and** clarify (both are needs-you); optionally relabel it "Needs-you alerts".

## Testing

- **Unit (pure), TDD — `NotificationMapperTest` (extend):**
  - `approval.request` → spec regardless of `appInForeground` (both true/false); null when `prefs.approvals=false`.
  - `clarify.request` → spec with body from `question`, regardless of foreground; null when `prefs.approvals=false`.
  - `run.completed` → spec when `!appInForeground`; **null when `appInForeground`**; null when `prefs.runFinished=false`.
  - `run.failed` → spec ("Run failed") when `!appInForeground`.
  - `event.sessionId == null` → null; `!prefs.enabled` → null.
- **On-device:** with notifications enabled, background the app, drive a `run.completed` (mock) → a "Run finished" notification appears; tapping it deep-links to that chat. Drive `run.completed` while foregrounded → **no** notification. Drive `clarify.request` (backgrounded or fore) → a "Needs your input" notification → tap opens the chat. Toggle "Run finished" off → no run notification. Approvals unchanged.

## Not doing (YAGNI) / deferred

- Cron-finished/failed push (no WS event — gateway follow-up); FCM/durable push (survives FGS death — gateway follow-up).
- Run-finished payload enrichment (title/preview) — generic body until the gateway's `run.*` fields are verified.
- The finer "app foreground but a *different* session open" case for run-finished — v1 gates on app-backgrounded only.
- Inline actions on clarify (needs a typed/option answer) — tap-to-open instead.
