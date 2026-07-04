# Quick Settings Tile — Design

**Date:** 2026-07-03
**Status:** Approved (brainstorming) → implementation plan next
**Branch:** `feature/qs-tile`
**Parent idea:** `docs/ideas/native-command-surface.md` (Ship 2 — Native Pager, the Quick Settings tile piece)

## Goal

A pull-down Quick Settings tile ("Hermes") that shows whether notifications are on and toggles the background notification service in one tap — mirroring the Settings → Notifications "Enable" switch, without opening the app. Pure native Android; no gateway/bridge changes.

## Hard constraints

- **No bridge/gateway API changes.** Reuse `NotificationSettings` (DataStore), `GatewayConnectionService.start/stop`, and the existing `extra_route` → `HermesNav` deep-link.
- Follow existing patterns: Hilt (`@AndroidEntryPoint`), pure unit-tested decision logic, existing `ic_stat_hermes` drawable.
- No AI/assistant attribution in commits, files, or PRs.

## What the app already provides (grounding)

- `NotificationSettings` (DataStore): `val prefs: Flow<NotificationPrefs>` (`enabled`, `approvals`), `suspend fun setEnabled(Boolean)`. Hilt-provided.
- `GatewayConnectionService.start(context)` / `stop(context)` — the foreground notifier service.
- `NotificationsScreen` enable flow: on API 33+ requests `POST_NOTIFICATIONS`, then `setEnabled(true)` + `GatewayConnectionService.start`. Reached via `HermesNav` route `"settings_notifications"`.
- `MainActivity` reads an `"extra_route"` intent extra into `pendingRoute` → `HermesNav.deepLinkRoute` navigates there.
- `ic_stat_hermes` drawable exists.
- `enabled` in `NotificationSettings` is the single source of truth (the service runs iff enabled + permission).

## Architecture

A `TileService` that reflects `NotificationSettings.enabled` and, on tap, applies a **pure decision** to enable/disable the service or route the user to the app to grant permission.

### Components

1. **Pure `tileClickAction(enabled: Boolean, canStart: Boolean): TileAction`** — `app/src/main/java/com/hermes/client/notifications/TileLogic.kt`. The unit-tested core.
   - `TileAction` = `DISABLE | ENABLE | OPEN_FOR_PERMISSION`.
   - `enabled == true` → `DISABLE`.
   - `enabled == false && canStart` → `ENABLE`.
   - `enabled == false && !canStart` → `OPEN_FOR_PERMISSION`.
   - `canStart` is computed by the caller as `Build.VERSION.SDK_INT < 33 || POST_NOTIFICATIONS granted`.

2. **`NotificationTileService`** — `app/src/main/java/com/hermes/client/notifications/NotificationTileService.kt`. `@AndroidEntryPoint TileService`, injects `NotificationSettings`.
   - `onStartListening()` (fires when the shade opens): `val enabled = runBlocking { settings.prefs.first().enabled }`; call `renderTile(enabled)` → set `qsTile` label `"Hermes"`, subtitle `"On"`/`"Off"`, `state = Tile.STATE_ACTIVE`/`STATE_INACTIVE`, icon `ic_stat_hermes`; `qsTile.updateTile()`.
   - `onClick()`:
     - `val enabled = runBlocking { settings.prefs.first().enabled }`.
     - `val canStart = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, POST_NOTIFICATIONS) == PERMISSION_GRANTED`.
     - `when (tileClickAction(enabled, canStart))`:
       - `ENABLE` → `runBlocking { settings.setEnabled(true) }`; `GatewayConnectionService.start(this)`; `renderTile(true)`.
       - `DISABLE` → `runBlocking { settings.setEnabled(false) }`; `GatewayConnectionService.stop(this)`; `renderTile(false)`.
       - `OPEN_FOR_PERMISSION` → `startActivityAndCollapse(PendingIntent for MainActivity with extra "extra_route" = "settings_notifications", FLAG_IMMUTABLE)`; leave the tile as-is (the app's Enable switch runs the permission flow). On Android 14+ `startActivityAndCollapse` requires a `PendingIntent`.

3. **Manifest** — a `<service>` alongside `GatewayConnectionService`:
   ```xml
   <service
       android:name=".notifications.NotificationTileService"
       android:exported="true"
       android:icon="@drawable/ic_stat_hermes"
       android:label="Hermes"
       android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
       <intent-filter>
           <action android:name="android.service.quicksettings.action.QS_TILE" />
       </intent-filter>
   </service>
   ```

### Optional live-sync (include only if trivial)

When the Settings → Notifications toggle flips `enabled`, the tile is stale until the shade re-opens. `TileService.requestListeningState(context, ComponentName(context, NotificationTileService::class.java))` pushes an `onStartListening` refresh. If it's a clean one-liner from the enable/disable handler, include it; otherwise skip — `onStartListening` already refreshes on every shade open.

## Data flow

```
shade opens → onStartListening → settings.prefs.enabled → renderTile(on/off)
tap → onClick → (enabled, canStart) → tileClickAction [pure]
   ENABLE  → setEnabled(true)  + GatewayConnectionService.start + renderTile(true)
   DISABLE → setEnabled(false) + GatewayConnectionService.stop  + renderTile(false)
   OPEN_FOR_PERMISSION → startActivityAndCollapse(MainActivity, extra_route="settings_notifications")
```

## Error handling

- DataStore reads in `onStartListening`/`onClick` use `runBlocking { prefs.first() }` — acceptable in the short-lived, main-thread TileService callbacks (a single fast DataStore read).
- If the service can't be started because permission isn't granted, the tile routes into the app (`OPEN_FOR_PERMISSION`) rather than failing silently.
- Starting `GatewayConnectionService` from `onClick` is a legal foreground-service start (user-initiated from the QS tile).

## Testing

- **Unit (pure), TDD — `TileLogicTest`:** `tileClickAction(enabled = true, …) → DISABLE`; `(false, canStart = true) → ENABLE`; `(false, canStart = false) → OPEN_FOR_PERMISSION`.
- **On-device:** add the Hermes tile to Quick Settings; toggle it → confirm the notification service starts/stops and the tile label flips On/Off; with `POST_NOTIFICATIONS` denied, tapping to enable opens the app at Settings → Notifications.

## Not doing (YAGNI)

- Showing counts or any state beyond On/Off.
- A second tile, an app widget, or per-type toggles on the tile.
- Any new gateway endpoint.

## Open questions (non-blocking; plan may settle)

- Whether to include the optional `requestListeningState` live-sync from the Settings toggle — decide in the plan based on how clean it is.
