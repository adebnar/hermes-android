# Notifications: Approvals-Only (event-type finding)

**Date:** 2026-07-03
**Status:** Implemented on `feature/notifications-approvals-only`
**Supersedes the cron/messaging parts of:** `docs/superpowers/specs/2026-07-02-push-notifications-design.md`

## Finding

The push-notifications feature shipped with three toggles — **Approvals**, **Cron runs**, **Messaging replies** — but the cron/messaging event-type strings were best-guess placeholders (`cron.completed`, `message.received`). Verifying against the gateway source (`~/.hermes/hermes-agent`) showed:

The app's WebSocket (`/api/ws`, served by `gateway/platforms/api_server.py`) broadcasts exactly:
`approval.request`, `approval.responded`, `run.started/completed/failed/cancelled`, `tool.started/completed`, `message.started/delta`, `assistant.completed`, `reasoning.available`, `error`, `done`.

- **`approval.request`** — real; the only notifiable event. Confirmed working on-device.
- **Cron finished** — no such event on this stream. Cron jobs run in the scheduler and **deliver results to messaging platforms** (`gateway/delivery.py`), not the dashboard WS. The `run.completed`/`run.failed` events that *are* on the WS fire on **every interactive turn** (and dashboard-initiated async runs), not cron — so they can't stand in for "cron finished".
- **Messaging reply** — `message.received` exists only in the gateway's **tests**, never in production; nothing equivalent reaches the app's WS.

So under the **no-bridge-changes** constraint, cron and messaging notifications cannot fire. Keying `run.completed`/`run.failed` for "cron" would notify on every chat turn and still be wrong.

## Decision

**Approvals-only.** Remove the Cron and Messaging toggles and their (dead) mapping; keep Approvals. The Notifications screen now offers only *Enable* + *Approval requests*, both of which actually work.

## Changes

- `NotificationPrefs` → `(enabled, approvals)` (dropped `cron`, `messaging`).
- `Notif` → dropped `CHANNEL_ACTIVITY`, `EVENT_CRON_DONE`, `EVENT_MSG`.
- `toNotificationSpec` → only the `approval.request` branch.
- `NotificationSettings` → dropped the cron/messaging keys + setters.
- `NotificationsScreen` / `NotificationsViewModel` → dropped the two toggle rows + setters.
- `HermesNotifier.ensureChannels` → dropped the unused `activity` channel.
- `NotificationMapperTest` → dropped the cron/messaging cases; added assertions that `run.completed`/`message.received` map to null.

## Future (needs a different mechanism, not this stream)

- **Cron-finished** could be done with a periodic REST poll of `/api/cron/jobs` (`last_run_at` + `last_status`) via WorkManager — a separate opt-in feature (delayed, battery cost), not a WS push.
- **Messaging** has no client-visible signal today without a gateway change.
