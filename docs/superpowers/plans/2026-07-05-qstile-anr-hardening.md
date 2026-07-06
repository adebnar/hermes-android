# QS-Tile ANR Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the Quick-Settings tile's DataStore reads/writes off the main thread onto a service-owned coroutine scope, removing an ANR risk.

**Architecture:** Add a `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)` to `NotificationTileService` (mirroring `GatewayConnectionService` in the same package), cancelled in `onDestroy`; run the tile callbacks' reads/writes in `scope.launch { … }`. Behaviour is unchanged.

**Tech Stack:** Kotlin, Android `TileService`, Hilt, DataStore, coroutines.

## Global Constraints

- **No gateway/bridge API changes.** Native Android only.
- JDK 21: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
- Compile: `./gradlew :app:compileDebugKotlin --console=plain`. Tests: `./gradlew :app:testDebugUnitTest --console=plain`. Beta: `./gradlew :app:assembleBeta`.
- **No AI/assistant attribution** in commits, files, or PRs.

---

### Task 1: Move tile I/O onto a service scope

**Files:** Modify `app/src/main/java/com/hermes/client/notifications/NotificationTileService.kt`

The `TileService` currently does `runBlocking { … }` on the main-thread callbacks (`onStartListening` read; `onClick` read + `settings.setEnabled(...)` write). Move them onto a service-owned coroutine scope.

- [ ] **Step 1: Add the scope + `onDestroy`**

Add a field and lifecycle cancel to the class (mirroring `GatewayConnectionService` in the same package, but with `Dispatchers.Main.immediate` so the coroutine can safely touch `qsTile`/`updateTile()`):
```kotlin
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
```

- [ ] **Step 2: Make `onStartListening` async**

```kotlin
    override fun onStartListening() {
        super.onStartListening()
        scope.launch { renderTile(settings.prefs.first().enabled) }
    }
```

- [ ] **Step 3: Make `onClick` async**

Wrap the whole body in `scope.launch { … }`, using `this@NotificationTileService` for the service `Context`:
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

- [ ] **Step 4: Fix imports + the stale doc comment**

Remove `import kotlinx.coroutines.runBlocking`. Add: `import kotlinx.coroutines.CoroutineScope`, `import kotlinx.coroutines.Dispatchers`, `import kotlinx.coroutines.SupervisorJob`, `import kotlinx.coroutines.cancel`, `import kotlinx.coroutines.launch`. Keep `import kotlinx.coroutines.flow.first`. Replace the class-doc line "TileService callbacks run on the main thread; a single fast DataStore read via runBlocking is fine." with: "Tile callbacks run reads/writes on a service-owned `Main.immediate` scope so DataStore I/O never blocks the main thread." Leave `renderTile`, `openNotificationSettings`, and the pure `tileClickAction(enabled, canStart)` helper unchanged.

- [ ] **Step 5: Compile + full unit suite**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --console=plain 2>&1 | grep -E "^e: |FAILED|BUILD" | head`
Expected: `BUILD SUCCESSFUL`, no `FAILED` (the existing `tileClickAction` test still passes — logic unchanged).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/hermes/client/notifications/NotificationTileService.kt
git commit -m "fix(tile): run QS-tile DataStore I/O on a service scope (no main-thread blocking)"
```

---

### Task 2: Package + sanity

**Files:** none (verification only).

- [ ] **Step 1: Assemble the beta variant**

Run: `./gradlew :app:assembleBeta --console=plain 2>&1 | grep -E "^e: |BUILD" | tail -2`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Behaviour note**

This is a threading-only change: the QS tile still shows On/Off and toggling it still starts/stops `GatewayConnectionService` and flips the tile state — no user-visible behaviour change. (Optional manual check: add the Hermes tile to the Quick Settings panel and toggle it.)

---

## Self-Review

- **Spec coverage:** the spec's whole "Design" section → Task 1 (scope + `onDestroy`, async `onStartListening`/`onClick`, imports, doc comment) ✓; "Testing" (compile + suite + assembleBeta, no new unit test) → Task 1 Step 5 + Task 2 ✓. Dropped siblings (model-error, New-FAB) intentionally have no task, per the spec.
- **Placeholder scan:** every code step shows the full code; no TBD/TODO.
- **Type consistency:** `scope`, `onDestroy`, `tileClickAction(enabled, canStart)`, `TileAction.{ENABLE,DISABLE,OPEN_FOR_PERMISSION}`, `GatewayConnectionService.start/stop`, `renderTile`, `openNotificationSettings`, `settings.prefs.first().enabled`, `settings.setEnabled(...)` all match the explored source.

**Ordering:** single file, single implementation task; Task 2 verifies.
