# Completion Notifications Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Notify on agent run-finished (background-only) and needs-input (`clarify.request`) by mapping existing WS events to notifications.

**Architecture:** A pure `toNotificationSpec(event, prefs, appInForeground)` gains branches for `clarify.request` (always) and `run.completed`/`run.failed` (only when backgrounded). `GatewayConnectionService` supplies the live foreground flag from `ProcessLifecycleOwner`. A new DEFAULT "Activity" channel + a `runFinished` settings toggle.

**Tech Stack:** Kotlin, Hilt, `androidx.lifecycle:lifecycle-process`, NotificationCompat, JUnit.

## Global Constraints

- **No gateway/bridge API changes** — map existing WS events only; reuse the notifications plumbing/channels/`extra_route`. Accepted limit (do NOT fix here): notifications only fire while the foreground service is alive.
- Deferred: cron-push, FCM durability, run-finished payload enrichment.
- JDK 21: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
- Compile: `./gradlew :app:compileDebugKotlin --console=plain`. Tests: `./gradlew :app:testDebugUnitTest --console=plain`. Beta: `./gradlew :app:assembleBeta`.
- **No AI/assistant attribution** in commits, files, or PRs.

## Grounding

- `NotificationMapper.toNotificationSpec(event: ServerEvent, prefs: NotificationPrefs): NotificationSpec?` — early-returns null on `!prefs.enabled` or `event.sessionId == null`; `id = (event.type + sid).hashCode()` bumped off `1001`; single `Notif.EVENT_APPROVAL` branch builds `route="chat/$sid"`.
- `NotificationSpec(id, channelId, title, body, route, actions, groupKey)` (no importance). `NotificationPrefs(enabled=false, approvals=true)`. `NotifAction(label, action, sessionId)`.
- `ServerEvent(type, sessionId, payload)`; `event.str("key")` (safe payload string). `clarify.request` carries `question`; `run.*` has only the generic `session_id`.
- `HermesNotifier.ensureChannels()` creates `CHANNEL_APPROVALS` (HIGH) + `CHANNEL_SERVICE` (MIN).
- `GatewayConnectionService`: `@Volatile latestPrefs` fed by `settings.prefs.collect`; collector runs `toNotificationSpec(event, latestPrefs)?.let { notifier.post(it) }`.
- `NotificationsScreen` has an "approval alerts" toggle bound to a notifications VM/prefs store.

---

### Task 1: Models + pure mapper + tests (TDD)

**Files:**
- Modify: `app/src/main/java/com/hermes/client/notifications/NotificationModels.kt`
- Modify: `app/src/main/java/com/hermes/client/notifications/NotificationMapper.kt`
- Test: `app/src/test/java/com/hermes/client/notifications/NotificationMapperTest.kt`

**Interfaces:** Produces `Notif.CHANNEL_ACTIVITY`, `Notif.EVENT_RUN_COMPLETED`, `Notif.EVENT_RUN_FAILED`, `Notif.EVENT_CLARIFY`; `NotificationPrefs.runFinished`; `toNotificationSpec(event, prefs, appInForeground: Boolean)`.

- [ ] **Step 1: Add the constants + pref**

In `NotificationModels.kt`, add to `object Notif` (next to the existing consts):
```kotlin
    const val CHANNEL_ACTIVITY = "activity"
    const val EVENT_RUN_COMPLETED = "run.completed"
    const val EVENT_RUN_FAILED = "run.failed"
    const val EVENT_CLARIFY = "clarify.request"
```
And add to `NotificationPrefs`:
```kotlin
data class NotificationPrefs(
    val enabled: Boolean = false,
    val approvals: Boolean = true,
    val runFinished: Boolean = true,
)
```

- [ ] **Step 2: Update the test (signature + new cases)** — `NotificationMapperTest.kt`

Read the file's existing `event(...)` helper + prefs fixtures first. Update every `toNotificationSpec(event, prefs)` call to pass a foreground flag, REPLACE the old `run.completed → null` assertion, and add the branches. The tests should read like (adapt to the existing `event(...)`/prefs helpers):
```kotlin
    private val on = NotificationPrefs(enabled = true, approvals = true, runFinished = true)

    @Test fun approval_notifies_regardless_of_foreground() {
        val e = event("approval.request", "c1", "prompt" to "May I run rm?")
        assertNotNull(toNotificationSpec(e, on, appInForeground = false))
        assertNotNull(toNotificationSpec(e, on, appInForeground = true))
    }
    @Test fun approval_off_when_pref_off() {
        val e = event("approval.request", "c1", "prompt" to "x")
        assertNull(toNotificationSpec(e, on.copy(approvals = false), appInForeground = false))
    }
    @Test fun clarify_notifies_with_question_regardless_of_foreground() {
        val e = event("clarify.request", "c1", "question" to "Which repo?")
        val spec = toNotificationSpec(e, on, appInForeground = true)!!
        assertEquals("Needs your input", spec.title)
        assertEquals("Which repo?", spec.body)
        assertEquals("chat/c1", spec.route)
        assertTrue(spec.actions.isEmpty())
    }
    @Test fun clarify_off_when_approvals_off() {
        val e = event("clarify.request", "c1", "question" to "x")
        assertNull(toNotificationSpec(e, on.copy(approvals = false), appInForeground = false))
    }
    @Test fun run_completed_only_when_backgrounded() {
        val e = event("run.completed", "c1")
        assertNotNull(toNotificationSpec(e, on, appInForeground = false))
        assertNull(toNotificationSpec(e, on, appInForeground = true))
    }
    @Test fun run_completed_off_when_pref_off() {
        val e = event("run.completed", "c1")
        assertNull(toNotificationSpec(e, on.copy(runFinished = false), appInForeground = false))
    }
    @Test fun run_failed_title() {
        val spec = toNotificationSpec(event("run.failed", "c1"), on, appInForeground = false)!!
        assertEquals("Run failed", spec.title)
        assertEquals(Notif.CHANNEL_ACTIVITY, spec.channelId)
    }
    @Test fun no_session_is_null() {
        assertNull(toNotificationSpec(event("run.completed", null), on, appInForeground = false))
    }
    @Test fun disabled_is_null() {
        assertNull(toNotificationSpec(event("approval.request", "c1", "prompt" to "x"), on.copy(enabled = false), appInForeground = false))
    }
```
(If the existing `event(...)` helper can't build an event with a null session id, add a small overload or use whatever null-session mechanism the fixtures already have.)

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*NotificationMapperTest*' --console=plain 2>&1 | tail -8`
Expected: FAIL — new signature / branches not present.

- [ ] **Step 4: Implement the mapper** — `NotificationMapper.kt`

```kotlin
fun toNotificationSpec(event: ServerEvent, prefs: NotificationPrefs, appInForeground: Boolean): NotificationSpec? {
    if (!prefs.enabled) return null
    val sid = event.sessionId ?: return null
    var id = (event.type + sid).hashCode()
    if (id == 1001) id = 1002
    return when (event.type) {
        Notif.EVENT_APPROVAL -> if (!prefs.approvals) null else NotificationSpec(
            id = id, channelId = Notif.CHANNEL_APPROVALS, title = "Approval needed",
            body = event.str("prompt") ?: "The agent is waiting for your approval.",
            route = "chat/$sid",
            actions = listOf(NotifAction("Approve", Notif.ACTION_APPROVE, sid), NotifAction("Deny", Notif.ACTION_DENY, sid)),
            groupKey = "approval",
        )
        // Needs-you: always notify (ignores foreground); tap opens the chat to answer.
        Notif.EVENT_CLARIFY -> if (!prefs.approvals) null else NotificationSpec(
            id = id, channelId = Notif.CHANNEL_APPROVALS, title = "Needs your input",
            body = event.str("question") ?: "The agent has a question.",
            route = "chat/$sid", actions = emptyList(), groupKey = "approval",
        )
        // Run finished: only when backgrounded; generic body (no showable payload fields client-side).
        Notif.EVENT_RUN_COMPLETED -> if (!prefs.runFinished || appInForeground) null else NotificationSpec(
            id = id, channelId = Notif.CHANNEL_ACTIVITY, title = "Run finished",
            body = "Your agent finished — tap to view.", route = "chat/$sid", actions = emptyList(), groupKey = "run",
        )
        Notif.EVENT_RUN_FAILED -> if (!prefs.runFinished || appInForeground) null else NotificationSpec(
            id = id, channelId = Notif.CHANNEL_ACTIVITY, title = "Run failed",
            body = "The agent run failed — tap to view.", route = "chat/$sid", actions = emptyList(), groupKey = "run",
        )
        else -> null
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*NotificationMapperTest*' --console=plain 2>&1 | tail -8`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/hermes/client/notifications/NotificationModels.kt app/src/main/java/com/hermes/client/notifications/NotificationMapper.kt app/src/test/java/com/hermes/client/notifications/NotificationMapperTest.kt
git commit -m "feat(notifications): map run-finished (bg) + clarify; foreground-aware mapper"
```

---

### Task 2: Activity notification channel

**Files:** Modify `app/src/main/java/com/hermes/client/notifications/HermesNotifier.kt`

**Interfaces:** Consumes `Notif.CHANNEL_ACTIVITY` (Task 1).

- [ ] **Step 1: Add the channel in `ensureChannels()`** (after the existing channels)

```kotlin
    sys.createNotificationChannel(
        NotificationChannel(Notif.CHANNEL_ACTIVITY, "Activity", NotificationManager.IMPORTANCE_DEFAULT),
    )
```

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin --console=plain 2>&1 | grep -E "^e: |BUILD" | head`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hermes/client/notifications/HermesNotifier.kt
git commit -m "feat(notifications): add DEFAULT-importance Activity channel"
```

---

### Task 3: Foreground flag in the service + dependency

**Files:**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`
- Modify: `app/src/main/java/com/hermes/client/notifications/GatewayConnectionService.kt`

**Interfaces:** Consumes `toNotificationSpec(event, prefs, appInForeground)` (Task 1).

- [ ] **Step 1: Add the `lifecycle-process` dependency**

In `gradle/libs.versions.toml`, add under `[libraries]` (reusing the pinned `lifecycle` version ref):
```toml
lifecycle-process = { module = "androidx.lifecycle:lifecycle-process", version.ref = "lifecycle" }
```
In `app/build.gradle.kts`, add to `dependencies` (next to the other lifecycle deps):
```kotlin
    implementation(libs.lifecycle.process)
```

- [ ] **Step 2: Observe process lifecycle into a `@Volatile` flag**

Read `GatewayConnectionService.kt` `onCreate` + the event collector first. Add a field near `latestPrefs`:
```kotlin
    @Volatile private var appInForeground = false
```
In `onCreate` (the observer must be added on the main thread — `ProcessLifecycleOwner` requires it):
```kotlin
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(
                androidx.lifecycle.LifecycleEventObserver { _, e ->
                    when (e) {
                        androidx.lifecycle.Lifecycle.Event.ON_START -> appInForeground = true
                        androidx.lifecycle.Lifecycle.Event.ON_STOP -> appInForeground = false
                        else -> {}
                    }
                },
            )
        }
```

- [ ] **Step 3: Pass the flag into the mapper**

Change the event-collector call:
```kotlin
            runCatching {
                toNotificationSpec(event, latestPrefs, appInForeground)?.let { notifier.post(it) }
            }
```

- [ ] **Step 4: Compile + assemble**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --console=plain 2>&1 | grep -E "^e: |FAILED|BUILD" | head` → `BUILD SUCCESSFUL`, no `FAILED`.
Run: `./gradlew :app:assembleBeta --console=plain 2>&1 | grep -E "^e: |BUILD" | tail -2` → `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/java/com/hermes/client/notifications/GatewayConnectionService.kt
git commit -m "feat(notifications): background-scope run-finished via ProcessLifecycleOwner"
```

---

### Task 4: `runFinished` settings toggle

**Files:**
- Modify: `app/src/main/java/com/hermes/client/ui/settings/NotificationsScreen.kt` + its ViewModel + the prefs store (discover on read)

**Interfaces:** Consumes `NotificationPrefs.runFinished` (Task 1).

- [ ] **Step 1: Persist `runFinished`**

Read the prefs store that backs `enabled`/`approvals` (the DataStore/`SettingsRepository` + the notifications VM). Mirror the `approvals` key end-to-end: a DataStore preference key `runFinished` (default `true`), its read into the `NotificationPrefs` flow, and a setter (e.g. `setRunFinished(Boolean)`), following the exact shape of the existing `approvals` key/flow/setter.

- [ ] **Step 2: Add the toggle row to `NotificationsScreen`**

Mirror the existing "approval alerts" toggle row (a `Switch`/`ListItem` bound to the VM), shown when notifications are enabled, labeled **"Run finished"** with a one-line subtitle like "Notify when an agent run completes (while the app is in the background)", bound to `runFinished` + its setter.

- [ ] **Step 3: Compile + suite + assemble**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --console=plain 2>&1 | grep -E "^e: |FAILED|BUILD" | head` → `BUILD SUCCESSFUL`, no `FAILED`.
Run: `./gradlew :app:assembleBeta --console=plain 2>&1 | grep -E "^e: |BUILD" | tail -2` → `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/settings/NotificationsScreen.kt
git add -A  # picks up the VM + prefs-store changes
git commit -m "feat(notifications): Run finished settings toggle"
```

---

### Task 5: On-device verification

**Files:** none (verification only). **Harness note:** the mock gateway (`$CLAUDE_JOB_DIR/tmp/mockgw.py`, not committed) likely needs a small test-only addition to emit `run.completed` and `clarify.request` over the WS on demand (e.g. a trigger endpoint or a timed emit after connect). Add it to the mock only — never to the repo.

- [ ] **Step 1: Build + install + enable notifications**

`./gradlew :app:assembleBeta`; install; connect to the mock; in Settings → Notifications, enable notifications (grant POST_NOTIFICATIONS) and confirm "Run finished" is on.

- [ ] **Step 2: Verify**

1. **Backgrounded run-finished:** background the app; have the mock emit `run.completed` (with a `session_id`) → a **"Run finished"** notification appears on the Activity channel; tapping it opens that chat (deep-link).
2. **Foreground suppression:** with the app foregrounded on a chat, emit `run.completed` → **no** notification.
3. **Clarify:** emit `clarify.request` (with `question` + `session_id`) → a **"Needs your input"** notification with the question as body; tap opens the chat.
4. **Toggle off:** turn "Run finished" off in settings; emit `run.completed` backgrounded → **no** notification. (Approvals still fire.)
5. Approvals behavior unchanged (emit `approval.request` → Approve/Deny inline actions still work).

- [ ] **Step 3: Commit (only if verification-only fixups were needed)**

```bash
git add -A
git commit -m "chore(notifications): completion-notifications verification fixups"   # only if needed
```

---

## Self-Review

- **Spec coverage:** Element 1 (models + channels consts) → Task 1 Step 1; Element 2 (pure mapper + tests) → Task 1; the Activity channel creation → Task 2; Element 3 (dep + ProcessLifecycleOwner flag + collector wiring) → Task 3; Element 4 (settings toggle + persistence) → Task 4; on-device → Task 5. Accepted FGS limit + deferred items honored (nothing adds FCM/cron/gateway).
- **Placeholder scan:** mapper + test + model code fully shown. The "discover on read" spots (Task 1 Step 2 `event(...)` helper; Task 4 prefs store + VM) are unavoidable (their exact shape lives in unread files) but the required change is fully specified (mirror `approvals`). Task-5 commit is conditional; the mock addition is explicitly harness-only.
- **Type consistency:** `toNotificationSpec(event, prefs, appInForeground: Boolean)` defined in Task 1, consumed in Task 3; `Notif.CHANNEL_ACTIVITY`/`EVENT_*`, `NotificationPrefs.runFinished`, `NotificationSpec(...)` fields consistent across tasks and with the codebase.

**Ordering:** Task 1 (pure core) → Task 2 (channel) → Task 3 (service flag, consumes the new signature) → Task 4 (settings) → Task 5 verify.
