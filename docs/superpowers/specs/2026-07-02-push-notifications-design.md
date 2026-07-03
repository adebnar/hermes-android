# Push Notifications — Design

**Date:** 2026-07-02
**Status:** Approved (brainstorming) → implementation plan next
**Branch:** `feature/push-notifications`

## Goal

Make Hermes for Android proactive so it is useful when the app is not open: notify the
user about **approval requests**, **cron-run completions**, and **messaging-platform
replies**, and let them act (approve/deny, or tap to open the relevant session).

## Chosen architecture — foreground-service WebSocket

A foreground service holds the app-wide gateway WebSocket and posts **local** notifications
when notifiable events arrive. Client-only: **no gateway changes, no FCM, no Firebase.**

Rationale over the alternatives:
- **WorkManager poll** — battery-light but delayed and cannot do live approve/deny; a poor
  fit for time-sensitive approvals.
- **Gateway-pushed FCM** — works when the app is killed and off-VPN, but needs gateway-side
  changes + a Firebase project + `google-services`, and the self-hosted gateway would need
  its own internet + FCM credentials. Large cross-repo effort; deferred.

Trade-offs accepted: a persistent low-priority *"Hermes — connected"* notification (Android
requires one for a foreground service) and battery use while enabled; events only fire when
the device can currently reach the gateway (e.g. Tailscale up). Both are acceptable for an
**opt-in, off-by-default** feature.

## Scope

- **Notify on:** approval requests (with inline Approve/Deny), cron-run finished (success or
  failure), messaging-platform replies.
- **Active profile only** (v1): the WebSocket connects with the active profile's config, so
  one connection covers one tenant. Cross-profile simultaneous connections are deferred.
- **Off by default**, enabled from a new Settings → Notifications screen.

## Components (isolated, independently testable)

1. **`GatewayConnectionService`** (foreground service) — owns the WS lifetime while
   notifications are enabled, independent of any open chat. Reuses the existing
   `@Singleton HermesGatewayClient` (chat screens share the same client — no double
   connection). Observes `HermesGatewayClient` events and delegates to the notifier.
   Depends on: `HermesGatewayClient`, `HermesNotifier`, `NotificationSettings`.

2. **`HermesNotifier`** — the pure decision unit plus the Android posting:
   - `fun toSpec(event: ServerEvent, settings: NotificationPrefs): NotificationSpec?` — pure,
     unit-tested. Maps a `ServerEvent` to a `NotificationSpec` (channel, title, body, tap
     route, actions) or `null` when the event is not notifiable or its per-type toggle is off.
   - `fun post(spec: NotificationSpec)` — builds and posts via `NotificationManagerCompat`.
   Depends on: `NotificationManagerCompat`, channel definitions.

3. **`NotificationSpec`** — a small data class describing a notification independent of Android
   APIs (channel id, title, body, route, list of actions). Keeps `toSpec` pure and testable.

4. **`NotificationActionReceiver`** (`BroadcastReceiver`) — handles the **Approve/Deny**
   actions. Reads the session id + decision from the intent and calls the approval RPC via
   `HermesGatewayClient` (the same path `ChatViewModel.approve` uses). Cancels the notification.
   Depends on: `HermesGatewayClient`.

5. **`NotificationSettings`** (DataStore) — persists the master toggle and per-type flags
   (approvals / cron / messaging). Exposes a `Flow<NotificationPrefs>`; `setEnabled`,
   `setType`.

6. **`NotificationsScreen`** (Settings sub-page) — master toggle + per-type toggles, the
   `POST_NOTIFICATIONS` permission request flow (API 33+), and copy noting the background
   connection. Enabling starts `GatewayConnectionService`; disabling stops it. Reached from the
   existing Settings hub.

7. **Manifest** — `POST_NOTIFICATIONS` permission, `FOREGROUND_SERVICE` (+ the appropriate
   `FOREGROUND_SERVICE_*` type, likely `dataSync`), the `<service>` and `<receiver>` entries.

## Event → notification mapping

| Event | Notification | Tap | Actions |
|-------|--------------|-----|---------|
| approval (confirmed in code) | high-priority: "Approval needed — <prompt>" | open session | **Approve**, **Deny** |
| cron-run finished | "<job> — success/failure" | `chat/<sessionId>` (run output) | — |
| messaging reply | "Reply on <platform>: <preview>" | open session | — |

Each notification is tagged with the profile + session id; notifications group by profile.

## Known uncertainty (verify during implementation)

Only the **approval** event type is confirmed present in `ServerEvent`/`ChatViewModel`. The
exact gateway event-type strings for **cron-finished** and **messaging-reply** are not yet
referenced in the client. Mitigation:
- Centralize the type→spec mapping in `HermesNotifier.toSpec` so adjusting event names is a
  one-line change.
- Verify the real event types against the live gateway (or the mock) **before shipping**. If
  the gateway emits no distinct cron-finished event, that item is deferred (the approval and
  messaging cases still ship).

## Testing

- **Unit** (`HermesNotifierTest`): `toSpec` produces the right channel/title/route/actions for
  approval, cron, and messaging events; returns `null` for unrelated events and for events
  whose per-type toggle is off; respects the master toggle.
- **On-device** (emulator + mock gateway): extend the mock `mockgw.py` WebSocket to emit
  approval / cron-finished / messaging events, and verify the service posts notifications, the
  Approve/Deny actions round-trip, and taps open the right screen.

## Not doing (YAGNI)

FCM / gateway-side changes; cross-profile simultaneous connections; agent-turn-completed
notifications (not selected); notification history/inbox; rich media in notifications.
