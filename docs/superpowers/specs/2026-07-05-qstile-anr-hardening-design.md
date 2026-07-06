# QS-Tile ANR Hardening — Design

**Date:** 2026-07-05
**Status:** Approved (brainstorming) → implementation plan next
**Branch:** `feature/qstile-anr-hardening`
**Source:** `docs/ideas/native-command-surface.md` ("Remaining" → QS-tile `runBlocking` → async)

## Goal

Remove blocking DataStore I/O from the Quick-Settings tile's main-thread callbacks (an ANR risk) by moving reads and writes onto a service-owned coroutine scope. No behaviour change — the tile still shows and toggles the notification service.

## Hard constraints

- **No gateway/bridge API changes.** Native Android only. Material 3 N/A (no UI).
- Multi-tenant isolation unaffected (this is the notification tile).
- No AI/assistant attribution in commits, files, or PRs.

## Problem

`app/src/main/java/com/hermes/client/notifications/NotificationTileService.kt` (a `TileService`) runs `runBlocking { … }` on the main-thread tile callbacks:
- `onStartListening()` — `runBlocking { settings.prefs.first().enabled }` (READ)
- `onClick()` — a READ, then in the ENABLE/DISABLE branches `runBlocking { settings.setEnabled(...) }` (WRITE)

A blocking DataStore **write** on the main thread, inside a QS-tile callback's short binder-transaction window, is an ANR risk on slow devices or during cold DataStore initialization. The file's own comment ("a single fast DataStore read via runBlocking is fine") understates the write's cost.

## Dropped after exploration (siblings from the same "Remaining" list)

- **Model-switch failure in Settings — no gap.** The real model-write path (`ModelsViewModel.select`) already surfaces failures via `onFailure { message = "Failed to set model: …" }` through a working `SnackbarHost` in `ModelsScreen`. `MemorySettingsScreen`'s "Default model" row is read-only display and writes nothing. Nothing to fix.
- **New-FAB last-used profile — not worth it.** New chat creates in the gateway's **active** profile, which the switcher chip row keeps in sync and visibly shows; "New" already opens in the tenant the user can see is selected. Auto-switching to the most-recent session's profile would be a *silent tenant-context switch* — a multi-tenant surprise. Current behaviour is safer and more predictable.

## Design

Mirror the existing `GatewayConnectionService` pattern in the same package (a service-owned `CoroutineScope(SupervisorJob())` cancelled in `onDestroy`), using `Dispatchers.Main.immediate` so the coroutine can safely touch `qsTile`/`updateTile()` after the suspend calls resolve.

- **Add a scope:** `private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`.
- **Cancel it:** add `override fun onDestroy() { scope.cancel(); super.onDestroy() }`.
- **`onStartListening`:** `scope.launch { renderTile(settings.prefs.first().enabled) }`.
- **`onClick`:** wrap the whole body in `scope.launch { … }`:
```kotlin
    override fun onClick() {
        super.onClick()
        scope.launch {
            val enabled = settings.prefs.first().enabled
            val canStart = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(this@NotificationTileService, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            when (tileClickAction(enabled, canStart)) {
                TileAction.ENABLE -> {
                    settings.setEnabled(true)
                    GatewayConnectionService.start(this@NotificationTileService)
                    renderTile(true)
                }
                TileAction.DISABLE -> {
                    settings.setEnabled(false)
                    GatewayConnectionService.stop(this@NotificationTileService)
                    renderTile(false)
                }
                TileAction.OPEN_FOR_PERMISSION -> openNotificationSettings()
            }
        }
    }
```
- **Imports:** remove `kotlinx.coroutines.runBlocking`; add `kotlinx.coroutines.CoroutineScope`, `kotlinx.coroutines.SupervisorJob`, `kotlinx.coroutines.Dispatchers`, `kotlinx.coroutines.cancel`, `kotlinx.coroutines.launch` (keep `kotlinx.coroutines.flow.first`).
- **Doc comment:** replace the stale "TileService callbacks run on the main thread; a single fast DataStore read via runBlocking is fine." line with a note that reads/writes run on a service-owned Main.immediate scope to keep the tile callbacks non-blocking.

`renderTile`, `openNotificationSettings`, and the pure `tileClickAction(enabled, canStart)` helper are unchanged.

## Testing

- The pure decision logic `tileClickAction(enabled, canStart)` is already covered by its existing unit test (unchanged). No new unit test — the change is a threading move with no logic change.
- Compile + `assembleBeta` green.
- Manual (optional, on-device): add the Hermes QS tile to the Quick Settings panel; toggling it still starts/stops the service and flips the tile On/Off (unchanged behaviour); no ANR/jank.

## Not doing (YAGNI)

- The dropped siblings above.
- Any refactor of `NotificationSettings`/DataStore.
- `canStart` extraction/dedup (a separate cosmetic item; `tileClickAction` already isolates the decision).
- Any gateway/API change.
