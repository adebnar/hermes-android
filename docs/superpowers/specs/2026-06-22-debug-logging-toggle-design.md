# Debug Logging Toggle — Design

**Date:** 2026-06-22
**Status:** Approved (key decisions confirmed with user)

## Problem

Users hit transient/unexplained errors in the app — notably **"message not found"** when
opening a session from the side menu — that we can't reproduce or diagnose after the fact.
There is currently **no logging in the app at all** (zero `Log`/Timber calls). We need a way
for a user to capture exactly what the app sent/received around a failure and share it,
without needing a computer or `adb`.

"message not found" is not a string in our source; it is a **gateway-emitted error**. The
REST history fetch on session-open is swallowed to an empty thread, so the visible error
arrives over the **WebSocket** — most likely the reducer's `"error"` event branch rendering
`event.str("message")`, or an `RpcErrorReply` to `session.resume`. The log must capture the
WS RPC/event sequence to confirm which.

## Goals

- A user-toggleable diagnostic log, **off by default**, zero overhead when off.
- When on, capture enough to explain failures like "message not found".
- Viewable and shareable **on the device** (no adb).
- Never leak the session token.

## Non-goals

- Persistent log history across app kills (in-memory ring buffer only).
- Remote/automatic crash reporting.
- Logging full user message content (we log structure/metadata, not bodies).

## Design

### Core facility — `DebugLog`
A process-wide Kotlin `object` (callable from non-DI code like the WS listener).

- `@Volatile var` enabled flag; `log(category, message)` is a cheap no-op when disabled.
- In-memory ring buffer (cap **500** entries), guarded by a lock.
- `entries: StateFlow<List<LogEntry>>` for the live in-app view.
- Mirrors each entry to `android.util.Log` under tag `HermesDebug`.
- `LogEntry(timeMillis, category, message)`; categories: `ws`, `rest`, `session`, `error`.
- Token redaction: a registered token string is masked (`***`) in any logged message.
- `clear()` and `export(): String` (formatted, newest-last) for Share.

### Persistence + sync
- `SettingsStore` gains `debugLogging: Flow<Boolean>` (default false) + `setDebugLogging`.
- `HermesApp.onCreate` collects `debugLogging` → `DebugLog.setEnabled(...)`, so logging is
  active at launch (before the Diagnostics screen is opened).
- `AppModule` registers the configured session token with `DebugLog` for redaction.

### Capture points (only meaningful when enabled)
- **`HermesGatewayClient`**: socket open/close/failure; each `call(method)`; `RpcResult` /
  `RpcErrorReply` (id, code, message); `gateway.ready`; and **error events**.
- **`HermesRestApi`**: request `METHOD /path` (no token) + response code; error body.
- **`ChatViewModel.open`**: `open(id)`, `resume → handle`, and caught exceptions.

### UI — Settings → Diagnostics
- New `SettingsScreen` entry "Diagnostics" → route `settings_diagnostics`.
- `DiagnosticsScreen` + `DiagnosticsViewModel`: a `Switch` bound to `debugLogging`, a
  **Clear** button, a **Share** button (`ACTION_SEND` of `DebugLog.export()`), and a
  reverse-chronological live list of entries.

## Testing
- `DebugLog` unit tests: no-op when disabled; ring-buffer cap; token redaction; `clear`;
  `export` format; `entries` emits when enabled.

## Decisions (confirmed)
- **Log access:** in-app Diagnostics screen + Share.
- **Capture scope:** everything (WS + REST + session lifecycle + errors), token-redacted.
