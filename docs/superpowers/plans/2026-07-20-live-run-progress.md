# Live In-Flight Run Progress Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show a live, glanceable ongoing notification while an agent run is in flight — indeterminate by default, determinate when the model drives the `todo` tool.

**Architecture:** A pure reducer (`RunProgress.reduce`) folds gateway WebSocket events into a small run-state value. `GatewayConnectionService` — which already owns the single `client.events` collector — holds that value, maps it through a pure `toSpec()` to a platform-independent `RunProgressSpec`, and drives `HermesNotifier` to post/update/cancel one ongoing notification. No new singleton, no second collector, and all logic outside the notifier is pure and unit-testable without Android.

**Tech Stack:** Kotlin, kotlinx.serialization (`JsonObject` payloads), Hilt, Compose/Material3 (settings toggle only), JUnit4 + `kotlinx-coroutines-test`.

**Spec:** `docs/superpowers/specs/2026-07-20-live-run-progress-design.md` (committed `1fe69c7`).

## Global Constraints

- `RUN_PROGRESS_NOTIFICATION_ID = 1003` — must not collide with `SERVICE_NOTIFICATION_ID` (1001) or the mapper's 1002 fallback.
- New channel id `"run_progress"`, `IMPORTANCE_LOW`. The existing MIN `"service"` channel and its static "Connected" notification are unchanged.
- `cancelled` todos are EXCLUDED from `total`; determinate iff `total > 0`.
- `session.info.running` is the authoritative idle/busy backstop; `false` ⇒ reset.
- The progress notification must be cancelled on `message.complete`, `error`, `session.info running=false`, AND `Service.onDestroy()`.
- Tenant accent via `accentArgb` for chrome only; generic `acme`/`globex` naming in tests.
- No AI attribution in commits/files/PRs. Run `gitleaks git --no-banner --redact` before every push. PR into `dev`, never `main`.
- Reducer and spec-mapping logic must be PURE functions, unit-testable without Android.
- Every API-36 call site runtime-gated on `Build.VERSION.SDK_INT >= 36`, with the `NotificationCompat.setProgress` fallback for API 26–35 (`minSdk = 26`).

### Resolved implementation fork (do not re-investigate)

`NotificationCompat.ProgressStyle` **does not exist** in `androidx.core 1.16.0` — verified by extracting `core-1.16.0.aar` and finding no `ProgressStyle` class. **Do NOT upgrade the dependency.** The API-36 branch uses the platform `android.app.Notification.Builder` with `android.app.Notification.ProgressStyle`, confirmed present in the android-36 platform jar.

Verified platform API surface (`javap` on `android.jar`):

- `Notification.ProgressStyle()` — no-arg constructor.
- `setProgress(int)`, `setProgressIndeterminate(boolean)`, `addProgressSegment(Segment)`.
- **There is NO `setProgressMax(int)`.** The bar's maximum is the SUM of its segment lengths, so a determinate `done/total` bar is built by adding ONE segment of length `total` and calling `setProgress(done)`.
- `Notification.ProgressStyle.Segment(int length)`, with `setColor(int)`.
- `Notification.Builder.setShortCriticalText(String)` — the status-bar chip text on promoted notifications.
- **There is NO `requestPromotedOngoing()`.** The system grants `Notification.FLAG_PROMOTED_ONGOING` itself when the notification has promotable characteristics. Do not attempt to set that flag.

### Build commands

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleBeta
```

### File structure

| File | Responsibility |
|---|---|
| `app/src/main/java/com/hermes/client/data/progress/RunProgress.kt` | **Create.** Run-state model + pure `reduce(ServerEvent)`. Knows nothing about notifications. |
| `app/src/main/java/com/hermes/client/data/network/ServerEvent.kt` | Modify. Add the defensive `todoCounts()` payload helper. |
| `app/src/main/java/com/hermes/client/notifications/RunProgressMapper.kt` | **Create.** Pure `RunProgress.toSpec(profileName, prefs)` → `RunProgressSpec?`. |
| `app/src/main/java/com/hermes/client/notifications/NotificationModels.kt` | Modify. `RunProgressSpec`, `runProgress` pref, channel + event constants. |
| `app/src/main/java/com/hermes/client/notifications/HermesNotifier.kt` | Modify. `run_progress` channel, `postRunProgress()`, `cancelRunProgress()`, API-36 split. |
| `app/src/main/java/com/hermes/client/notifications/GatewayConnectionService.kt` | Modify. Hold reduced state, drive the notifier, cancel in `onDestroy()`. |
| `app/src/main/java/com/hermes/client/data/repository/NotificationSettings.kt` | Modify. Persist `runProgress`. |
| `app/src/main/java/com/hermes/client/ui/settings/NotificationsScreen.kt` | Modify. Toggle row. |
| `app/src/test/java/com/hermes/client/data/progress/RunProgressTest.kt` | **Create.** Reducer tests (spec cases 1–10). |
| `app/src/test/java/com/hermes/client/notifications/RunProgressMapperTest.kt` | **Create.** Spec-mapping tests. |

**Note — a deliberate refinement of the spec's Files table:** the spec placed `toSpec()` inside `RunProgress.kt`. It is instead placed in `notifications/RunProgressMapper.kt` so the `data` layer never imports notification types, mirroring the existing `NotificationMapper.kt` pattern. Behaviour is identical.

---

### Task 1: RunProgress model + pure reducer

**Files:**
- Create: `app/src/main/java/com/hermes/client/data/progress/RunProgress.kt`
- Modify: `app/src/main/java/com/hermes/client/data/network/ServerEvent.kt` (append helper after line 45)
- Test: `app/src/test/java/com/hermes/client/data/progress/RunProgressTest.kt`

**Interfaces:**
- Consumes: `ServerEvent(type, sessionId, payload)` and the existing `internal fun ServerEvent.str(key)` / `bool(key)` helpers from `com.hermes.client.data.network`.
- Produces: `data class RunProgress(running, sessionId, tool, done, total)` with `val determinate: Boolean`; `fun RunProgress.reduce(event: ServerEvent): RunProgress`; `internal fun ServerEvent.todoCounts(): Pair<Int, Int>`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/hermes/client/data/progress/RunProgressTest.kt`:

```kotlin
package com.hermes.client.data.progress

import com.hermes.client.data.network.ServerEvent
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunProgressTest {
    private fun ev(type: String, session: String = "s1", build: (JsonObjectBuilder.() -> Unit) = {}) =
        ServerEvent(type, session, buildJsonObject { put("session_id", session); build() })

    /** A todo tool.complete carrying an explicit list of {id, content, status} items. */
    private fun todoEvent(vararg statuses: String) = ev("tool.complete") {
        put("name", "todo")
        putJsonArray("todos") {
            statuses.forEachIndexed { i, s ->
                addJsonObject { put("id", "$i"); put("content", "task $i"); put("status", s) }
            }
        }
    }

    // Case 1
    @Test fun message_start_marks_running_and_indeterminate() {
        val s = RunProgress().reduce(ev("message.start"))
        assertTrue(s.running)
        assertFalse(s.determinate)
        assertEquals("s1", s.sessionId)
    }

    // Case 2
    @Test fun tool_start_records_tool_name() {
        var s = RunProgress().reduce(ev("message.start"))
        s = s.reduce(ev("tool.start") { put("name", "web_search") })
        assertEquals("web_search", s.tool)
    }

    // Case 3
    @Test fun todo_tool_complete_yields_determinate_counts() {
        var s = RunProgress().reduce(ev("message.start"))
        s = s.reduce(todoEvent("completed", "completed", "in_progress", "pending", "pending"))
        assertTrue(s.determinate)
        assertEquals(2, s.done)
        assertEquals(5, s.total)
    }

    // Case 4
    @Test fun cancelled_todos_are_excluded_from_total() {
        var s = RunProgress().reduce(ev("message.start"))
        s = s.reduce(todoEvent("completed", "completed", "completed", "cancelled"))
        assertEquals(3, s.done)
        assertEquals(3, s.total)
    }

    // Case 5
    @Test fun a_new_run_resets_counts_from_the_previous_run() {
        var s = RunProgress().reduce(ev("message.start"))
        s = s.reduce(todoEvent("completed", "pending"))
        s = s.reduce(ev("message.complete"))
        s = s.reduce(ev("message.start"))
        assertTrue(s.running)
        assertEquals(0, s.done)
        assertEquals(0, s.total)
        assertNull(s.tool)
    }

    // Case 6
    @Test fun message_complete_returns_to_idle() {
        var s = RunProgress().reduce(ev("message.start"))
        s = s.reduce(ev("message.complete"))
        assertFalse(s.running)
    }

    // Case 7
    @Test fun error_returns_to_idle() {
        var s = RunProgress().reduce(ev("message.start"))
        s = s.reduce(ev("error") { put("message", "boom") })
        assertFalse(s.running)
    }

    // Case 8
    @Test fun session_info_running_false_is_the_interrupt_backstop() {
        var s = RunProgress().reduce(ev("message.start"))
        s = s.reduce(todoEvent("completed", "pending"))
        s = s.reduce(ev("session.info") { put("running", false) })
        assertFalse(s.running)
        assertEquals(0, s.total)
    }

    @Test fun session_info_running_true_marks_running_when_connecting_mid_run() {
        val s = RunProgress().reduce(ev("session.info") { put("running", true) })
        assertTrue(s.running)
        assertEquals("s1", s.sessionId)
    }

    // Case 9 — tool_progress_mode "off" means no tool.* events ever arrive.
    @Test fun run_without_tool_events_stays_indeterminate() {
        var s = RunProgress().reduce(ev("message.start"))
        s = s.reduce(ev("message.delta") { put("text", "thinking") })
        assertTrue(s.running)
        assertFalse(s.determinate)
    }

    // Case 10
    @Test fun malformed_todos_payload_does_not_crash_and_stays_indeterminate() {
        var s = RunProgress().reduce(ev("message.start"))
        s = s.reduce(ev("tool.complete") { put("name", "todo"); put("todos", "not-an-array") })
        assertFalse(s.determinate)
        assertEquals(0, s.total)
    }

    @Test fun todo_items_with_missing_status_count_toward_total_but_not_done() {
        var s = RunProgress().reduce(ev("message.start"))
        s = s.reduce(ev("tool.complete") {
            put("name", "todo")
            putJsonArray("todos") { addJsonObject { put("id", "1"); put("content", "x") } }
        })
        assertEquals(0, s.done)
        assertEquals(1, s.total)
    }

    @Test fun non_todo_tool_complete_clears_the_tool_without_touching_counts() {
        var s = RunProgress().reduce(ev("message.start"))
        s = s.reduce(todoEvent("completed", "pending"))
        s = s.reduce(ev("tool.start") { put("name", "web_search") })
        s = s.reduce(ev("tool.complete") { put("name", "web_search") })
        assertNull(s.tool)
        assertEquals(1, s.done)
        assertEquals(2, s.total)
    }

    @Test fun tool_events_while_idle_do_not_start_a_phantom_run() {
        val s = RunProgress().reduce(ev("tool.start") { put("name", "web_search") })
        assertFalse(s.running)
        assertNull(s.tool)
    }

    @Test fun unrelated_events_leave_state_untouched() {
        val running = RunProgress().reduce(ev("message.start"))
        assertEquals(running, running.reduce(ev("approval.request") { put("command", "ls") }))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "com.hermes.client.data.progress.RunProgressTest"
```
Expected: FAIL — compilation error, `Unresolved reference: RunProgress`.

- [ ] **Step 3: Add the `todoCounts()` helper to ServerEvent.kt**

Append to `app/src/main/java/com/hermes/client/data/network/ServerEvent.kt` (after the existing `strList` helper on line 45). Add `import kotlinx.serialization.json.JsonArray` only if it is not already imported — it is (line 3).

```kotlin
/**
 * Counts todo items from a `tool.complete` payload's `todos` array (gateway sends the full list
 * as `{id, content, status}` objects). `done` counts `completed`; `total` counts every item that
 * is NOT `cancelled` — a cancelled task never completes, so including it would stall the progress
 * bar below 100% forever. Defensive like [str]: a malformed or absent payload yields 0 to 0
 * rather than throwing, because a throw here would escape the event collector.
 */
internal fun ServerEvent.todoCounts(): Pair<Int, Int> {
    val arr = payload["todos"] as? JsonArray ?: return 0 to 0
    var done = 0
    var total = 0
    for (el in arr) {
        val status = ((el as? JsonObject)?.get("status") as? JsonPrimitive)?.content?.lowercase()
        if (status == "cancelled") continue
        total++
        if (status == "completed") done++
    }
    return done to total
}
```

- [ ] **Step 4: Create the model and reducer**

Create `app/src/main/java/com/hermes/client/data/progress/RunProgress.kt`:

```kotlin
package com.hermes.client.data.progress

import com.hermes.client.data.network.ServerEvent
import com.hermes.client.data.network.bool
import com.hermes.client.data.network.str
import com.hermes.client.data.network.todoCounts

/**
 * State of the agent run currently in flight, derived purely from gateway WebSocket events.
 *
 * Deliberately NOT part of ChatUiState: that is scoped to an open chat screen and dies when the
 * app is backgrounded, which is exactly when this state must survive to drive a notification.
 */
data class RunProgress(
    val running: Boolean = false,
    val sessionId: String? = null,
    val tool: String? = null,
    val done: Int = 0,
    val total: Int = 0,
) {
    /** A determinate bar is only possible once the `todo` tool has reported a non-empty list. */
    val determinate: Boolean get() = total > 0
}

/**
 * Folds one gateway event into run state. Pure — no Android, no IO.
 *
 * `session.info.running` is the authoritative backstop: `message.complete` alone misses
 * interrupted and compacted turns, which would otherwise strand a permanent "running" state.
 * Tool events are ignored while idle so a late/stray `tool.*` cannot resurrect a finished run.
 */
fun RunProgress.reduce(event: ServerEvent): RunProgress = when (event.type) {
    "message.start" -> RunProgress(running = true, sessionId = event.sessionId)

    "tool.start" -> if (!running) this else copy(tool = event.str("name")?.ifBlank { null })

    "tool.complete" -> when {
        !running -> this
        event.str("name") == "todo" -> {
            val (d, t) = event.todoCounts()
            copy(tool = null, done = d, total = t)
        }
        else -> copy(tool = null)
    }

    "message.complete", "error" -> RunProgress()

    // Authoritative busy/idle signal. A missing `running` field leaves state untouched.
    "session.info" -> when (event.bool("running")) {
        false -> RunProgress()
        true -> if (running) this else RunProgress(running = true, sessionId = event.sessionId)
        null -> this
    }

    else -> this
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "com.hermes.client.data.progress.RunProgressTest"
```
Expected: PASS — 14 tests.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/hermes/client/data/progress/RunProgress.kt \
        app/src/main/java/com/hermes/client/data/network/ServerEvent.kt \
        app/src/test/java/com/hermes/client/data/progress/RunProgressTest.kt
git commit -m "feat: pure run-progress reducer over gateway run events"
```

---

### Task 2: RunProgressSpec + pure mapping

**Files:**
- Modify: `app/src/main/java/com/hermes/client/notifications/NotificationModels.kt` (add `runProgress` to `NotificationPrefs` at lines 4-8; add `RunProgressSpec`; add constants to `Notif` at lines 34-59)
- Create: `app/src/main/java/com/hermes/client/notifications/RunProgressMapper.kt`
- Test: `app/src/test/java/com/hermes/client/notifications/RunProgressMapperTest.kt`

**Interfaces:**
- Consumes: `RunProgress(running, sessionId, tool, done, total)` and `RunProgress.determinate` from Task 1.
- Produces: `data class RunProgressSpec(title, body, done, total, indeterminate, route, shortText)`; `fun RunProgress.toSpec(profileName: String?, prefs: NotificationPrefs): RunProgressSpec?`; `NotificationPrefs.runProgress: Boolean`; `Notif.CHANNEL_RUN_PROGRESS`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/hermes/client/notifications/RunProgressMapperTest.kt`:

```kotlin
package com.hermes.client.notifications

import com.hermes.client.data.progress.RunProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunProgressMapperTest {
    private val on = NotificationPrefs(enabled = true, runProgress = true)

    @Test fun idle_state_maps_to_null() {
        assertNull(RunProgress().toSpec("acme", on))
    }

    @Test fun running_maps_to_an_indeterminate_spec_titled_with_the_tenant() {
        val spec = RunProgress(running = true, sessionId = "s1").toSpec("acme", on)!!
        assertEquals("acme · agent running", spec.title)
        assertEquals("Working…", spec.body)
        assertTrue(spec.indeterminate)
        assertEquals("chat/s1", spec.route)
        assertNull(spec.shortText)
    }

    @Test fun active_tool_appears_in_the_body() {
        val spec = RunProgress(running = true, tool = "web_search").toSpec("acme", on)!!
        assertEquals("Calling tool: web_search", spec.body)
    }

    @Test fun todo_counts_make_the_spec_determinate_with_a_short_chip() {
        val spec = RunProgress(running = true, done = 3, total = 5).toSpec("globex", on)!!
        assertFalse(spec.indeterminate)
        assertEquals(3, spec.done)
        assertEquals(5, spec.total)
        assertEquals("3/5", spec.shortText)
    }

    @Test fun a_missing_profile_falls_back_to_a_generic_title() {
        val spec = RunProgress(running = true).toSpec(null, on)!!
        assertEquals("Agent running", spec.title)
    }

    @Test fun a_blank_profile_falls_back_to_a_generic_title() {
        val spec = RunProgress(running = true).toSpec("   ", on)!!
        assertEquals("Agent running", spec.title)
    }

    @Test fun master_notification_toggle_off_suppresses_progress() {
        val prefs = NotificationPrefs(enabled = false, runProgress = true)
        assertNull(RunProgress(running = true).toSpec("acme", prefs))
    }

    @Test fun run_progress_toggle_off_suppresses_progress() {
        val prefs = NotificationPrefs(enabled = true, runProgress = false)
        assertNull(RunProgress(running = true).toSpec("acme", prefs))
    }

    @Test fun run_progress_defaults_to_on() {
        assertTrue(NotificationPrefs().runProgress)
    }

    @Test fun a_run_with_no_session_id_has_no_route() {
        val spec = RunProgress(running = true, sessionId = null).toSpec("acme", on)!!
        assertNull(spec.route)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "com.hermes.client.notifications.RunProgressMapperTest"
```
Expected: FAIL — `Unresolved reference: toSpec`, and `No value passed for parameter 'runProgress'`.

- [ ] **Step 3: Extend NotificationModels.kt**

Replace the `NotificationPrefs` declaration (currently lines 4-8) with:

```kotlin
/** User's notification preferences (persisted); off by default. */
data class NotificationPrefs(
    val enabled: Boolean = false,
    val approvals: Boolean = true,
    val runFinished: Boolean = true,
    val runProgress: Boolean = true,
)
```

Add this data class immediately after the existing `NotificationSpec` declaration (currently ends line 31):

```kotlin
/**
 * A platform-independent description of the live run-progress notification, so mapping stays
 * unit-testable. [indeterminate] means no todo counts are available yet; [shortText] is the
 * status-bar chip text used on API 36+ promoted notifications (null when indeterminate).
 */
data class RunProgressSpec(
    val title: String,
    val body: String,
    val done: Int,
    val total: Int,
    val indeterminate: Boolean,
    val route: String?,
    val shortText: String?,
)
```

Inside the `Notif` object, add the channel constant after `CHANNEL_ACTIVITY` (line 37):

```kotlin
    // Live in-flight run progress. IMPORTANCE_LOW (not MIN like CHANNEL_SERVICE) so the ongoing
    // progress notification is actually glanceable in the shade and eligible for promotion to a
    // status-bar Live Update on API 36+, while still making no sound.
    const val CHANNEL_RUN_PROGRESS = "run_progress"
```

And add the run-lifecycle event constants after `EVENT_ERROR` (line 50):

```kotlin
    // Run-lifecycle events consumed by the run-progress reducer (not by toNotificationSpec).
    // `session.info` carries "running": bool and is the authoritative busy/idle backstop.
    const val EVENT_MESSAGE_START = "message.start"
    const val EVENT_TOOL_START = "tool.start"
    const val EVENT_TOOL_COMPLETE = "tool.complete"
    const val EVENT_SESSION_INFO = "session.info"
```

- [ ] **Step 4: Create the mapper**

Create `app/src/main/java/com/hermes/client/notifications/RunProgressMapper.kt`:

```kotlin
package com.hermes.client.notifications

import com.hermes.client.data.progress.RunProgress

/**
 * Pure mapping from run state to a notification description, or null when nothing should be
 * shown. Mirrors [toNotificationSpec]: all decisions live here so they are testable without
 * Android, and [HermesNotifier] only renders.
 */
fun RunProgress.toSpec(profileName: String?, prefs: NotificationPrefs): RunProgressSpec? {
    if (!prefs.enabled || !prefs.runProgress) return null
    if (!running) return null
    val tenant = profileName?.takeIf { it.isNotBlank() }
    return RunProgressSpec(
        title = if (tenant != null) "$tenant · agent running" else "Agent running",
        body = tool?.let { "Calling tool: $it" } ?: "Working…",
        done = done,
        total = total,
        indeterminate = !determinate,
        route = sessionId?.let { "chat/$it" },
        shortText = if (determinate) "$done/$total" else null,
    )
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "com.hermes.client.notifications.RunProgressMapperTest"
```
Expected: PASS — 10 tests.

- [ ] **Step 6: Run the full suite to confirm the new pref field broke nothing**

Run:
```bash
./gradlew :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL. `NotificationPrefs` gained a defaulted field, so existing constructor calls still compile.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/hermes/client/notifications/NotificationModels.kt \
        app/src/main/java/com/hermes/client/notifications/RunProgressMapper.kt \
        app/src/test/java/com/hermes/client/notifications/RunProgressMapperTest.kt
git commit -m "feat: map run progress to a platform-independent notification spec"
```

---

### Task 3: Persist the runProgress preference and expose a toggle

**Files:**
- Modify: `app/src/main/java/com/hermes/client/data/repository/NotificationSettings.kt`
- Modify: `app/src/main/java/com/hermes/client/ui/settings/NotificationsScreen.kt`

**Interfaces:**
- Consumes: `NotificationPrefs.runProgress` from Task 2.
- Produces: `NotificationSettings.setRunProgress(v: Boolean)`; `NotificationsViewModel.setRunProgress(v: Boolean)`.

- [ ] **Step 1: Persist the preference**

In `app/src/main/java/com/hermes/client/data/repository/NotificationSettings.kt`, update the KDoc (line 13), add the key after `kRunFinished` (line 17), read it in the `map` block (after line 23), and add the setter after `setRunFinished` (line 29). The full file becomes:

```kotlin
package com.hermes.client.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.hermes.client.notifications.NotificationPrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.notificationDataStore by preferencesDataStore(name = "notifications")

/**
 * Device-local notification preferences (master toggle + approvals + run-finished +
 * run-progress). Off by default.
 */
class NotificationSettings(private val context: Context) {
    private val kEnabled = booleanPreferencesKey("enabled")
    private val kApprovals = booleanPreferencesKey("approvals")
    private val kRunFinished = booleanPreferencesKey("runFinished")
    private val kRunProgress = booleanPreferencesKey("runProgress")

    val prefs: Flow<NotificationPrefs> = context.notificationDataStore.data.map { p ->
        NotificationPrefs(
            enabled = p[kEnabled] ?: false,
            approvals = p[kApprovals] ?: true,
            runFinished = p[kRunFinished] ?: true,
            runProgress = p[kRunProgress] ?: true,
        )
    }

    suspend fun setEnabled(v: Boolean) = context.notificationDataStore.edit { it[kEnabled] = v }
    suspend fun setApprovals(v: Boolean) = context.notificationDataStore.edit { it[kApprovals] = v }
    suspend fun setRunFinished(v: Boolean) = context.notificationDataStore.edit { it[kRunFinished] = v }
    suspend fun setRunProgress(v: Boolean) = context.notificationDataStore.edit { it[kRunProgress] = v }
}
```

- [ ] **Step 2: Add the ViewModel setter**

In `app/src/main/java/com/hermes/client/ui/settings/NotificationsScreen.kt`, add after `setRunFinished` (line 49):

```kotlin
    fun setRunProgress(v: Boolean) = viewModelScope.launch { settings.setRunProgress(v) }
```

- [ ] **Step 3: Add the toggle row**

In the same file, add after the "Run finished" `ToggleRow` block (which currently ends at line 102, before the closing `}` of the `Column`):

```kotlin
            HorizontalDivider()
            ToggleRow(
                "Live run progress",
                "Show an ongoing notification with live progress while an agent run is in flight",
                prefs.runProgress,
                enabled = prefs.enabled,
            ) { vm.setRunProgress(it) }
```

- [ ] **Step 4: Compile and run the suite**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:compileDebugKotlin && ./gradlew :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hermes/client/data/repository/NotificationSettings.kt \
        app/src/main/java/com/hermes/client/ui/settings/NotificationsScreen.kt
git commit -m "feat: persist and expose the live run-progress preference"
```

---

### Task 4: Render the progress notification

**Files:**
- Modify: `app/src/main/java/com/hermes/client/notifications/HermesNotifier.kt`

**Interfaces:**
- Consumes: `RunProgressSpec(title, body, done, total, indeterminate, route, shortText)` and `Notif.CHANNEL_RUN_PROGRESS` from Task 2; the existing private `openIntent(route, id)` (lines 71-77) and `accentArgb(profile, dark)` from `com.hermes.client.ui.theme`.
- Produces: `HermesNotifier.postRunProgress(spec: RunProgressSpec, profile: String?)`; `HermesNotifier.cancelRunProgress()`; `HermesNotifier.Companion.RUN_PROGRESS_NOTIFICATION_ID = 1003`.

This task has no unit test: it is pure Android framework rendering with no branching logic worth faking, and the repo does not use Robolectric. All decision logic was tested in Tasks 1-2. Verification is compilation plus the on-device check in Task 5.

- [ ] **Step 1: Add the channel**

In `ensureChannels()` (lines 19-32), add after the `CHANNEL_ACTIVITY` block:

```kotlin
        sys.createNotificationChannel(
            NotificationChannel(Notif.CHANNEL_RUN_PROGRESS, "Run progress", NotificationManager.IMPORTANCE_LOW),
        )
```

- [ ] **Step 2: Add the imports**

Add to the import block at the top of `HermesNotifier.kt`:

```kotlin
import android.content.res.Configuration
import android.os.Build
import com.hermes.client.ui.theme.accentArgb
```

- [ ] **Step 3: Add the post/cancel methods**

Add after the existing `cancel(id: Int)` (line 69):

```kotlin
    /**
     * Posts (or updates) the single ongoing run-progress notification. On API 36+ this uses the
     * platform ProgressStyle so the system can promote it to a status-bar Live Update; below that
     * it falls back to an ordinary ongoing progress notification.
     *
     * androidx.core 1.16.0 has no NotificationCompat.ProgressStyle, so the API 36+ branch builds
     * with the platform Notification.Builder rather than upgrading the dependency.
     */
    fun postRunProgress(spec: RunProgressSpec, profile: String?) {
        if (!mgr.areNotificationsEnabled()) return
        val accent = accentFor(profile)
        val n = if (Build.VERSION.SDK_INT >= 36) buildPromoted(spec, accent) else buildCompat(spec, accent)
        mgr.notify(RUN_PROGRESS_NOTIFICATION_ID, n)
    }

    fun cancelRunProgress() = mgr.cancel(RUN_PROGRESS_NOTIFICATION_ID)

    /** Tenant accent, resolved against the system's current night mode. Chrome only. */
    private fun accentFor(profile: String?): Int {
        val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return accentArgb(profile, dark)
    }

    @androidx.annotation.RequiresApi(36)
    private fun buildPromoted(spec: RunProgressSpec, accent: Int): Notification {
        val style = Notification.ProgressStyle().setProgressIndeterminate(spec.indeterminate)
        if (!spec.indeterminate) {
            // ProgressStyle has no setProgressMax(): the bar's maximum is the SUM of its segment
            // lengths, so one segment of `total` gives a bar of exactly that length.
            style.addProgressSegment(Notification.ProgressStyle.Segment(spec.total).setColor(accent))
            style.setProgress(spec.done)
        }
        val b = Notification.Builder(context, Notif.CHANNEL_RUN_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_hermes)
            .setContentTitle(spec.title)
            .setContentText(spec.body)
            .setStyle(style)
            .setOngoing(true)
            .setColor(accent)
            .setContentIntent(openIntent(spec.route, RUN_PROGRESS_NOTIFICATION_ID))
        // Status-bar chip text on a promoted notification. The system decides promotion itself
        // (Notification.FLAG_PROMOTED_ONGOING); there is no request API to call.
        spec.shortText?.let { b.setShortCriticalText(it) }
        return b.build()
    }

    private fun buildCompat(spec: RunProgressSpec, accent: Int): Notification =
        NotificationCompat.Builder(context, Notif.CHANNEL_RUN_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_hermes)
            .setContentTitle(spec.title)
            .setContentText(spec.body)
            .setProgress(spec.total, spec.done, spec.indeterminate)
            .setOngoing(true)
            .setColor(accent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent(spec.route, RUN_PROGRESS_NOTIFICATION_ID))
            .build()
```

- [ ] **Step 4: Add the id constant**

In the `companion object` (lines 109-111), add after `SERVICE_NOTIFICATION_ID`:

```kotlin
        // Distinct from SERVICE_NOTIFICATION_ID (1001) and from toNotificationSpec's 1002
        // collision fallback, so the ongoing progress notification can never clobber either.
        const val RUN_PROGRESS_NOTIFICATION_ID = 1003
```

- [ ] **Step 5: Compile**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/hermes/client/notifications/HermesNotifier.kt
git commit -m "feat: render the live run-progress notification with API 36 progress style"
```

---

### Task 5: Wire the service and verify end to end

**Files:**
- Modify: `app/src/main/java/com/hermes/client/notifications/GatewayConnectionService.kt`

**Interfaces:**
- Consumes: `RunProgress()` / `reduce(event)` (Task 1), `toSpec(profileName, prefs)` (Task 2), `postRunProgress(spec, profile)` / `cancelRunProgress()` (Task 4), and the existing `ProfileManager.active: StateFlow<String?>`.
- Produces: nothing consumed downstream — this is the integration task.

- [ ] **Step 1: Inject ProfileManager and add the state fields**

In `app/src/main/java/com/hermes/client/notifications/GatewayConnectionService.kt`, add the injection after `notifier` (line 24):

```kotlin
    @Inject lateinit var profiles: com.hermes.client.data.repository.ProfileManager
```

Add after the `appInForeground` field (line 36):

```kotlin
    // Live run state, folded from the same event stream. @Volatile for the same reason as the
    // fields above: the collector scope has no single-thread dispatcher.
    @Volatile private var runProgress = com.hermes.client.data.progress.RunProgress()

    // Last spec actually posted. message.delta fires many times per second and does not change
    // the spec, so re-posting on every event would burn cycles and visibly flicker the
    // notification. Only act when the derived spec actually changes.
    @Volatile private var lastRunSpec: RunProgressSpec? = null
```

- [ ] **Step 2: Drive the notification from the collector**

Replace the event collector block (currently lines 63-71) with:

```kotlin
        scope.launch {
            client.events.collect { event ->
                // One malformed/unexpected event must not crash the process — mirror the guard
                // ChatViewModel's reduce() uses around event handling.
                runCatching {
                    toNotificationSpec(event, latestPrefs, appInForeground)?.let { notifier.post(it) }
                    updateRunProgress(event)
                }
            }
        }
```

Add this private method after `onStartCommand`/`onBind` (after line 75):

```kotlin
    /** Folds the event into run state and posts/cancels the progress notification on change. */
    private fun updateRunProgress(event: com.hermes.client.data.network.ServerEvent) {
        runProgress = runProgress.reduce(event)
        val spec = runProgress.toSpec(profiles.active.value, latestPrefs)
        if (spec == lastRunSpec) return
        lastRunSpec = spec
        if (spec != null) notifier.postRunProgress(spec, profiles.active.value)
        else notifier.cancelRunProgress()
    }
```

Add the required imports at the top of the file:

```kotlin
import com.hermes.client.data.progress.reduce
```

- [ ] **Step 3: Cancel on destroy**

In `onDestroy()` (lines 89-96), add immediately after `scope.cancel()`:

```kotlin
        // A stopped service must never strand an ongoing "running" notification.
        notifier.cancelRunProgress()
```

- [ ] **Step 4: Compile and run the full suite**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:compileDebugKotlin && ./gradlew :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Build the beta variant**

Run:
```bash
./gradlew :app:assembleBeta
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/hermes/client/notifications/GatewayConnectionService.kt
git commit -m "feat: drive the live run-progress notification from the gateway event stream"
```

- [ ] **Step 7: On-device verification (best-effort)**

Target the emulator explicitly — `emulator-5554`. **Do NOT touch the physical device (serial `57150DLCH00385`).**

```bash
adb -s emulator-5554 install -r app/build/outputs/apk/beta/app-beta.apk
```

Then, in the app: enable Settings → Notifications → "Enable notifications", confirm "Live run progress" is on, background the app, and send a prompt that makes the agent use tools. Expect an ongoing "acme · agent running" notification whose body tracks the active tool, becoming a determinate bar if the model uses the `todo` tool, and disappearing when the run ends.

If the emulator is unavailable or no gateway is reachable, record that verification was skipped and why — do not fake the result.

---

## Self-Review

**Spec coverage:**

| Spec requirement | Task |
|---|---|
| `RunProgress` model + pure reducer | 1 |
| Event→state table (all 7 rows) | 1 |
| `session.info` backstop + `ServerEvent` parsing | 1 |
| Cancelled excluded from total; determinate iff `total > 0` | 1 |
| `RunProgressSpec` + pure `toSpec` | 2 |
| Tenant title / tool body / accent | 2 (title, body), 4 (accent) |
| `runProgress` pref + persistence + toggle | 2 (model), 3 (store + UI) |
| `run_progress` channel at IMPORTANCE_LOW | 4 |
| `RUN_PROGRESS_NOTIFICATION_ID = 1003` | 4 |
| API-36 ProgressStyle / API 26-35 fallback | 4 |
| Cancel on complete/error/session.info-false | 1 (state) + 5 (cancel call) |
| Cancel in `onDestroy()` | 5 |
| All 10 spec test cases | 1 (cases 1-10), 2 (mapping) |

No gaps.

**Placeholder scan:** none — every code step carries complete code, and the one open fork from the spec (`NotificationCompat.ProgressStyle` availability) is resolved above with verified `javap` evidence.

**Type consistency:** `RunProgress(running, sessionId, tool, done, total)` and `determinate` are used identically in Tasks 1, 2 and 5. `RunProgressSpec(title, body, done, total, indeterminate, route, shortText)` is declared in Task 2 and consumed unchanged in Tasks 4 and 5. `postRunProgress(spec, profile)` / `cancelRunProgress()` are declared in Task 4 and called with matching arity in Task 5. `toSpec(profileName, prefs)` is declared in Task 2 and called with matching arity in Task 5.

**One risk carried into execution:** an indeterminate `ProgressStyle` with no segments is assumed valid on API 36. If the device check in Task 5 shows no bar rendering, add a single full-length segment and rely on `setProgressIndeterminate(true)` for the animation.
