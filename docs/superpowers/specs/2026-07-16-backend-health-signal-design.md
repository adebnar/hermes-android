# Backend-Health Signal — Design

**Wave:** Quick-wins wave 1 (from `docs/ideas/2026-07-16-competitive-refresh.md`). **Branch:** `feature/backend-health-signal` (off `dev`).

**Goal:** App-wide, proactive awareness of whether the self-hosted Hermes gateway is reachable and healthy — distinguishing *your phone is offline* from *the gateway is down* from *gateway up* — with zero visual clutter when everything is healthy.

**Positioning:** This is the one signal only a self-hosted client owes its user. Today the app only surfaces the WebSocket `ConnectionState` **inside an open chat** (`ChatScreen.kt:260` via `StatusDot`); on every other screen (sessions, activity, cron, …) there is no backend signal at all. A user discovers a dead gateway only when a send fails.

**Constraints:** Kotlin / Compose / Hilt / Material3, per-tenant accent. **Fully client-only** — no gateway changes. Uses the existing public `GET /api/status` endpoint (`HermesRestApi.gatewayStatus()` → `GatewayStatusDto { version, gateway_running, gateway_state }`, `Dtos.kt:8-12`). Standing repo constraints apply (no AI attribution; gitleaks before every push; tenant isolation; `main` only via approved PR).

---

## Scope decisions (locked)

1. **Ambition:** Proactive — actively probe `/api/status`, don't just passively reflect the WS socket. Distinguish device-offline vs gateway-unreachable vs gateway-healthy.
2. **Placement:** Global **down-strip** hosted by the shared outer `Scaffold` (`HermesNav.kt`), shown on **every** screen but **only when unhealthy**; a tiny **badge** on the `You` bottom-nav tab when unhealthy; tap either → a detail **bottom sheet** with a **Re-check** button. Nothing shown when healthy except the (absent) badge.
3. **Background reach:** **In-app only for v1.** Probe on app-resume + a light interval while foregrounded; stop when backgrounded. No push, no foreground-service changes. The state machine is built so a background-push variant is a small fast-follow (add a listener in the existing `GatewayConnectionService`).

---

## Architecture

Three well-bounded units: a **health model**, a **monitor** that produces it, and the **UI surface** that renders it. The monitor is the only stateful piece; the model and UI mapping are pure and independently testable.

### 1. Health model — `data/network/GatewayHealth.kt` (new)

Deliberately separate from `ConnectionState` (which is the WS socket lifecycle). This models the **backend**, sourced authoritatively from `/api/status` + device connectivity.

```kotlin
sealed interface GatewayHealth {
    /** Before the first probe completes. Renders nothing (no strip, no badge). */
    data object Unknown : GatewayHealth

    /** /api/status returned 2xx. */
    data class Healthy(val version: String?, val running: Boolean, val latencyMs: Long?) : GatewayHealth

    /** ConnectivityManager reports no network — the phone is offline, not the gateway. */
    data object DeviceOffline : GatewayHealth

    /** Network is up but /api/status failed (timeout, connection refused, non-2xx). */
    data class GatewayUnreachable(val detail: String?) : GatewayHealth
}

/** True when the down-strip and You-tab badge should show. */
fun GatewayHealth.isUnhealthy(): Boolean =
    this is GatewayHealth.DeviceOffline || this is GatewayHealth.GatewayUnreachable
```

`Healthy.running` reflects `GatewayStatusDto.gateway_running`; a 2xx with `running == false` is still `Healthy` (gateway reachable) but the sheet surfaces "reachable, not running" copy.

### 2. Monitor — `data/network/GatewayHealthMonitor.kt` (new, Hilt `@Singleton`)

Exposes `val health: StateFlow<GatewayHealth>` (initial value `Unknown`). Dependencies: `HermesRestApi`, a `ConnectivityManager` (from `@ApplicationContext`), and an app-scope `CoroutineScope`.

**Probe algorithm (`suspend fun probe()`):**
1. If `ConnectivityManager` reports no validated network → emit `DeviceOffline`, return (skip HTTP).
2. Else call `api.gatewayStatus()` inside `withTimeout(PROBE_TIMEOUT_MS = 5_000)`, timing it:
   - success → `Healthy(version, running = gateway_running, latencyMs = elapsed)`.
   - `HermesApiException` with 401 → `GatewayUnreachable("unauthorized")` (distinct copy; does not trigger the existing auth/setup redirect — this is a status hint only).
   - any other throwable / timeout → **one immediate retry**; if it also fails → `GatewayUnreachable(detail)` where `detail` is a short reason (`"timed out"`, `"unreachable"`, HTTP code). The single retry is the debounce that prevents a transient blip from flashing the strip.

**Cadence:**
- Observe `ProcessLifecycleOwner.get().lifecycle`: on `ON_RESUME`, probe immediately and start a loop that re-probes every `PROBE_INTERVAL_MS = 30_000`; on `ON_STOP`, cancel the loop (no background probing).
- Subscribe to the WS `ConnectionState` (already exposed by `HermesGatewayClient`); when it transitions to `Error`/`Disconnected`, trigger an immediate `probe()` (cheap early signal, coalesced so a flapping socket can't spam probes — ignore if a probe ran < 3s ago).

**Manual re-check:** `fun recheck()` launches an immediate `probe()` (used by the sheet's button).

`PROBE_TIMEOUT_MS`, `PROBE_INTERVAL_MS`, and the WS-coalesce window are named constants in a companion object.

### 3. UI surface

**`ShellViewModel` (`ui/nav/ShellViewModel.kt`, modify):** inject `GatewayHealthMonitor`; expose `val health: StateFlow<GatewayHealth> = monitor.health`. (This VM already backs the outer Scaffold chrome.)

**`HermesNav.kt` (modify):** in the outer `Scaffold`:
- Collect `shellVm.health`.
- Render `HealthStrip(health, onClick = { showHealthSheet = true })` in a top slot above the `NavHost` content — the composable returns/draws nothing when `!health.isUnhealthy()`, so healthy state adds no layout.
- On the `You` `NavigationBarItem`, wrap the icon in `BadgedBox` showing a `Badge` when `health.isUnhealthy()`.
- Host a `HealthSheet(health, onRecheck = shellVm::recheck, onDismiss = …)` `ModalBottomSheet`, opened by the strip or the badge tap.

**`ui/components/HealthStrip.kt` (new):** the strip composable, the sheet composable, and **pure mapping helpers**:
- `healthStripLabel(GatewayHealth): String?` — `DeviceOffline → "You're offline"`, `GatewayUnreachable → "Gateway unreachable"` (or "Gateway unauthorized" for the 401 detail), else `null`.
- `healthStripColor(GatewayHealth): Color` — semantic: red (`error`) for `GatewayUnreachable`, neutral/amber for `DeviceOffline`. **Down states are semantic, never per-tenant accent** (a down gateway looks the same in every tenant). Neutral chrome in the sheet (e.g. the Re-check button) may use `LocalProfileAccent`.
- Sheet body copy per state: healthy → "Gateway running · v{version} · {latencyMs} ms" (or "reachable, not running" when `running == false`); unreachable → detail + "Last checked {relative time}"; device-offline → "Your device is offline — Hermes will reconnect automatically."

**DI (`di/AppModule.kt`, modify):** `@Provides @Singleton fun provideGatewayHealthMonitor(api, @ApplicationContext context, appScope): GatewayHealthMonitor`. (If no app-scope `CoroutineScope` is already provided, the monitor creates its own `CoroutineScope(SupervisorJob() + Dispatchers.Default)` internally — check existing DI for an app scope first and reuse it.)

---

## Data flow

```
ProcessLifecycleOwner ─ON_RESUME/interval─┐
WS ConnectionState ─Error/Disconnected────┤→ GatewayHealthMonitor.probe()
HealthSheet "Re-check" ─recheck()─────────┘        │
                                                   │  ConnectivityManager? → DeviceOffline
                                                   │  else GET /api/status (5s, +1 retry)
                                                   ▼
                              GatewayHealthMonitor.health: StateFlow<GatewayHealth>
                                                   │
                                          ShellViewModel.health
                                                   │
                        ┌──────────────────────────┼───────────────────────────┐
                    HealthStrip (top slot,      You-tab Badge            HealthSheet (detail
                    only when unhealthy)        (when unhealthy)         + Re-check button)
```

## Error handling

- **Probe timeout / connection refused:** retried once, then `GatewayUnreachable`. Never throws out of the monitor — all exceptions are caught and mapped to a state.
- **401 unauthorized:** mapped to `GatewayUnreachable("unauthorized")` with distinct copy; v1 does **not** re-drive the setup/auth flow from here (that stays owned by the existing send/auth path) — this is an advisory signal only.
- **No network:** short-circuits to `DeviceOffline` without an HTTP attempt.
- **Missing/blank `version`:** sheet omits the version segment; `Healthy` still valid.

## Testing

**`GatewayHealthMonitorTest`** (fake `HermesRestApi` + fake connectivity provider + `runTest`/`StandardTestDispatcher`):
- healthy on 2xx (captures version, running, latency).
- `DeviceOffline` when connectivity reports no network (no HTTP call made).
- `GatewayUnreachable` after a probe failure **and** its retry both fail.
- transient blip: first probe throws, retry succeeds → stays `Healthy` (debounce works).
- recovery: `GatewayUnreachable` → `Healthy` on a later successful probe.
- 401 → `GatewayUnreachable("unauthorized")`.
- WS-flip coalescing: two rapid `Disconnected` signals within the window trigger only one probe.

**`HealthStripTest`** (pure): `isUnhealthy()`, `healthStripLabel`, `healthStripColor` for every state; badge-visibility predicate.

UI logic lives in pure functions; no full Compose UI tests (matches the repo's VM-plus-pure-logic test style).

## Files

| Action | Path | Responsibility |
|--------|------|----------------|
| New | `data/network/GatewayHealth.kt` | Sealed health model + `isUnhealthy()` |
| New | `data/network/GatewayHealthMonitor.kt` | Connectivity + `/api/status` probing → `StateFlow<GatewayHealth>` |
| New | `ui/components/HealthStrip.kt` | Strip + sheet composables + pure mapping helpers |
| Modify | `di/AppModule.kt` | Provide the monitor |
| Modify | `ui/nav/ShellViewModel.kt` | Expose `health` |
| Modify | `ui/nav/HermesNav.kt` | Host strip, badge the You tab, wire the sheet |
| New | `test/…/data/network/GatewayHealthMonitorTest.kt` | Monitor state transitions |
| New | `test/…/ui/components/HealthStripTest.kt` | Pure mapping helpers |

## Explicitly out of v1 (deliberate anti-scope)

- **Background push** when the gateway dies while the app is closed — the chosen fast-follow (adds a listener in `GatewayConnectionService`; the model already supports it).
- A **dedicated health/status screen** (version/uptime/latency history, provider-reachability).
- **Provider/LLM reachability** (whether the upstream model API is up) — `/api/status` only reports the gateway itself.
- **Latency history/graphs.**

## Build & gates

Build with `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`: `:app:compileDebugKotlin`, `:app:testDebugUnitTest`, `:app:assembleBeta`. gitleaks before every push; PR into `dev`.
