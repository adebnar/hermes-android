# Push Notifications Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An opt-in foreground service holds the app-wide gateway WebSocket and posts local notifications for approval requests (inline Approve/Deny), cron-run completions, and messaging replies.

**Architecture:** A foreground service observes the existing `@Singleton HermesGatewayClient.events` flow and posts notifications via a pure `ServerEvent → NotificationSpec?` mapper. Client-only (no FCM/gateway changes). Active-profile scope. Off by default; toggled from a new Settings → Notifications page.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Hilt, DataStore, OkHttp WebSocket (existing), `androidx.core` `NotificationManagerCompat`, JUnit + MockK.

## Global Constraints

- JDK 21 toolchain via `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
- compileSdk 36, minSdk 26, targetSdk 36. AGP 8.13.2, Kotlin 2.2.21.
- Build variants: run tests with `./gradlew :app:testDebugUnitTest`; build beta APK with `:app:assembleBeta`.
- No AI/assistant attribution in commits.
- Feature is **off by default**; **active-profile scope only** (one WebSocket, one tenant).
- Reuse existing patterns: DataStore stores like `ProfileAccentStore`; settings sub-pages `XScreen(onBack)` + `composable("settings_X")`; the shared `HermesTopBar`.
- The **only confirmed** event type is `"approval.request"`. Cron/messaging type strings are **best-guess and MUST be verified** against the gateway in Task 8 — they live as named constants so a fix is one line.

---

### Task 1: Notification model types + event/channel constants

**Files:**
- Create: `app/src/main/java/com/hermes/client/notifications/NotificationModels.kt`

**Interfaces:**
- Produces:
  - `data class NotificationPrefs(val enabled: Boolean = false, val approvals: Boolean = true, val cron: Boolean = true, val messaging: Boolean = true)`
  - `data class NotifAction(val label: String, val action: String, val sessionId: String)`
  - `data class NotificationSpec(val id: Int, val channelId: String, val title: String, val body: String, val route: String?, val actions: List<NotifAction>, val groupKey: String)`
  - Constants object `Notif`: `CHANNEL_APPROVALS="approvals"`, `CHANNEL_ACTIVITY="activity"`, `CHANNEL_SERVICE="service"`, `EVENT_APPROVAL="approval.request"`, `EVENT_CRON_DONE="cron.completed"` /* VERIFY */, `EVENT_MSG="message.received"` /* VERIFY */, `ACTION_APPROVE="approve"`, `ACTION_DENY="deny"`.

- [ ] **Step 1: Create the file with the model types and constants**

```kotlin
package com.hermes.client.notifications

/** User's notification preferences (persisted); off by default. */
data class NotificationPrefs(
    val enabled: Boolean = false,
    val approvals: Boolean = true,
    val cron: Boolean = true,
    val messaging: Boolean = true,
)

/** An inline notification action (Approve/Deny) carrying the target session. */
data class NotifAction(val label: String, val action: String, val sessionId: String)

/** A platform-independent description of a notification, so mapping stays unit-testable. */
data class NotificationSpec(
    val id: Int,
    val channelId: String,
    val title: String,
    val body: String,
    val route: String?,
    val actions: List<NotifAction>,
    val groupKey: String,
)

/** Channel ids, gateway event-type strings, and action names in one place. */
object Notif {
    const val CHANNEL_APPROVALS = "approvals"
    const val CHANNEL_ACTIVITY = "activity"
    const val CHANNEL_SERVICE = "service"

    // approval.request is confirmed in ChatUiState. The other two are BEST-GUESS — verify in Task 8.
    const val EVENT_APPROVAL = "approval.request"
    const val EVENT_CRON_DONE = "cron.completed"
    const val EVENT_MSG = "message.received"

    const val ACTION_APPROVE = "approve"
    const val ACTION_DENY = "deny"
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tail -3`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hermes/client/notifications/NotificationModels.kt
git commit -m "feat(notifications): notification model types + event/channel constants"
```

---

### Task 2: Pure `ServerEvent → NotificationSpec?` mapping (TDD)

**Files:**
- Create: `app/src/main/java/com/hermes/client/notifications/NotificationMapper.kt`
- Test: `app/src/test/java/com/hermes/client/notifications/NotificationMapperTest.kt`

**Interfaces:**
- Consumes: `NotificationPrefs`, `NotificationSpec`, `NotifAction`, `Notif` (Task 1); `com.hermes.client.data.network.ServerEvent` (fields `type: String`, `sessionId: String?`, and helper `str(key): String?`).
- Produces: `fun toNotificationSpec(event: ServerEvent, prefs: NotificationPrefs): NotificationSpec?`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.hermes.client.notifications

import com.hermes.client.data.network.ServerEvent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationMapperTest {
    private val on = NotificationPrefs(enabled = true)

    private fun event(type: String, sid: String? = "s1", vararg pairs: Pair<String, String>) =
        ServerEvent(type, sid, buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } })

    @Test fun approval_makes_high_priority_spec_with_actions() {
        val spec = toNotificationSpec(event(Notif.EVENT_APPROVAL, "s1", "prompt" to "Delete file?"), on)!!
        assertEquals(Notif.CHANNEL_APPROVALS, spec.channelId)
        assertEquals("chat/s1", spec.route)
        assertTrue(spec.body.contains("Delete file?"))
        assertEquals(listOf(Notif.ACTION_APPROVE, Notif.ACTION_DENY), spec.actions.map { it.action })
        assertTrue(spec.actions.all { it.sessionId == "s1" })
    }

    @Test fun cron_and_messaging_make_specs_without_actions() {
        val cron = toNotificationSpec(event(Notif.EVENT_CRON_DONE, "c1", "name" to "Nightly", "status" to "success"), on)!!
        assertEquals(Notif.CHANNEL_ACTIVITY, cron.channelId)
        assertEquals("chat/c1", cron.route)
        assertTrue(cron.actions.isEmpty())
        val msg = toNotificationSpec(event(Notif.EVENT_MSG, "m1", "platform" to "Telegram", "preview" to "hi"), on)!!
        assertTrue(msg.title.contains("Telegram"))
    }

    @Test fun per_type_toggles_and_master_toggle_suppress() {
        assertNull(toNotificationSpec(event(Notif.EVENT_APPROVAL), on.copy(approvals = false)))
        assertNull(toNotificationSpec(event(Notif.EVENT_CRON_DONE), on.copy(cron = false)))
        assertNull(toNotificationSpec(event(Notif.EVENT_MSG), on.copy(messaging = false)))
        assertNull(toNotificationSpec(event(Notif.EVENT_APPROVAL), NotificationPrefs(enabled = false)))
    }

    @Test fun unrelated_or_sessionless_events_are_null() {
        assertNull(toNotificationSpec(event("message.delta"), on))
        assertNull(toNotificationSpec(event(Notif.EVENT_APPROVAL, sid = null), on))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*NotificationMapperTest*' --console=plain 2>&1 | tail -6`
Expected: FAIL — `toNotificationSpec` unresolved.

- [ ] **Step 3: Write the mapper**

```kotlin
package com.hermes.client.notifications

import com.hermes.client.data.network.ServerEvent

/**
 * Pure mapping from a gateway event to a notification (or null). Centralizes every event-type
 * decision so verifying/adjusting the gateway's event names is a one-line change. A stable id is
 * derived from the session so repeats of the same event update rather than stack.
 */
fun toNotificationSpec(event: ServerEvent, prefs: NotificationPrefs): NotificationSpec? {
    if (!prefs.enabled) return null
    val sid = event.sessionId ?: return null
    val id = (event.type + sid).hashCode()
    return when (event.type) {
        Notif.EVENT_APPROVAL -> if (!prefs.approvals) null else NotificationSpec(
            id = id,
            channelId = Notif.CHANNEL_APPROVALS,
            title = "Approval needed",
            body = event.str("prompt") ?: "The agent is waiting for your approval.",
            route = "chat/$sid",
            actions = listOf(
                NotifAction("Approve", Notif.ACTION_APPROVE, sid),
                NotifAction("Deny", Notif.ACTION_DENY, sid),
            ),
            groupKey = "approval",
        )
        Notif.EVENT_CRON_DONE -> if (!prefs.cron) null else NotificationSpec(
            id = id,
            channelId = Notif.CHANNEL_ACTIVITY,
            title = event.str("name") ?: "Scheduled job finished",
            body = "Run ${event.str("status") ?: "finished"}",
            route = "chat/$sid",
            actions = emptyList(),
            groupKey = "cron",
        )
        Notif.EVENT_MSG -> if (!prefs.messaging) null else NotificationSpec(
            id = id,
            channelId = Notif.CHANNEL_ACTIVITY,
            title = "Reply on ${event.str("platform") ?: "messaging"}",
            body = event.str("preview") ?: event.str("text") ?: "New reply",
            route = "chat/$sid",
            actions = emptyList(),
            groupKey = "messaging",
        )
        else -> null
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*NotificationMapperTest*' --console=plain 2>&1 | tail -6`
Expected: PASS (4 tests).

> If `event.str(...)` does not resolve, check `ServerEvent.kt` for the accessor name used by
> `ChatUiState.kt` (it calls `event.str("prompt")`) and use that exact helper.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hermes/client/notifications/NotificationMapper.kt app/src/test/java/com/hermes/client/notifications/NotificationMapperTest.kt
git commit -m "feat(notifications): pure ServerEvent -> NotificationSpec mapping with tests"
```

---

### Task 3: `NotificationSettings` DataStore + Hilt provider

**Files:**
- Create: `app/src/main/java/com/hermes/client/data/repository/NotificationSettings.kt`
- Modify: `app/src/main/java/com/hermes/client/di/AppModule.kt` (add a `@Provides @Singleton` after `provideProfileAccentStore`)

**Interfaces:**
- Consumes: `NotificationPrefs` (Task 1).
- Produces: `class NotificationSettings(context)` with `val prefs: Flow<NotificationPrefs>`, `suspend fun setEnabled(Boolean)`, `suspend fun setApprovals(Boolean)`, `suspend fun setCron(Boolean)`, `suspend fun setMessaging(Boolean)`.

- [ ] **Step 1: Create the store (mirrors `ProfileAccentStore`)**

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

/** Device-local notification preferences (master toggle + per-type flags). Off by default. */
class NotificationSettings(private val context: Context) {
    private val kEnabled = booleanPreferencesKey("enabled")
    private val kApprovals = booleanPreferencesKey("approvals")
    private val kCron = booleanPreferencesKey("cron")
    private val kMessaging = booleanPreferencesKey("messaging")

    val prefs: Flow<NotificationPrefs> = context.notificationDataStore.data.map { p ->
        NotificationPrefs(
            enabled = p[kEnabled] ?: false,
            approvals = p[kApprovals] ?: true,
            cron = p[kCron] ?: true,
            messaging = p[kMessaging] ?: true,
        )
    }

    suspend fun setEnabled(v: Boolean) = context.notificationDataStore.edit { it[kEnabled] = v }
    suspend fun setApprovals(v: Boolean) = context.notificationDataStore.edit { it[kApprovals] = v }
    suspend fun setCron(v: Boolean) = context.notificationDataStore.edit { it[kCron] = v }
    suspend fun setMessaging(v: Boolean) = context.notificationDataStore.edit { it[kMessaging] = v }
}
```

- [ ] **Step 2: Add the Hilt provider in `AppModule.kt`** (immediately after `provideProfileAccentStore`)

```kotlin
    @Provides
    @Singleton
    fun provideNotificationSettings(
        @ApplicationContext context: Context,
    ): com.hermes.client.data.repository.NotificationSettings =
        com.hermes.client.data.repository.NotificationSettings(context)
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tail -3`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/hermes/client/data/repository/NotificationSettings.kt app/src/main/java/com/hermes/client/di/AppModule.kt
git commit -m "feat(notifications): NotificationSettings DataStore + Hilt provider"
```

---

### Task 4: `HermesNotifier` — channels + posting

**Files:**
- Create: `app/src/main/java/com/hermes/client/notifications/HermesNotifier.kt`

**Interfaces:**
- Consumes: `NotificationSpec`, `Notif`, `NotifAction` (Task 1); Android `NotificationManagerCompat`.
- Produces:
  - `class HermesNotifier(context)` with:
    - `fun ensureChannels()` — creates the three channels (idempotent).
    - `fun serviceNotification(): Notification` — the persistent low-priority foreground notification.
    - `fun post(spec: NotificationSpec)` — builds + posts; tap opens `MainActivity` with `extra_route`; actions send broadcasts to `NotificationActionReceiver`.
    - `fun cancel(id: Int)`.
  - Companion: `SERVICE_NOTIFICATION_ID = 1001`.

- [ ] **Step 1: Create the notifier**

```kotlin
package com.hermes.client.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hermes.client.MainActivity
import com.hermes.client.R

/** Owns notification channels and turns a [NotificationSpec] into a posted Android notification. */
class HermesNotifier(private val context: Context) {
    private val mgr = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        val sys = context.getSystemService(NotificationManager::class.java)
        sys.createNotificationChannel(
            NotificationChannel(Notif.CHANNEL_APPROVALS, "Approvals", NotificationManager.IMPORTANCE_HIGH),
        )
        sys.createNotificationChannel(
            NotificationChannel(Notif.CHANNEL_ACTIVITY, "Agent activity", NotificationManager.IMPORTANCE_DEFAULT),
        )
        sys.createNotificationChannel(
            NotificationChannel(Notif.CHANNEL_SERVICE, "Connection", NotificationManager.IMPORTANCE_MIN),
        )
    }

    fun serviceNotification(): Notification =
        NotificationCompat.Builder(context, Notif.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_hermes)
            .setContentTitle("Hermes")
            .setContentText("Connected — watching for approvals & activity")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    fun post(spec: NotificationSpec) {
        val b = NotificationCompat.Builder(context, spec.channelId)
            .setSmallIcon(R.drawable.ic_stat_hermes)
            .setContentTitle(spec.title)
            .setContentText(spec.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(spec.body))
            .setAutoCancel(true)
            .setGroup(spec.groupKey)
            .setContentIntent(openIntent(spec.route, spec.id))
        spec.actions.forEach { a ->
            b.addAction(0, a.label, actionIntent(a, spec.id))
        }
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            mgr.notify(spec.id, b.build())
        }
    }

    fun cancel(id: Int) = mgr.cancel(id)

    private fun openIntent(route: String?, id: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            route?.let { putExtra("extra_route", it) }
        }
        return PendingIntent.getActivity(context, id, intent, pendingFlags())
    }

    private fun actionIntent(a: NotifAction, notifId: Int): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = a.action
            putExtra("session_id", a.sessionId)
            putExtra("notif_id", notifId)
        }
        return PendingIntent.getBroadcast(context, (a.action + a.sessionId).hashCode(), intent, pendingFlags())
    }

    private fun pendingFlags() = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    companion object {
        const val SERVICE_NOTIFICATION_ID = 1001
    }
}
```

- [ ] **Step 2: Add a small-icon drawable**

Create `app/src/main/res/drawable/ic_stat_hermes.xml` (a simple white vector for the status bar):

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24"
    android:tint="#FFFFFF">
    <path android:fillColor="#FFFFFF"
        android:pathData="M4,4h3v6h4V4h3v16h-3v-7H7v7H4z" />
</vector>
```

> `NotificationActionReceiver` (Task 6) is referenced here; it will exist by the time the service posts. If compiling this task alone fails on that reference, do Task 6's Step 1 (create the empty receiver class) first, then return.

- [ ] **Step 3: Verify it compiles** (create the receiver stub from Task 6 Step 1 first if needed)

Run: `./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tail -3`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/hermes/client/notifications/HermesNotifier.kt app/src/main/res/drawable/ic_stat_hermes.xml
git commit -m "feat(notifications): HermesNotifier — channels, service notification, posting"
```

---

### Task 5: `NotificationsScreen` (Settings sub-page) + nav + Settings entry

**Files:**
- Create: `app/src/main/java/com/hermes/client/ui/settings/NotificationsScreen.kt`
- Modify: `app/src/main/java/com/hermes/client/ui/settings/SettingsScreen.kt` (add an `Entry`)
- Modify: `app/src/main/java/com/hermes/client/ui/nav/HermesNav.kt` (add a `composable` route)

**Interfaces:**
- Consumes: `NotificationSettings` (Task 3); starts/stops `GatewayConnectionService` (Task 7) via its companion `start(context)`/`stop(context)`.
- Produces: `@Composable fun NotificationsScreen(onBack: () -> Unit)`.

- [ ] **Step 1: Create the screen + its ViewModel**

```kotlin
package com.hermes.client.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.repository.NotificationSettings
import com.hermes.client.notifications.GatewayConnectionService
import com.hermes.client.notifications.NotificationPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val settings: NotificationSettings,
) : ViewModel() {
    val prefs: StateFlow<NotificationPrefs> =
        settings.prefs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotificationPrefs())

    fun setEnabled(v: Boolean) = viewModelScope.launch { settings.setEnabled(v) }
    fun setApprovals(v: Boolean) = viewModelScope.launch { settings.setApprovals(v) }
    fun setCron(v: Boolean) = viewModelScope.launch { settings.setCron(v) }
    fun setMessaging(v: Boolean) = viewModelScope.launch { settings.setMessaging(v) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(onBack: () -> Unit, vm: NotificationsViewModel = hiltViewModel()) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            vm.setEnabled(true)
            GatewayConnectionService.start(context)
        }
    }

    fun enable() {
        if (Build.VERSION.SDK_INT >= 33) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.setEnabled(true); GatewayConnectionService.start(context)
        }
    }

    Scaffold(
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = "Notifications",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        androidx.compose.material3.Icon(
                            androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ToggleRow(
                "Enable notifications",
                "Keeps a background connection to your gateway while on. Off saves battery.",
                prefs.enabled,
            ) { on -> if (on) enable() else { vm.setEnabled(false); GatewayConnectionService.stop(context) } }
            HorizontalDivider()
            ToggleRow("Approval requests", "When the agent needs you to approve an action", prefs.approvals, enabled = prefs.enabled) { vm.setApprovals(it) }
            ToggleRow("Cron runs", "When a scheduled job finishes", prefs.cron, enabled = prefs.enabled) { vm.setCron(it) }
            ToggleRow("Messaging replies", "Replies on connected platforms", prefs.messaging, enabled = prefs.enabled) { vm.setMessaging(it) }
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
```

- [ ] **Step 2: Add the Settings entry** in `SettingsScreen.kt` (after the "Appearance" entry)

```kotlin
            Entry("Notifications", "Approvals, cron, and messaging alerts") { onNavigate("settings_notifications") }
            HorizontalDivider()
```

- [ ] **Step 3: Add the nav route** in `HermesNav.kt` (next to `settings_appearance`)

```kotlin
            composable("settings_notifications") {
                com.hermes.client.ui.settings.NotificationsScreen(onBack = { nav.popBackStack() })
            }
```

> Depends on `GatewayConnectionService` (Task 7). Implement Task 6 + Task 7 before compiling this task, or temporarily comment the two `GatewayConnectionService` calls, compile, then restore after Task 7.

- [ ] **Step 4: Commit** (after Task 7 exists so it compiles)

```bash
git add app/src/main/java/com/hermes/client/ui/settings/NotificationsScreen.kt app/src/main/java/com/hermes/client/ui/settings/SettingsScreen.kt app/src/main/java/com/hermes/client/ui/nav/HermesNav.kt
git commit -m "feat(notifications): Settings > Notifications screen with permission flow"
```

---

### Task 6: `NotificationActionReceiver` (Approve/Deny) + manifest permissions/receiver

**Files:**
- Create: `app/src/main/java/com/hermes/client/notifications/NotificationActionReceiver.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `ChatRepository.respondApproval(sessionId, approve)` (existing); `HermesNotifier.cancel` (Task 4); `Notif` (Task 1).
- Produces: a `@AndroidEntryPoint BroadcastReceiver` handling `Notif.ACTION_APPROVE`/`ACTION_DENY`.

- [ ] **Step 1: Create the receiver**

```kotlin
package com.hermes.client.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hermes.client.data.repository.ChatRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Handles the Approve/Deny actions on an approval notification by sending the approval RPC. */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {
    @Inject lateinit var chat: ChatRepository
    @Inject lateinit var notifier: HermesNotifier

    override fun onReceive(context: Context, intent: Intent) {
        val sid = intent.getStringExtra("session_id") ?: return
        val notifId = intent.getIntExtra("notif_id", -1)
        val approve = intent.action == Notif.ACTION_APPROVE
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching { chat.respondApproval(sid, approve) }
                if (notifId != -1) notifier.cancel(notifId)
            } finally {
                pending.finish()
            }
        }
    }
}
```

- [ ] **Step 2: Add permissions + `<service>` + `<receiver>` to `AndroidManifest.xml`**

Inside `<manifest>` (with the other `<uses-permission>` lines):

```xml
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
```

Inside `<application>`:

```xml
        <service
            android:name=".notifications.GatewayConnectionService"
            android:exported="false"
            android:foregroundServiceType="dataSync" />
        <receiver
            android:name=".notifications.NotificationActionReceiver"
            android:exported="false" />
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tail -3`
Expected: `BUILD SUCCESSFUL` (Task 7 must exist for the manifest `<service>` class to resolve at build/lint — do Task 7 first if lint complains).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/hermes/client/notifications/NotificationActionReceiver.kt app/src/main/AndroidManifest.xml
git commit -m "feat(notifications): approve/deny broadcast receiver + manifest entries"
```

---

### Task 7: `GatewayConnectionService` (foreground service)

**Files:**
- Create: `app/src/main/java/com/hermes/client/notifications/GatewayConnectionService.kt`

**Interfaces:**
- Consumes: `HermesGatewayClient` (`.connect()`, `.events: SharedFlow<ServerEvent>`), `NotificationSettings` (`.prefs`), `HermesNotifier` (`.ensureChannels`, `.serviceNotification`, `.post`), `toNotificationSpec` (Task 2), `Notif.SERVICE_*`.
- Produces: `class GatewayConnectionService : Service` with companion `fun start(Context)`, `fun stop(Context)`.

- [ ] **Step 1: Create the service**

```kotlin
package com.hermes.client.notifications

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.hermes.client.data.network.HermesGatewayClient
import com.hermes.client.data.repository.NotificationSettings
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that keeps the gateway WebSocket connected and posts notifications for
 * notifiable events, using the current [NotificationSettings]. Started/stopped by the toggle.
 */
@AndroidEntryPoint
class GatewayConnectionService : Service() {
    @Inject lateinit var client: HermesGatewayClient
    @Inject lateinit var settings: NotificationSettings
    @Inject lateinit var notifier: HermesNotifier

    private val scope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        notifier.ensureChannels()
        startForeground(HermesNotifier.SERVICE_NOTIFICATION_ID, notifier.serviceNotification())
        client.connect()
        scope.launch {
            client.events.collect { event ->
                val prefs = settings.prefs.first()
                toNotificationSpec(event, prefs)?.let { notifier.post(it) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            val i = Intent(context, GatewayConnectionService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, GatewayConnectionService::class.java))
        }
    }
}
```

- [ ] **Step 2: Provide `HermesNotifier` via Hilt** — add to `AppModule.kt` after `provideNotificationSettings`

```kotlin
    @Provides
    @Singleton
    fun provideHermesNotifier(
        @ApplicationContext context: Context,
    ): com.hermes.client.notifications.HermesNotifier =
        com.hermes.client.notifications.HermesNotifier(context)
```

- [ ] **Step 3: Auto-start the service on app launch when enabled** — in `MainActivity.onCreate` (after `setContent` block or before), start it if the pref is on:

```kotlin
        // Resume the notification service if the user previously enabled it.
        kotlinx.coroutines.MainScope().launch {
            if (notificationSettings.prefs.first().enabled) {
                com.hermes.client.notifications.GatewayConnectionService.start(this@MainActivity)
            }
        }
```

Add `@Inject lateinit var notificationSettings: com.hermes.client.data.repository.NotificationSettings` to `MainActivity` and the imports `kotlinx.coroutines.launch` / `kotlinx.coroutines.flow.first`.

- [ ] **Step 4: Verify it compiles + unit tests still pass**

Run: `./gradlew :app:testDebugUnitTest :app:compileDebugKotlin --console=plain 2>&1 | grep -E "FAILED|BUILD" | head`
Expected: `BUILD SUCCESSFUL`, no `FAILED`.

- [ ] **Step 5: Commit** (this unblocks Task 5's commit too)

```bash
git add app/src/main/java/com/hermes/client/notifications/GatewayConnectionService.kt app/src/main/java/com/hermes/client/di/AppModule.kt app/src/main/java/com/hermes/client/MainActivity.kt
git commit -m "feat(notifications): foreground service holding the gateway WebSocket"
```

---

### Task 8: Route notification taps + verify end-to-end on the emulator

**Files:**
- Modify: `app/src/main/java/com/hermes/client/MainActivity.kt` (read `extra_route` and navigate)
- Modify (scratch, not committed to app): `$CLAUDE_JOB_DIR/tmp/mockgw.py`

**Interfaces:**
- Consumes: `HermesNav` navigation; the `extra_route` extra set by `HermesNotifier.openIntent`.

- [ ] **Step 1: Handle the tap route in `MainActivity`** — pass an initial route into `HermesNav` from the launching intent (`intent.getStringExtra("extra_route")`) and `override fun onNewIntent`. Minimal approach: hold the route in a `mutableStateOf`, set it from `intent`/`onNewIntent`, and `LaunchedEffect(route)` calls `nav.navigate(route)`. Wire it where `HermesNav(hasConfig = ...)` is invoked by adding an optional `deepLinkRoute: String?` param to `HermesNav` and, inside, `LaunchedEffect(deepLinkRoute) { deepLinkRoute?.let { nav.navigate(it) } }`.

- [ ] **Step 2: Extend the mock gateway WS to emit test events** — in `mockgw.py`'s `_ws`, after sending `gateway.ready`, sleep briefly then send one of each (unmasked text frames), so the running app posts notifications:

```python
        import threading
        def emit_later():
            time.sleep(6)
            for ev in [
                {"method":"event","params":{"type":"approval.request","session_id":"s3","prompt":"Delete /tmp/cache?"}},
                {"method":"event","params":{"type":"cron.completed","session_id":"s4","name":"Nightly summary","status":"success"}},
                {"method":"event","params":{"type":"message.received","session_id":"s5","platform":"Telegram","preview":"On it — deploying now"}},
            ]:
                b = json.dumps(ev).encode()
                try:
                    self.wfile.write(bytes([0x81, len(b)]) + b); self.wfile.flush()
                except Exception:
                    return
                time.sleep(2)
        threading.Thread(target=emit_later, daemon=True).start()
```

- [ ] **Step 3: Run end-to-end on the emulator**

```bash
# start mock + emulator (see the screenshot session), install beta, enable notifications in
# Settings > Notifications, grant POST_NOTIFICATIONS, then confirm the shade shows:
#  - a high-priority "Approval needed — Delete /tmp/cache?" with Approve/Deny
#  - "Nightly summary — Run success"
#  - "Reply on Telegram: On it — deploying now"
# Tap each -> opens the right chat. Tap Approve -> mock receives POST-less WS call (log it).
```

Expected: three notifications appear; tapping opens the session; Approve/Deny dismiss the approval.

- [ ] **Step 4: VERIFY the real event names** — with the user's OK, connect to the real gateway and watch `DebugLog` "ws" entries (or the Diagnostics screen) while a cron job finishes and a messaging reply arrives. If the real types differ from `cron.completed` / `message.received`, update the two constants in `NotificationModels.kt` (Task 1) and re-run. If no distinct cron-finished event exists, note it and leave cron notifications behind a follow-up.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hermes/client/MainActivity.kt app/src/main/java/com/hermes/client/ui/nav/HermesNav.kt
git commit -m "feat(notifications): open the tapped session via notification deep-link"
```

---

## Self-Review

- **Spec coverage:** foreground-service WS (Task 7) ✓; pure mapping (Task 2) ✓; approval inline actions (Tasks 4/6) ✓; cron + messaging (Task 2 + verify Task 8) ✓; opt-in off-by-default + per-type toggles + permission (Tasks 3/5) ✓; active-profile scope (single client, inherent) ✓; manifest (Task 6) ✓; unit-test mapping (Task 2) ✓; mock e2e (Task 8) ✓; not-doing items untouched ✓.
- **Placeholder scan:** every code step has full code; the two unverified event constants are explicit named constants with a verification task, not placeholders.
- **Type consistency:** `toNotificationSpec(event, prefs)`, `NotificationSpec` fields, `Notif.*`, `HermesNotifier.post/cancel/ensureChannels/serviceNotification`, `GatewayConnectionService.start/stop`, `respondApproval(sessionId, approve)` are used consistently across tasks.

**Known ordering note:** Tasks 4, 5, 6, 7 are mutually referential (notifier ↔ receiver ↔ service ↔ screen). Implement classes 6→7→4→5 for clean compiles, or create empty stubs first; each task's commit lands once the group compiles. This is called out in each task's steps.
