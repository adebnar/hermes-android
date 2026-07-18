# `hermes://` Deep Links Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Checkbox (`- [ ]`) steps.

**Goal:** Route `hermes://tab/{sessions|activity|you}` and `hermes://chat/<id>` links into the app, reusing the existing deep-link rail; harden the navigate against bad routes.

**Spec:** `docs/superpowers/specs/2026-07-17-hermes-deep-links-design.md`

## Global Constraints
- Client-only; strict allowlist (unknown links ignored); no crash on a bad route.
- Build env: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
- Branch `feature/hermes-deep-links` (off `dev`). No AI attribution.

---

### Task 1: Pure `deepLinkRouteFor` mapper

**Files:** Create `app/src/main/java/com/hermes/client/ui/nav/DeepLinkMapper.kt`; Test `app/src/test/java/com/hermes/client/ui/nav/DeepLinkMapperTest.kt`

- [ ] **Step 1: Write the failing test**

`DeepLinkMapperTest.kt`:
```kotlin
package com.hermes.client.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepLinkMapperTest {
    @Test fun tab_routes_map() {
        assertEquals("sessions", deepLinkRouteFor("hermes://tab/sessions"))
        assertEquals("activity", deepLinkRouteFor("hermes://tab/activity"))
        assertEquals("you", deepLinkRouteFor("hermes://tab/you"))
    }

    @Test fun chat_id_maps() {
        assertEquals("chat/abc-123", deepLinkRouteFor("hermes://chat/abc-123"))
    }

    @Test fun scheme_is_case_insensitive() {
        assertEquals("sessions", deepLinkRouteFor("HERMES://tab/sessions"))
    }

    @Test fun unknown_or_malformed_is_null() {
        assertNull(deepLinkRouteFor("hermes://tab/nope"))
        assertNull(deepLinkRouteFor("hermes://chat"))       // no id
        assertNull(deepLinkRouteFor("hermes://chat/a/b"))   // two segments
        assertNull(deepLinkRouteFor("hermes://bogus/x"))    // unknown host
        assertNull(deepLinkRouteFor("http://tab/sessions")) // wrong scheme
        assertNull(deepLinkRouteFor(""))
        assertNull(deepLinkRouteFor("not a uri at all ::: %%%"))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.hermes.client.ui.nav.DeepLinkMapperTest"` → FAIL (unresolved).

- [ ] **Step 3: Implement**

`DeepLinkMapper.kt`:
```kotlin
package com.hermes.client.ui.nav

private val TAB_ROUTES = setOf("sessions", "activity", "you")

/**
 * Map a `hermes://` URI string to an internal nav route, or null if it isn't a recognised link.
 * Parsed with [java.net.URI] (pure JVM, unit-testable). Strict allowlist — a `hermes://` link is
 * untrusted external input (BROWSABLE), so anything unknown returns null and is ignored.
 */
fun deepLinkRouteFor(raw: String): String? {
    if (raw.isBlank()) return null
    val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return null
    if (!"hermes".equals(uri.scheme, ignoreCase = true)) return null
    val host = uri.host ?: return null
    val segs = uri.path.orEmpty().split('/').filter { it.isNotBlank() }
    return when (host) {
        "tab" -> segs.singleOrNull()?.takeIf { it in TAB_ROUTES }
        "chat" -> segs.singleOrNull()?.takeIf { it.isNotBlank() && '/' !in it }?.let { "chat/$it" }
        else -> null
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.hermes.client.ui.nav.DeepLinkMapperTest"` → PASS (4 tests). If `java.net.URI("not a uri at all ::: %%%")` throws instead of the `runCatching` catching it, confirm the `runCatching` wraps the constructor (it does) — the test expects null.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/nav/DeepLinkMapper.kt \
        app/src/test/java/com/hermes/client/ui/nav/DeepLinkMapperTest.kt
git commit -m "feat: add hermes:// deep-link route mapper"
```

---

### Task 2: Intent-filter + MainActivity + navigate hardening

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/hermes/client/MainActivity.kt`
- Modify: `app/src/main/java/com/hermes/client/ui/nav/HermesNav.kt`
- Test: none new (Android glue). Compile + full suite + assembleBeta + Task 3.

**Interfaces:** Consumes `deepLinkRouteFor` (Task 1).

- [ ] **Step 1: Add the intent-filter**

In `AndroidManifest.xml`, inside the existing `<activity android:name=".MainActivity" …>` block (after the existing intent-filters), add:
```xml
        <intent-filter android:autoVerify="false">
            <action android:name="android.intent.action.VIEW" />
            <category android:name="android.intent.category.DEFAULT" />
            <category android:name="android.intent.category.BROWSABLE" />
            <data android:scheme="hermes" />
        </intent-filter>
```

- [ ] **Step 2: Read `intent.data` in MainActivity**

In `MainActivity.kt`, add the import:
```kotlin
import com.hermes.client.ui.nav.deepLinkRouteFor
```
In `onCreate`, replace:
```kotlin
        pendingRoute.value = intent?.getStringExtra("extra_route")
        intent?.removeExtra("extra_route")
```
with:
```kotlin
        pendingRoute.value = intent?.getStringExtra("extra_route")
            ?: intent?.data?.let { deepLinkRouteFor(it.toString()) }
        intent?.removeExtra("extra_route")
        intent?.data = null
```
In `onNewIntent`, replace:
```kotlin
        pendingRoute.value = intent.getStringExtra("extra_route")
        intent.removeExtra("extra_route")
```
with:
```kotlin
        pendingRoute.value = intent.getStringExtra("extra_route")
            ?: intent.data?.let { deepLinkRouteFor(it.toString()) }
        intent.removeExtra("extra_route")
        intent.data = null
```

- [ ] **Step 3: Harden the navigate in HermesNav**

In `HermesNav.kt`, replace:
```kotlin
    LaunchedEffect(deepLinkRoute) { deepLinkRoute?.let { nav.navigate(it); onDeepLinkConsumed() } }
```
with:
```kotlin
    // Guard the navigate: a hermes:// deep link is untrusted, and even the notification path could
    // carry a stale/unknown route — an unresolved route must be ignored, never crash.
    LaunchedEffect(deepLinkRoute) {
        deepLinkRoute?.let {
            runCatching { nav.navigate(it) }
            onDeepLinkConsumed()
        }
    }
```

- [ ] **Step 4: Compile + full suite + assembleBeta**

Run each (JAVA_HOME set): `:app:compileDebugKotlin`, `:app:testDebugUnitTest` (0 failures), `:app:assembleBeta` — all BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/AndroidManifest.xml \
        app/src/main/java/com/hermes/client/MainActivity.kt \
        app/src/main/java/com/hermes/client/ui/nav/HermesNav.kt
git commit -m "feat: accept hermes:// VIEW intents and guard deep-link navigation"
```

---

### Task 3: On-device verification (adb-driven, fully exercisable)

**Files:** none.

- [ ] **Step 1:** `:app:installBeta` (target the emulator explicitly if multiple devices: `adb -s emulator-5554 …`). The app must be configured (past setup) for tab navigation to land meaningfully; if on the setup screen, note it.
- [ ] **Step 2:** `adb shell am start -a android.intent.action.VIEW -d "hermes://tab/sessions" com.hermes.client.beta` → app foregrounds on the **Chats** tab. Repeat `hermes://tab/activity` (Home) and `hermes://tab/you` (You).
- [ ] **Step 3:** `adb shell am start -a android.intent.action.VIEW -d "hermes://chat/<real-id>" com.hermes.client.beta` → opens that chat (if the active profile owns it). Confirm no crash if the id isn't found (lands/stays gracefully).
- [ ] **Step 4:** `adb shell am start -a android.intent.action.VIEW -d "hermes://bogus/x" com.hermes.client.beta` → app opens normally, link ignored, **no crash** (verify process alive: `adb shell pidof com.hermes.client.beta`).
- [ ] **Step 5:** Record pass/fail in the PR description (no commit).

---

## Notes for the executor
- Keep the existing `extra_route` (notification) path working — the deep-link read is a **fallback** (`?:`) only when `extra_route` is absent.
- Do NOT add `?profile=` handling, cron links, `https://` App Links, or link *generation* — all explicit anti-scope for this wave.
