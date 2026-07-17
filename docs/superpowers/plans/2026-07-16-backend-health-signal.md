# Backend-Health Signal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the app an app-wide, proactive backend-health signal that distinguishes *your phone is offline* from *the gateway is down* from *gateway up* — visible on every screen, but only when unhealthy.

**Architecture:** A Hilt-singleton `GatewayHealthMonitor` probes the existing public `GET /api/status` (device-connectivity check first, then a timed HTTP call with one retry as debounce), exposing `StateFlow<GatewayHealth>`. The shell (`HermesNav` via `ShellViewModel`) renders a down-strip + a `You`-tab badge + a detail sheet from that state. Fully client-only — no gateway changes.

**Tech Stack:** Kotlin, Jetpack Compose, Material3, Hilt, Coroutines/StateFlow, OkHttp (via existing `HermesRestApi`).

**Spec:** `docs/superpowers/specs/2026-07-16-backend-health-signal-design.md`

## Global Constraints

- Client-only: use `HermesRestApi.gatewayStatus()` → `GatewayStatusDto { version: String?, gatewayRunning: Boolean, gatewayState: String? }`. No new gateway endpoints, no gateway edits.
- Kotlin/Compose/Material3; per-tenant accent via `LocalProfileAccent` for neutral chrome only — **down states use semantic colors (error / surfaceVariant), never the tenant accent.**
- `GatewayHealth` is distinct from the WS `ConnectionState`.
- Tests are pure-logic + monitor style (mockk `HermesRestApi`, fake `ConnectivityChecker`, `runTest`). **No Compose UI tests.**
- No AI/assistant attribution in commits or files.
- Build env: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
- Constants live in `GatewayHealthMonitor.Companion`: `PROBE_TIMEOUT_MS = 5_000L`, `PROBE_INTERVAL_MS = 30_000L`.
- Branch: `feature/backend-health-signal` (off `dev`; spec committed at `ee69173`). All commits land here.

---

### Task 1: Health model

**Files:**
- Create: `app/src/main/java/com/hermes/client/data/network/GatewayHealth.kt`
- Test: `app/src/test/java/com/hermes/client/data/network/GatewayHealthTest.kt`

**Interfaces:**
- Produces: `sealed interface GatewayHealth` with `Unknown`, `Healthy(version: String?, running: Boolean, latencyMs: Long?)`, `DeviceOffline`, `GatewayUnreachable(detail: String?)`; and `fun GatewayHealth.isUnhealthy(): Boolean`.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/hermes/client/data/network/GatewayHealthTest.kt`:
```kotlin
package com.hermes.client.data.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayHealthTest {
    @Test fun healthy_and_unknown_are_not_unhealthy() {
        assertFalse(GatewayHealth.Unknown.isUnhealthy())
        assertFalse(GatewayHealth.Healthy(version = "1.2.3", running = true, latencyMs = 42).isUnhealthy())
    }

    @Test fun device_offline_and_gateway_unreachable_are_unhealthy() {
        assertTrue(GatewayHealth.DeviceOffline.isUnhealthy())
        assertTrue(GatewayHealth.GatewayUnreachable("unreachable").isUnhealthy())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.hermes.client.data.network.GatewayHealthTest"`
Expected: FAIL — `GatewayHealth` unresolved (does not compile yet).

- [ ] **Step 3: Write minimal implementation**

`app/src/main/java/com/hermes/client/data/network/GatewayHealth.kt`:
```kotlin
package com.hermes.client.data.network

/**
 * Backend health, distinct from the WebSocket [ConnectionState]. Sourced from the device's
 * connectivity plus the gateway's public `/api/status`.
 */
sealed interface GatewayHealth {
    /** Before the first probe completes — renders nothing. */
    data object Unknown : GatewayHealth

    /** `/api/status` returned 2xx. [running] mirrors `gateway_running`. */
    data class Healthy(val version: String?, val running: Boolean, val latencyMs: Long?) : GatewayHealth

    /** The device has no network — the phone is offline, not the gateway. */
    data object DeviceOffline : GatewayHealth

    /** Network is up but `/api/status` failed (timeout, refused, non-2xx). */
    data class GatewayUnreachable(val detail: String?) : GatewayHealth
}

/** True when the down-strip and the You-tab badge should show. */
fun GatewayHealth.isUnhealthy(): Boolean =
    this is GatewayHealth.DeviceOffline || this is GatewayHealth.GatewayUnreachable
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.hermes.client.data.network.GatewayHealthTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hermes/client/data/network/GatewayHealth.kt \
        app/src/test/java/com/hermes/client/data/network/GatewayHealthTest.kt
git commit -m "feat: add GatewayHealth model"
```

---

### Task 2: Connectivity checker + health monitor

**Files:**
- Create: `app/src/main/java/com/hermes/client/data/network/GatewayHealthMonitor.kt` (holds both `ConnectivityChecker`/`AndroidConnectivityChecker` and `GatewayHealthMonitor`)
- Modify: `app/src/main/AndroidManifest.xml` (ensure `ACCESS_NETWORK_STATE` permission)
- Test: `app/src/test/java/com/hermes/client/data/network/GatewayHealthMonitorTest.kt`

**Interfaces:**
- Consumes: `GatewayHealth` (Task 1); `HermesRestApi.gatewayStatus(): GatewayStatusDto`; `HermesApiException(val code: Int, message)`; `ConnectionState` sealed interface (`Connected`/`Connecting`/`Reconnecting`/`Disconnected`/`Error`).
- Produces:
  - `interface ConnectivityChecker { fun isOnline(): Boolean }`
  - `class AndroidConnectivityChecker(context: Context) : ConnectivityChecker`
  - `class GatewayHealthMonitor(api: HermesRestApi, connectivity: ConnectivityChecker, connectionState: StateFlow<ConnectionState>, scope: CoroutineScope)` with `val health: StateFlow<GatewayHealth>`, `suspend fun probe()`, `fun recheck()`, `fun startForeground()`, `fun stopForeground()`, and `companion object { const val PROBE_TIMEOUT_MS = 5_000L; const val PROBE_INTERVAL_MS = 30_000L }`.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/hermes/client/data/network/GatewayHealthMonitorTest.kt`:
```kotlin
package com.hermes.client.data.network

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayHealthMonitorTest {
    private val api = mockk<HermesRestApi>()
    private class FakeConnectivity(var online: Boolean = true) : ConnectivityChecker {
        override fun isOnline() = online
    }

    private fun ok() = GatewayStatusDto(version = "1.2.3", gatewayRunning = true, gatewayState = "running")

    @Test fun probe_reports_healthy_on_2xx() = runTest {
        coEvery { api.gatewayStatus() } returns ok()
        val m = GatewayHealthMonitor(api, FakeConnectivity(true), MutableStateFlow(ConnectionState.Connected), backgroundScope)
        m.probe()
        val h = m.health.value
        assertTrue(h is GatewayHealth.Healthy)
        assertEquals("1.2.3", (h as GatewayHealth.Healthy).version)
        assertTrue(h.running)
    }

    @Test fun probe_reports_device_offline_without_calling_api() = runTest {
        val conn = FakeConnectivity(online = false)
        val m = GatewayHealthMonitor(api, conn, MutableStateFlow(ConnectionState.Connected), backgroundScope)
        m.probe()
        assertEquals(GatewayHealth.DeviceOffline, m.health.value)
        io.mockk.coVerify(exactly = 0) { api.gatewayStatus() }
    }

    @Test fun probe_reports_gateway_unreachable_when_both_attempts_fail() = runTest {
        coEvery { api.gatewayStatus() } throws RuntimeException("timeout")
        val m = GatewayHealthMonitor(api, FakeConnectivity(true), MutableStateFlow(ConnectionState.Connected), backgroundScope)
        m.probe()
        assertTrue(m.health.value is GatewayHealth.GatewayUnreachable)
    }

    @Test fun transient_first_failure_then_success_stays_healthy() = runTest {
        coEvery { api.gatewayStatus() } throws RuntimeException("blip") andThen ok()
        val m = GatewayHealthMonitor(api, FakeConnectivity(true), MutableStateFlow(ConnectionState.Connected), backgroundScope)
        m.probe()
        assertTrue(m.health.value is GatewayHealth.Healthy)
    }

    @Test fun unauthorized_is_reported_without_retry() = runTest {
        coEvery { api.gatewayStatus() } throws HermesApiException(401, "unauthorized")
        val m = GatewayHealthMonitor(api, FakeConnectivity(true), MutableStateFlow(ConnectionState.Connected), backgroundScope)
        m.probe()
        val h = m.health.value
        assertTrue(h is GatewayHealth.GatewayUnreachable)
        assertEquals("unauthorized", (h as GatewayHealth.GatewayUnreachable).detail)
        io.mockk.coVerify(exactly = 1) { api.gatewayStatus() }
    }

    @Test fun recovery_from_unreachable_to_healthy() = runTest {
        coEvery { api.gatewayStatus() } throws RuntimeException("down") andThen RuntimeException("down") andThen ok()
        val m = GatewayHealthMonitor(api, FakeConnectivity(true), MutableStateFlow(ConnectionState.Connected), backgroundScope)
        m.probe() // both attempts fail -> unreachable
        assertTrue(m.health.value is GatewayHealth.GatewayUnreachable)
        m.probe() // next probe succeeds
        assertTrue(m.health.value is GatewayHealth.Healthy)
    }

    @Test fun ws_disconnect_triggers_a_probe() = runTest {
        coEvery { api.gatewayStatus() } returns ok()
        val conn = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        val m = GatewayHealthMonitor(api, FakeConnectivity(true), conn, backgroundScope)
        advanceUntilIdle()
        conn.value = ConnectionState.Disconnected
        advanceUntilIdle()
        assertTrue(m.health.value is GatewayHealth.Healthy)
        io.mockk.coVerify(atLeast = 1) { api.gatewayStatus() }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.hermes.client.data.network.GatewayHealthMonitorTest"`
Expected: FAIL — `ConnectivityChecker` / `GatewayHealthMonitor` unresolved.

- [ ] **Step 3: Write minimal implementation**

`app/src/main/java/com/hermes/client/data/network/GatewayHealthMonitor.kt`:
```kotlin
package com.hermes.client.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout

/** Abstraction over Android connectivity so the monitor is unit-testable. */
interface ConnectivityChecker {
    /** True when the device has a validated, internet-capable network. */
    fun isOnline(): Boolean
}

class AndroidConnectivityChecker(private val context: Context) : ConnectivityChecker {
    override fun isOnline(): Boolean {
        // If we can't read connectivity, assume online rather than false-flag DeviceOffline —
        // the /api/status probe is then the source of truth.
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

/**
 * Proactively tracks whether the self-hosted gateway is reachable, exposed as [health].
 * Probes device connectivity first (→ [GatewayHealth.DeviceOffline]) then the public
 * `/api/status` endpoint, with one immediate retry as debounce so a transient blip does not
 * flash the down-strip. Probing runs only while the app is foregrounded (see [startForeground]).
 */
class GatewayHealthMonitor(
    private val api: HermesRestApi,
    private val connectivity: ConnectivityChecker,
    private val connectionState: StateFlow<ConnectionState>,
    private val scope: CoroutineScope,
) {
    private val _health = MutableStateFlow<GatewayHealth>(GatewayHealth.Unknown)
    val health: StateFlow<GatewayHealth> = _health.asStateFlow()

    private val probeGuard = Mutex()
    private var periodicJob: Job? = null

    init {
        // A dropped/errored socket is an early hint the backend may be gone — re-probe promptly.
        // probe()'s tryLock coalesces this with any in-flight probe.
        scope.launch {
            connectionState.collect { st ->
                if (st is ConnectionState.Error || st is ConnectionState.Disconnected) probe()
            }
        }
    }

    /** Run one health probe, coalescing with any probe already in flight. */
    suspend fun probe() {
        if (!probeGuard.tryLock()) return
        try {
            _health.value = evaluate()
        } finally {
            probeGuard.unlock()
        }
    }

    private suspend fun evaluate(): GatewayHealth {
        if (!connectivity.isOnline()) return GatewayHealth.DeviceOffline
        // First attempt; on a retryable failure (null) try once more before declaring the gateway down.
        return attemptStatus() ?: attemptStatus() ?: GatewayHealth.GatewayUnreachable("unreachable")
    }

    /** Terminal state on a definitive answer (healthy / unauthorized), or null for a retryable failure. */
    private suspend fun attemptStatus(): GatewayHealth? {
        val start = System.nanoTime()
        return try {
            val dto = withTimeout(PROBE_TIMEOUT_MS) { api.gatewayStatus() }
            val latencyMs = (System.nanoTime() - start) / 1_000_000
            GatewayHealth.Healthy(version = dto.version, running = dto.gatewayRunning, latencyMs = latencyMs)
        } catch (e: HermesApiException) {
            // 401 is a definitive answer (reachable but unauthorized) — do not retry.
            if (e.code == 401) GatewayHealth.GatewayUnreachable("unauthorized") else null
        } catch (e: Exception) {
            null // timeout / IO — retryable
        }
    }

    /** Fire an immediate probe (the sheet's Re-check button). */
    fun recheck() {
        scope.launch { probe() }
    }

    /** Begin foreground probing: probe now, then every [PROBE_INTERVAL_MS]. Idempotent. */
    fun startForeground() {
        if (periodicJob?.isActive == true) return
        periodicJob = scope.launch {
            while (true) {
                probe()
                delay(PROBE_INTERVAL_MS)
            }
        }
    }

    /** Stop foreground probing (app backgrounded). */
    fun stopForeground() {
        periodicJob?.cancel()
        periodicJob = null
    }

    companion object {
        const val PROBE_TIMEOUT_MS = 5_000L
        const val PROBE_INTERVAL_MS = 30_000L
    }
}
```

- [ ] **Step 4: Ensure the connectivity permission exists**

Confirm `app/src/main/AndroidManifest.xml` contains (add it next to the existing `INTERNET` permission if missing — `getNetworkCapabilities` needs it):
```xml
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```
Run: `grep -n 'ACCESS_NETWORK_STATE\|android.permission.INTERNET' app/src/main/AndroidManifest.xml`
Expected: both permissions present after this step.

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.hermes.client.data.network.GatewayHealthMonitorTest"`
Expected: PASS (7 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/hermes/client/data/network/GatewayHealthMonitor.kt \
        app/src/test/java/com/hermes/client/data/network/GatewayHealthMonitorTest.kt \
        app/src/main/AndroidManifest.xml
git commit -m "feat: add GatewayHealthMonitor with /api/status probing"
```

---

### Task 3: Health strip + sheet UI (with pure mapping helpers)

**Files:**
- Create: `app/src/main/java/com/hermes/client/ui/components/HealthStrip.kt`
- Test: `app/src/test/java/com/hermes/client/ui/components/HealthStripTest.kt`

**Interfaces:**
- Consumes: `GatewayHealth` (Task 1) + `isUnhealthy()`; `LocalProfileAccent` (existing, `com.hermes.client.ui.theme.LocalProfileAccent`, field `.accent`).
- Produces:
  - `enum class HealthStripStyle { ERROR, NEUTRAL, NONE }`
  - `fun healthStripStyle(health: GatewayHealth): HealthStripStyle`
  - `fun healthStripLabel(health: GatewayHealth): String?`
  - `fun healthSheetBody(health: GatewayHealth): String`
  - `@Composable fun HealthStrip(health: GatewayHealth, onClick: () -> Unit, modifier: Modifier = Modifier)`
  - `@Composable fun HealthSheet(health: GatewayHealth, onRecheck: () -> Unit, onDismiss: () -> Unit)`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/hermes/client/ui/components/HealthStripTest.kt`:
```kotlin
package com.hermes.client.ui.components

import com.hermes.client.data.network.GatewayHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthStripTest {
    @Test fun style_is_none_when_healthy_or_unknown() {
        assertEquals(HealthStripStyle.NONE, healthStripStyle(GatewayHealth.Unknown))
        assertEquals(HealthStripStyle.NONE, healthStripStyle(GatewayHealth.Healthy("1", true, 10)))
    }

    @Test fun style_error_for_gateway_unreachable_neutral_for_device_offline() {
        assertEquals(HealthStripStyle.ERROR, healthStripStyle(GatewayHealth.GatewayUnreachable("x")))
        assertEquals(HealthStripStyle.NEUTRAL, healthStripStyle(GatewayHealth.DeviceOffline))
    }

    @Test fun label_null_when_healthy() {
        assertNull(healthStripLabel(GatewayHealth.Healthy("1", true, 10)))
        assertNull(healthStripLabel(GatewayHealth.Unknown))
    }

    @Test fun label_distinguishes_offline_unreachable_unauthorized() {
        assertEquals("You're offline", healthStripLabel(GatewayHealth.DeviceOffline))
        assertEquals("Gateway unreachable", healthStripLabel(GatewayHealth.GatewayUnreachable("unreachable")))
        assertEquals("Gateway unauthorized", healthStripLabel(GatewayHealth.GatewayUnreachable("unauthorized")))
    }

    @Test fun sheet_body_healthy_includes_version_and_latency() {
        val body = healthSheetBody(GatewayHealth.Healthy(version = "1.2.3", running = true, latencyMs = 42))
        assertTrue(body.contains("running"))
        assertTrue(body.contains("1.2.3"))
        assertTrue(body.contains("42"))
    }

    @Test fun sheet_body_reachable_not_running_when_running_false() {
        val body = healthSheetBody(GatewayHealth.Healthy(version = null, running = false, latencyMs = null))
        assertTrue(body.contains("not running"))
    }

    @Test fun sheet_body_offline_and_unauthorized_copy() {
        assertTrue(healthSheetBody(GatewayHealth.DeviceOffline).contains("offline"))
        assertTrue(healthSheetBody(GatewayHealth.GatewayUnreachable("unauthorized")).contains("unauthorized"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.hermes.client.ui.components.HealthStripTest"`
Expected: FAIL — helpers unresolved.

- [ ] **Step 3: Write minimal implementation**

`app/src/main/java/com/hermes/client/ui/components/HealthStrip.kt`:
```kotlin
package com.hermes.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hermes.client.data.network.GatewayHealth
import com.hermes.client.ui.theme.LocalProfileAccent

/** Visual severity of the strip. Kept separate from color so it is unit-testable. */
enum class HealthStripStyle { ERROR, NEUTRAL, NONE }

fun healthStripStyle(health: GatewayHealth): HealthStripStyle = when (health) {
    is GatewayHealth.GatewayUnreachable -> HealthStripStyle.ERROR
    GatewayHealth.DeviceOffline -> HealthStripStyle.NEUTRAL
    is GatewayHealth.Healthy, GatewayHealth.Unknown -> HealthStripStyle.NONE
}

/** Short strip label; null when nothing should show. */
fun healthStripLabel(health: GatewayHealth): String? = when (health) {
    GatewayHealth.DeviceOffline -> "You're offline"
    is GatewayHealth.GatewayUnreachable ->
        if (health.detail == "unauthorized") "Gateway unauthorized" else "Gateway unreachable"
    is GatewayHealth.Healthy, GatewayHealth.Unknown -> null
}

/** Sheet detail copy for the current state. */
fun healthSheetBody(health: GatewayHealth): String = when (health) {
    is GatewayHealth.Healthy -> buildString {
        append(if (health.running) "Gateway running" else "Gateway reachable, not running")
        health.version?.let { append(" · v").append(it) }
        health.latencyMs?.let { append(" · ").append(it).append(" ms") }
    }
    is GatewayHealth.GatewayUnreachable ->
        if (health.detail == "unauthorized") "The gateway rejected the session token (unauthorized)."
        else "The gateway isn't responding. It may be down or restarting."
    GatewayHealth.DeviceOffline -> "Your device is offline — Hermes will reconnect automatically."
    GatewayHealth.Unknown -> "Checking…"
}

/**
 * Slim status strip shown across all screens ONLY when unhealthy. Applies its own status-bar
 * padding so it sits below the system bar; callers should consume the status-bars inset for the
 * content beneath it so the screen's own top bar does not add a second gap.
 */
@Composable
fun HealthStrip(health: GatewayHealth, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = healthStripLabel(health) ?: return
    val bg = when (healthStripStyle(health)) {
        HealthStripStyle.ERROR -> MaterialTheme.colorScheme.errorContainer
        HealthStripStyle.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant
        HealthStripStyle.NONE -> Color.Transparent
    }
    val fg = when (healthStripStyle(health)) {
        HealthStripStyle.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onClick)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = fg, modifier = Modifier.padding(end = 8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg, modifier = Modifier.padding(end = 8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = fg)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthSheet(health: GatewayHealth, onRecheck: () -> Unit, onDismiss: () -> Unit) {
    val accent = LocalProfileAccent.current.accent
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(24.dp)) {
            Text(
                healthStripLabel(health) ?: "Gateway",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                healthSheetBody(health),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onRecheck) {
                    Text("Re-check", color = accent, textAlign = TextAlign.End)
                }
            }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.hermes.client.ui.components.HealthStripTest"`
Expected: PASS (7 tests).

- [ ] **Step 5: Compile the app (composables reference Material3/theme)**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/components/HealthStrip.kt \
        app/src/test/java/com/hermes/client/ui/components/HealthStripTest.kt
git commit -m "feat: add HealthStrip + HealthSheet with pure mapping helpers"
```

---

### Task 4: Wire monitor into DI, ShellViewModel, and the shell

**Files:**
- Modify: `app/src/main/java/com/hermes/client/di/AppModule.kt` (add two providers, after `provideHermesRestApi`)
- Modify: `app/src/main/java/com/hermes/client/ui/nav/ShellViewModel.kt`
- Modify: `app/src/main/java/com/hermes/client/ui/nav/HermesNav.kt`
- Test: none new (covered by Tasks 1–3 + the build). This task's deliverable is verified by compile + full test suite + `assembleBeta`.

**Interfaces:**
- Consumes: `GatewayHealthMonitor`, `ConnectivityChecker`/`AndroidConnectivityChecker` (Task 2); `HealthStrip`, `HealthSheet` (Task 3); `GatewayHealth.isUnhealthy()` (Task 1); existing `HermesGatewayClient.connectionState`, `provideAppScope(): CoroutineScope`, `ShellViewModel`.

- [ ] **Step 1: Add DI providers**

In `app/src/main/java/com/hermes/client/di/AppModule.kt`, add these two providers immediately after `provideHermesRestApi(...)` (uses the existing `@ApplicationContext context: Context` and `scope: CoroutineScope` patterns already present in this file):
```kotlin
    @Provides
    @Singleton
    fun provideConnectivityChecker(
        @ApplicationContext context: Context,
    ): com.hermes.client.data.network.ConnectivityChecker =
        com.hermes.client.data.network.AndroidConnectivityChecker(context)

    @Provides
    @Singleton
    fun provideGatewayHealthMonitor(
        api: HermesRestApi,
        connectivity: com.hermes.client.data.network.ConnectivityChecker,
        client: HermesGatewayClient,
        scope: CoroutineScope,
    ): com.hermes.client.data.network.GatewayHealthMonitor =
        com.hermes.client.data.network.GatewayHealthMonitor(api, connectivity, client.connectionState, scope)
```

- [ ] **Step 2: Expose health from ShellViewModel**

Replace the constructor and add the health members in `app/src/main/java/com/hermes/client/ui/nav/ShellViewModel.kt`:
```kotlin
@HiltViewModel
class ShellViewModel @Inject constructor(
    private val profileManager: ProfileManager,
    private val accentStore: ProfileAccentStore,
    private val healthMonitor: com.hermes.client.data.network.GatewayHealthMonitor,
) : ViewModel() {
    val profiles: StateFlow<List<ProfileDto>> = profileManager.list
    val active: StateFlow<String?> = profileManager.active

    /** Backend health for the shell's status strip + You-tab badge. */
    val health: StateFlow<com.hermes.client.data.network.GatewayHealth> = healthMonitor.health

    init { viewModelScope.launch { profileManager.refresh() } }

    fun switchProfile(name: String) = viewModelScope.launch { profileManager.switchTo(name) }

    /** Set a custom accent colour for [profile] (persisted; overrides the auto-hashed hue). */
    fun setAccent(profile: String, argb: Int) = viewModelScope.launch { accentStore.setColor(profile, argb) }

    /** Clear [profile]'s custom colour, reverting to the auto hue. */
    fun clearAccent(profile: String) = viewModelScope.launch { accentStore.clear(profile) }

    /** Fire an immediate health probe (Re-check button). */
    fun recheckHealth() = healthMonitor.recheck()

    /** Foreground/background gating for periodic probing. */
    fun onAppForeground() = healthMonitor.startForeground()
    fun onAppBackground() = healthMonitor.stopForeground()
}
```

- [ ] **Step 3: Host the strip, badge, sheet, and lifecycle wiring in HermesNav**

In `app/src/main/java/com/hermes/client/ui/nav/HermesNav.kt`, add imports:
```kotlin
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.data.network.isUnhealthy
import com.hermes.client.ui.components.HealthSheet
import com.hermes.client.ui.components.HealthStrip
```

Near the top of `HermesNav`, after `val route = backStackEntry?.destination?.route`, add:
```kotlin
    val shellVm: ShellViewModel = hiltViewModel()
    val health by shellVm.health.collectAsStateWithLifecycle()
    var showHealthSheet by rememberSaveable { mutableStateOf(false) }

    // Probe only while the app is foregrounded (in-app-only v1). ProcessLifecycleOwner replays its
    // current state on addObserver, so ON_START fires immediately if already foregrounded.
    DisposableEffect(Unit) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> shellVm.onAppForeground()
                Lifecycle.Event.ON_STOP -> shellVm.onAppBackground()
                else -> {}
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(obs)
        onDispose { ProcessLifecycleOwner.get().lifecycle.removeObserver(obs) }
    }
```

Change the `You` tab's `NavigationBarItem` icon to badge when unhealthy. Replace the `icon = { Icon(tab.icon, contentDescription = tab.label) }` line inside `TABS.forEach` with:
```kotlin
                            icon = {
                                if (tab.route == "you" && health.isUnhealthy()) {
                                    BadgedBox(badge = { Badge() }) {
                                        Icon(tab.icon, contentDescription = tab.label)
                                    }
                                } else {
                                    Icon(tab.icon, contentDescription = tab.label)
                                }
                            },
```

Replace the Scaffold content lambda (the `{ padding -> NavHost(...) { ... } }` block) so the strip sits above the `NavHost`. The `NavHost` body (all `composable(...) { }` entries) stays **exactly** as-is; only the wrapper changes:
```kotlin
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
            // Renders nothing when healthy. When shown it owns the status-bar inset, so the content
            // below consumes that inset to avoid a second top gap under the strip.
            if (health.isUnhealthy()) {
                HealthStrip(health = health, onClick = { showHealthSheet = true })
            }
            val contentModifier =
                if (health.isUnhealthy()) Modifier.weight(1f).consumeWindowInsets(WindowInsets.statusBars)
                else Modifier.weight(1f)
            NavHost(
                navController = nav,
                startDestination = start,
                modifier = contentModifier,
            ) {
                // ... existing composable(...) entries unchanged ...
            }
        }
    }
```
Note: the `NavHost`'s `modifier` changes from `Modifier.padding(bottom = padding.calculateBottomPadding())` to `contentModifier` (the bottom padding now lives on the enclosing `Column`).

After the `Scaffold { ... }` block (still inside `HermesNav`), add the sheet host:
```kotlin
    if (showHealthSheet) {
        HealthSheet(
            health = health,
            onRecheck = { shellVm.recheckHealth() },
            onDismiss = { showHealthSheet = false },
        )
    }
```

- [ ] **Step 4: Compile**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (If `Column` needs `weight`, it is `androidx.compose.foundation.layout.ColumnScope.weight`, available inside `Column`.)

- [ ] **Step 5: Run the full unit-test suite**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 0 failures (includes Tasks 1–3 suites).

- [ ] **Step 6: Assemble the beta variant**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleBeta`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/hermes/client/di/AppModule.kt \
        app/src/main/java/com/hermes/client/ui/nav/ShellViewModel.kt \
        app/src/main/java/com/hermes/client/ui/nav/HermesNav.kt
git commit -m "feat: surface backend health as a shell strip, You-tab badge, and detail sheet"
```

---

### Task 5: On-device verification

**Files:** none (manual verification on the emulator or a connected device).

This task has no code. Install the beta build and confirm the behavior against the spec. There is no automated Compose UI test (per Global Constraints), so this manual pass is the acceptance gate for the UI wiring.

- [ ] **Step 1: Install the beta build**

Run:
```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:installBeta
```
Expected: `Installed on 1 device`.

- [ ] **Step 2: Verify the healthy state (no clutter)**

With the gateway reachable and the app connected: confirm **no** strip is shown on the Chats / Home / You screens, and **no** badge on the `You` tab. Open the `You` tab, then any screen — still clean.

- [ ] **Step 3: Verify gateway-unreachable**

Stop the gateway (or point the app at a dead port), background/foreground the app to trigger a probe. Confirm: a red (errorContainer) strip reading **"Gateway unreachable"** appears across screens; the `You` tab shows a badge; tapping the strip opens the sheet with "The gateway isn't responding…" and a **Re-check** button. Restart the gateway, tap **Re-check** → strip and badge clear.

- [ ] **Step 4: Verify device-offline distinction**

Enable airplane mode. Confirm the strip reads **"You're offline"** in neutral (surfaceVariant) styling — visibly different from the red gateway-unreachable state — and the sheet body says the device is offline. Disable airplane mode → clears on the next probe.

- [ ] **Step 5: Verify no double top gap**

While the strip is visible, confirm the underlying screen's own top app bar sits directly beneath the strip with a single status-bar area (no doubled blank gap), and content is not pushed down twice.

- [ ] **Step 6: Verify tenant accent + background pause**

Confirm the strip/error styling is semantic (same red in every profile — not the tenant accent), while the sheet's **Re-check** button uses the tenant accent. Background the app for > 30s and confirm (via `adb logcat` filtering `rest` `GET /api/status`, or the DebugLog screen) that probing stops while backgrounded and resumes on return.

- [ ] **Step 7: Record the verification result**

No commit. Note the outcome (pass/fail per step) in the PR description when the branch is finished.

---

## Notes for the executor

- **Coalescing:** the spec's "ignore if a probe ran < 3s ago" is realized more simply as an in-flight `Mutex.tryLock` guard in `probe()` (no wall-clock dependency, unit-testable). Concurrent triggers (WS flap + periodic + Re-check) collapse to one probe; this is the intended behavior and a deliberate, equivalent simplification.
- **Latency in tests:** `System.nanoTime()` is real on the JVM test runtime, so `Healthy.latencyMs` is a small non-negative number in tests; assertions check presence/other fields, not an exact latency.
- **Do not** add a background/push path, a health-detail screen, provider-reachability, or latency history — all explicitly out of v1 per the spec.
```
