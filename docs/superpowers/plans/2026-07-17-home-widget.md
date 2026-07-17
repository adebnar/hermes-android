# Home-Screen Quick-Launch Widget Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Checkbox steps.

**Goal:** A Glance widget with New chat / Chats / Home buttons; New chat via a new `hermes://new` verb reusing the share rail.

**Spec:** `docs/superpowers/specs/2026-07-17-home-widget-design.md`

## Global Constraints
- Client-only; static widget theme (no per-tenant accent in Glance); no AI attribution.
- Build env: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
- Branch `feature/home-widget` (off `dev`).

---

### Task 1: `isNewChatLink` predicate

**Files:** Modify `app/src/main/java/com/hermes/client/ui/nav/DeepLinkMapper.kt`; Test `app/src/test/java/com/hermes/client/ui/nav/DeepLinkMapperTest.kt` (extend)

- [ ] **Step 1: Add failing tests** to `DeepLinkMapperTest.kt`:
```kotlin
    @Test fun new_chat_link_recognised() {
        assertTrue(isNewChatLink("hermes://new"))
        assertTrue(isNewChatLink("HERMES://new"))
    }

    @Test fun non_new_links_are_false() {
        assertFalse(isNewChatLink("hermes://new/x"))
        assertFalse(isNewChatLink("hermes://tab/sessions"))
        assertFalse(isNewChatLink("http://new"))
        assertFalse(isNewChatLink(""))
        assertFalse(isNewChatLink("garbage ::: %%"))
    }
```
(Add `import org.junit.Assert.assertTrue` / `assertFalse` if not already present.)

- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest --tests "com.hermes.client.ui.nav.DeepLinkMapperTest"` → FAIL (unresolved `isNewChatLink`).

- [ ] **Step 3: Implement** — append to `DeepLinkMapper.kt`:
```kotlin
/** True for a `hermes://new` link (widget "New chat"). Not a nav route — the caller runs createSession. */
fun isNewChatLink(raw: String): Boolean {
    val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return false
    return "hermes".equals(uri.scheme, ignoreCase = true) && uri.host == "new"
}
```

- [ ] **Step 4:** Run the test → PASS.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/hermes/client/ui/nav/DeepLinkMapper.kt \
        app/src/test/java/com/hermes/client/ui/nav/DeepLinkMapperTest.kt
git commit -m "feat: recognise hermes://new deep link"
```

---

### Task 2: `openNewChat` + `hermes://new` handling in MainActivity

**Files:** Modify `app/src/main/java/com/hermes/client/MainActivity.kt`

**Interfaces:** Consumes `isNewChatLink` (Task 1); reuses existing `chat`, `profileManager`, `credentialStore`, `pendingRoute`, `lifecycleScope`.

- [ ] **Step 1: Add the import**
```kotlin
import com.hermes.client.ui.nav.isNewChatLink
```
(`deepLinkRouteFor` is already imported from the deep-links wave.)

- [ ] **Step 2: Add `openNewChat()`** as a private method (near `handleShare`):
```kotlin
    /** Create a fresh chat and navigate to it (widget "New chat" / hermes://new). No-op if unconfigured. */
    private fun openNewChat() {
        if (credentialStore.load() == null) return
        lifecycleScope.launch {
            chat.connect() // idempotent; a cold start has no socket yet
            runCatching {
                profileManager.refresh() // load active profile so the session isn't orphaned to default
                chat.createSession(profileManager.active.value)
            }.onSuccess { id -> pendingRoute.value = "chat/$id" }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    android.widget.Toast.makeText(
                        this@MainActivity, "Couldn't start a chat", android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
        }
    }
```

- [ ] **Step 3: Branch on `hermes://new` in onCreate.** Replace the existing deep-link read block in `onCreate`:
```kotlin
        pendingRoute.value = intent?.getStringExtra("extra_route")
            ?: intent?.data?.let { deepLinkRouteFor(it.toString()) }
        intent?.removeExtra("extra_route")
        intent?.data = null
```
with:
```kotlin
        val dlData = intent?.data
        if (dlData != null && isNewChatLink(dlData.toString())) {
            openNewChat()
            intent?.data = null
        } else {
            pendingRoute.value = intent?.getStringExtra("extra_route")
                ?: dlData?.let { deepLinkRouteFor(it.toString()) }
            intent?.removeExtra("extra_route")
            intent?.data = null
        }
```

- [ ] **Step 4: Same branch in onNewIntent.** Replace the equivalent block:
```kotlin
        pendingRoute.value = intent.getStringExtra("extra_route")
            ?: intent.data?.let { deepLinkRouteFor(it.toString()) }
        intent.removeExtra("extra_route")
        intent.data = null
```
with:
```kotlin
        val dlData = intent.data
        if (dlData != null && isNewChatLink(dlData.toString())) {
            openNewChat()
            intent.data = null
        } else {
            pendingRoute.value = intent.getStringExtra("extra_route")
                ?: dlData?.let { deepLinkRouteFor(it.toString()) }
            intent.removeExtra("extra_route")
            intent.data = null
        }
```

- [ ] **Step 5: Compile** — `JAVA_HOME=… ./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**
```bash
git add app/src/main/java/com/hermes/client/MainActivity.kt
git commit -m "feat: handle hermes://new to start a new chat"
```

---

### Task 3: Glance widget + dependency + manifest

**Files:**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`
- New: `app/src/main/java/com/hermes/client/widget/HermesWidget.kt`, `HermesWidgetReceiver.kt`
- New: `app/src/main/res/xml/hermes_widget_info.xml`
- Modify: `app/src/main/res/values/strings.xml` (create if absent), `app/src/main/AndroidManifest.xml`
- Test: none new (Android glue). Compile + full suite + assembleBeta + Task 4.

- [ ] **Step 1: Add the Glance dependency**

`gradle/libs.versions.toml`: under `[versions]` add `glance = "1.1.1"`; under `[libraries]` add:
```toml
glance-appwidget = { module = "androidx.glance:glance-appwidget", version.ref = "glance" }
```
`app/build.gradle.kts` (in `dependencies {}`): `implementation(libs.glance.appwidget)`.

- [ ] **Step 2: Create the widget**

`app/src/main/java/com/hermes/client/widget/HermesWidget.kt`:
```kotlin
package com.hermes.client.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.action.clickable
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

class HermesWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { Content(context) }
    }

    @Composable
    private fun Content(context: Context) {
        Column(
            modifier = GlanceModifier.fillMaxSize()
                .background(ColorProvider(Color(0xFF3B3BAF)))
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Item(context, "New chat", "hermes://new")
            Item(context, "Chats", "hermes://tab/sessions")
            Item(context, "Home", "hermes://tab/activity")
        }
    }

    @Composable
    private fun Item(context: Context, label: String, uri: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(context.packageName)
        Text(
            text = label,
            modifier = GlanceModifier.fillMaxWidth()
                .padding(vertical = 6.dp)
                .clickable(actionStartActivity(intent)),
            style = TextStyle(color = ColorProvider(Color.White), fontSize = 16.sp, textAlign = TextAlign.Center),
        )
    }
}

class HermesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HermesWidget()
}
```
NOTE: the exact Glance imports/API for the resolved `glance-appwidget` version take precedence — if `padding`, `clickable`, `background`, `ColorProvider`, or `actionStartActivity` signatures differ, adjust imports/calls so it compiles. `GlanceModifier.clickable` is `androidx.glance.action.clickable`; `padding(vertical = 6.dp)` uses `androidx.compose.ui.unit.dp`. Keep the three buttons + their `hermes://` URIs and the explicit `setPackage` — that is the contract. (Put `HermesWidgetReceiver` in its own file `HermesWidgetReceiver.kt` if the reviewer prefers; either is fine.)

- [ ] **Step 3: Provider XML**

`app/src/main/res/xml/hermes_widget_info.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:minWidth="180dp"
    android:minHeight="110dp"
    android:targetCellWidth="3"
    android:targetCellHeight="2"
    android:resizeMode="horizontal|vertical"
    android:widgetCategory="home_screen"
    android:description="@string/widget_description"
    android:initialLayout="@layout/glance_default_loading_layout" />
```

- [ ] **Step 4: String resource**

Ensure `app/src/main/res/values/strings.xml` exists; add:
```xml
    <string name="widget_description">Quick actions for Hermes</string>
```
(If `strings.xml` doesn't exist, create it with a `<resources>` root containing this string.)

- [ ] **Step 5: Manifest receiver**

In `AndroidManifest.xml`, inside `<application>` (near the existing `NotificationActionReceiver`), add:
```xml
        <receiver
            android:name=".widget.HermesWidgetReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data
                android:name="android.appwidget.provider"
                android:resource="@xml/hermes_widget_info" />
        </receiver>
```

- [ ] **Step 6: Compile + full suite + assembleBeta**

Run each (JAVA_HOME set): `:app:compileDebugKotlin`, `:app:testDebugUnitTest` (0 failures), `:app:assembleBeta` — all BUILD SUCCESSFUL. **If the Glance dependency version fails to resolve or an API signature differs, adjust the version and imports minimally until it builds; the widget's three buttons + hermes:// targets are the contract.**

- [ ] **Step 7: Commit**
```bash
git add gradle/libs.versions.toml app/build.gradle.kts \
        app/src/main/java/com/hermes/client/widget/ \
        app/src/main/res/xml/hermes_widget_info.xml \
        app/src/main/res/values/strings.xml \
        app/src/main/AndroidManifest.xml
git commit -m "feat: add home-screen quick-launch widget (Glance)"
```

---

### Task 4: On-device verification (best-effort)

- [ ] **Step 1:** `:app:installBeta` (target `emulator-5554` if multiple devices).
- [ ] **Step 2:** `adb -s emulator-5554 shell am start -a android.intent.action.VIEW -d "hermes://new" com.hermes.client.beta` → if configured, a new chat opens; if not configured, no crash (verify `pidof`).
- [ ] **Step 3:** `adb -s emulator-5554 shell dumpsys appwidget | grep -i hermes` → the `HermesWidgetReceiver` provider is registered.
- [ ] **Step 4:** (Best-effort) confirm the widget appears in the launcher's widget picker; placing + tapping on the emulator is optional — the `hermes://` launch targets are already verified from the deep-links wave.
- [ ] **Step 5:** Record pass/fail in the PR description.

---

## Notes for the executor
- Do NOT add a "needs you" dynamic glance, per-tenant accent, or a "scan" button — explicit anti-scope.
- The widget's launch intents are explicit (`setPackage(self)`), so they can only open this app.
- If Glance `1.1.1` doesn't resolve, use the newest `androidx.glance:glance-appwidget` 1.x that builds against AGP 9.1 / Compose BOM 2025.12.
