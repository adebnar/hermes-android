# Live in-flight run progress — design

**Status:** approved
**Branch:** `feature/live-run-progress` (off `dev`)
**Roadmap item:** A1 in `docs/ideas/2026-07-16-competitive-refresh.md` (P0)

## Problem

> How might we make an agent run that is *already in flight* glanceable from outside the app?

Hermes notifies on **completion** and renders nothing during the minutes a run is actually
executing. The only in-flight signal is a spinner inside an open chat screen. A user who
backgrounds the app has no way to tell whether their agent is working, stuck, or finished.
Cursor iOS streams this to the lock screen via Live Activities; Android 16 provides the
equivalent through `Notification.ProgressStyle` and promoted "Live Updates".

## Scope

Client-only. **No gateway changes.** Progress is *opportunistic*: indeterminate
("acme · agent running · Calling tool: web_search") by default, upgrading to a determinate
"3/5" bar whenever the model drives the `todo` tool.

### Why not a true "step N/M"

The roadmap proposed "step 3/5". That is not achievable on this wiring. The only genuine
iteration counter in the agent codebase (`api_call_count` vs `max_iterations`,
`agent/conversation_loop.py:715-766`) is wired into `gateway/run.py` and `acp_adapter/server.py`
only — never into `tui_gateway/server.py`, which is the path this app's WebSocket uses
(`grep -n step_callback tui_gateway/server.py` returns nothing). A generic determinate bar
therefore requires a gateway change, which is explicitly out of scope for this wave.

The `todo` tool's list is the one real, client-reachable proxy, and it is the same signal the
Electron desktop client already uses for its "Tasks N/M" chip
(`apps/desktop/src/app/chat/composer/status-stack/index.tsx:49`). Using it is desktop parity.

## Verified gateway facts

All confirmed in source; do not re-derive during implementation.

| Fact | Evidence |
|---|---|
| Events emitted via one `_emit(event, sid, payload)` dispatcher | `tui_gateway/server.py:1187-1191` |
| `message.start` | `server.py:9894` |
| `tool.start` — payload has `name` | `server.py:4048` |
| `tool.complete` — payload has `name` | `server.py:4095` |
| `message.complete` | `server.py:10157` |
| `session.info` carries `"running": bool` | `server.py:3805`, re-emitted `:10335-10339` |
| On `tool.complete` where `name == "todo"`, payload gains `todos` = full list | `server.py:4076-4082` |
| A todo item is `{id, content, status}`; `status ∈ {pending, in_progress, completed, cancelled}` | `tools/todo_tool.py:21,59` |
| `tool.start`/`tool.complete` are gated on `_tool_progress_enabled(sid)` | `server.py:4048,4095` |
| That gate defaults to `"all"`, i.e. enabled; only `tool_progress_mode: "off"` suppresses | `server.py:3096-3105` |

**`tool.progress` is NOT emitted on this path** — it exists only in the unrelated
`gateway/platforms/api_server.py`. Do not use it.

## Architecture

### State location

Run state must survive backgrounding and drive a notification, so it cannot live in
`ChatUiState` (per-open-session, owned by `ChatViewModel`, gone when the app is backgrounded).

It is held as a **pure reducer's** value inside `GatewayConnectionService`, mirroring exactly how
that service already holds `latestPrefs` and `appInForeground` and calls the pure
`toNotificationSpec`. This adds no second collector on `client.events` and no new singleton, and
leaves all logic unit-testable as pure functions.

A singleton `StateFlow` would only be required to feed an in-app chip, which is out of scope
(see Not Doing). Promote it then, not now.

### Data flow

```
HermesGatewayClient.events
  └─> GatewayConnectionService (existing single collector)
        ├─> toNotificationSpec(...)        // existing: approvals / clarify / finished / error
        └─> runProgress = runProgress.reduce(event)   // new: pure
              └─> runProgress.toSpec(profileName)     // new: pure, null when idle
                    ├─ non-null -> notifier.postRunProgress(spec)
                    └─ null     -> notifier.cancelRunProgress()
```

### Event → state

| Event | Effect on `RunProgress` |
|---|---|
| `message.start` | `running = true`; reset `tool`, `done`, `total`; capture `sessionId` |
| `tool.start` | `tool = payload["name"]` |
| `tool.complete`, `name == "todo"` | `done` = count(`status == "completed"`), `total` = count(`status != "cancelled"`) |
| `tool.complete`, other tool | clear `tool` |
| `message.complete` | `running = false`; reset |
| `error` | `running = false`; reset |
| `session.info` | `running = payload["running"]`; when false, reset |

`session.info` is the **authoritative backstop**. `message.complete` alone misses interrupted and
compacted turns, which would otherwise strand a permanent "running…" notification. Android does
not parse `session.info` today (`ServerEvent.kt:11-25` reads only `type`/`sessionId`/`payload`);
wiring it closes a real correctness gap.

### Determinate rule

`total > 0` means determinate. `cancelled` todos are **excluded from `total`**: a cancelled task
never completes, so counting it would stall the bar permanently below 100%. This is a deliberate
divergence from the desktop client, which uses raw `items.length`.

When a session runs with `tool_progress_mode: "off"`, no `tool.*` events arrive, so `total`
stays 0 and the surface degrades to indeterminate. That is correct and expected behaviour, not
an error state.

## Notification

A **new `run_progress` channel at `IMPORTANCE_LOW`** — present and glanceable in the shade,
silent, and eligible for promotion on API 36.

Reusing the existing `service` channel is rejected: it is `IMPORTANCE_MIN`, which suppresses the
status-bar presence this feature exists to provide. The static MIN "Connected" foreground-service
notification therefore stays exactly as it is, and the progress notification is a **separate
ongoing notification** at `RUN_PROGRESS_NOTIFICATION_ID = 1003` (clear of the existing `1001` and
the mapper's `1002` fallback).

Lifecycle: posted on run start, updated on `tool.start` and on todo counts, cancelled on run end
(`message.complete` / `error` / `session.info running=false`) **and** in `Service.onDestroy()` so
a stopped service never strands it.

### Rendering

- **API ≥ 36:** `ProgressStyle` + promoted ongoing (Live Update).
- **API 26–35:** `NotificationCompat.setProgress(total, done, indeterminate)` + `setOngoing(true)`.

Every API-36 call site must be runtime-gated on `Build.VERSION.SDK_INT >= 36`
(`minSdk = 26`, `compileSdk`/`targetSdk = 37` — `app/build.gradle.kts:21,25,26`).

**Implementation risk to resolve in the plan:** confirm whether `NotificationCompat.ProgressStyle`
exists in `androidx.core 1.16.0` (`gradle/libs.versions.toml:12`). If it does not, the API-36
branch uses the platform `Notification.Builder` directly rather than upgrading the dependency.

### Content

- **Title:** `"acme · agent running"` — tenant name from `ProfileManager.active`
  (`ProfileManager.kt:22-23`).
- **Body:** `"Calling tool: web_search"` when a tool is active, else `"Working…"`.
- **Colour:** tenant accent via `setColor(accentArgb(profile, dark))`
  (`ui/theme/ProfileAccent.kt:45`). Accent is correct here — this is chrome, not a
  down/error state.
- **Tap:** routes to `chat/$sessionId`, reusing the notifier's existing `openIntent` route
  mechanism.

### Preference

Add `runProgress: Boolean = true` to `NotificationPrefs` (`NotificationModels.kt:4-8`), persisted
in `NotificationSettings` (`NotificationSettings.kt`) alongside `enabled`/`approvals`/
`runFinished`, and exposed as a toggle in `ui/settings/NotificationsScreen.kt` beside the
existing switches. A persistent ongoing notification warrants an in-app switch rather than
forcing the user into system channel settings.

Progress is gated by the master `enabled` pref like everything else, which means it only appears
while the notifications toggle is on — that is what runs `GatewayConnectionService` at all. This
is a documented limitation, not something to work around in this wave.

## Multi-tenancy

The WebSocket is a single Hilt `@Singleton` bound to one active profile at a time
(`AppModule.kt:69,125`; `ProfileManager.kt:18-41`), and `ServerEvent` carries no profile field.
There is structurally no concurrent multi-tenant run, so the notification always represents "the
active profile's run" and attribution is a direct read of `ProfileManager.active`. Tenant names
in tests and docs use the generic `acme`/`globex` convention.

## Files

| File | Change |
|---|---|
| `data/progress/RunProgress.kt` | **Create** — `RunProgress` model, pure `reduce(ServerEvent)`, pure `toSpec(profileName)` |
| `notifications/NotificationModels.kt` | Modify — `RunProgressSpec`, `CHANNEL_RUN_PROGRESS`, `EVENT_*` constants, `runProgress` pref |
| `notifications/HermesNotifier.kt` | Modify — `run_progress` channel in `ensureChannels()`, `postRunProgress()`, `cancelRunProgress()`, API-36 split |
| `notifications/GatewayConnectionService.kt` | Modify — hold reduced state, drive notifier, cancel in `onDestroy()` |
| `data/network/ServerEvent.kt` | Modify — helpers to read `session.info.running` and the `todos` array |
| `data/repository/NotificationSettings.kt` | Modify — persist `runProgress` |
| `ui/settings/NotificationsScreen.kt` | Modify — toggle |
| `test/.../RunProgressTest.kt` | **Create** — reducer + spec-mapping tests |

## Testing

Pure-logic and reducer-style only, per repo convention — fakes plus `runTest`, no Compose UI
tests. Events are constructed with the `ev()` helper pattern from `ChatReducerTest`.

Cases:
1. `message.start` → running, indeterminate.
2. `tool.start` → tool name surfaces in the spec body.
3. todo `tool.complete` → determinate `done`/`total`.
4. `cancelled` todos excluded from `total`.
5. Second run resets counts from the previous run (no bleed-through).
6. `message.complete` → idle, `toSpec` returns null.
7. `error` → idle.
8. `session.info running=false` mid-run → idle (interrupt backstop).
9. No `tool.*` events (`tool_progress_mode: "off"`) → stays indeterminate while running.
10. Malformed/absent `todos` payload → no crash, stays indeterminate.

## Not doing (and why)

- **In-app progress chip.** The chat screen already shows a generating spinner; this feature's
  value is the *backgrounded* surface. Deferred rather than bundled, and the pure-reducer design
  makes promoting it to a singleton `StateFlow` cheap later.
- **Determinate `step N/M` for non-todo runs.** Requires the declined gateway change.
- **Cross-tenant concurrent progress.** Structurally impossible today (single active-profile WS);
  tracked separately as a gateway-side wave.
- **Auto-restarting the service** so progress works with notifications off. Out of scope; the
  opt-in limitation is documented instead.

## Open questions

None. Scope and rendering decisions are settled; the single implementation fork
(`NotificationCompat.ProgressStyle` availability in core 1.16.0) is resolved by inspection during
Task 1 of the plan and has a defined fallback.
