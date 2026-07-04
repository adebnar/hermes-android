# Share-to-Hermes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user share a link/text from any Android app into Hermes; it opens a new chat in the active profile with the shared content pre-filled in the composer, ready to review and send.

**Architecture:** A pure `sharedText()` extractor + a one-shot `PendingShareStore` handoff, wired into `MainActivity`'s existing intent/deep-link rail (`pendingRoute` → `HermesNav.deepLinkRoute`) and the chat composer. No gateway/bridge changes — reuses `ChatRepository.connect()`/`createSession()`.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, JUnit.

## Global Constraints

- **No bridge/gateway API changes.** Reuse `ChatRepository.connect()` (idempotent), `ChatRepository.createSession()` (`session.create`), the existing `pendingRoute`→`HermesNav.deepLinkRoute` deep-link, and `ChatScreen`'s local `draft` composer state.
- JDK 21: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`. compileSdk/targetSdk 36, minSdk 26.
- Compile: `./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tail -5`. Unit tests: `./gradlew :app:testDebugUnitTest --console=plain`. Beta APK: `./gradlew :app:assembleBeta`.
- Follow patterns: pure unit tests like `NotificationMapperTest`/`ModelSelectorTest`; Hilt constructor injection (`@Inject constructor`); MVVM + StateFlow.
- **No AI/assistant attribution** in commits, files, or PRs.
- v1 scope: **text/plain only**, new session in the **active profile**, **not auto-sent** (composer pre-filled). Images, profile/session chooser, `ACTION_SEND_MULTIPLE`, auto-send are out.

## File Structure

- Create `app/src/main/java/com/hermes/client/share/ShareIntent.kt` — pure `sharedText(...)`.
- Create `app/src/main/java/com/hermes/client/share/PendingShareStore.kt` — `@Singleton` one-shot handoff.
- Create `app/src/test/java/com/hermes/client/share/ShareIntentTest.kt`, `PendingShareStoreTest.kt`.
- Modify `app/src/main/java/com/hermes/client/ui/chat/ChatViewModel.kt` — `initialDraft` + consume the store in `open()`.
- Modify `app/src/main/java/com/hermes/client/ui/chat/ChatScreen.kt` — pre-fill `draft`.
- Modify `app/src/main/java/com/hermes/client/MainActivity.kt` — `handleShare(intent)`.
- Modify `app/src/main/AndroidManifest.xml` — `ACTION_SEND` `text/plain` intent-filter.

---

### Task 1: Pure `sharedText()` extractor (TDD)

**Files:**
- Create: `app/src/main/java/com/hermes/client/share/ShareIntent.kt`
- Test: `app/src/test/java/com/hermes/client/share/ShareIntentTest.kt`

**Interfaces:**
- Produces: `fun sharedText(action: String?, type: String?, subject: String?, text: String?): String?`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.hermes.client.share

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareIntentTest {
    private val send = Intent.ACTION_SEND

    @Test fun text_only_returns_trimmed_text() {
        assertEquals("https://x.com", sharedText(send, "text/plain", null, "  https://x.com  "))
    }
    @Test fun subject_and_text_combined_when_different() {
        assertEquals("My Page\nhttps://x.com", sharedText(send, "text/plain", "My Page", "https://x.com"))
    }
    @Test fun subject_equal_to_text_not_duplicated() {
        assertEquals("https://x.com", sharedText(send, "text/plain", "https://x.com", "https://x.com"))
    }
    @Test fun only_subject_returns_subject() {
        assertEquals("A note", sharedText(send, "text/plain", "A note", null))
    }
    @Test fun non_send_action_is_null() {
        assertNull(sharedText(Intent.ACTION_VIEW, "text/plain", null, "hi"))
    }
    @Test fun non_text_type_is_null() {
        assertNull(sharedText(send, "image/png", null, "hi"))
    }
    @Test fun blank_or_absent_returns_null() {
        assertNull(sharedText(send, "text/plain", "  ", ""))
        assertNull(sharedText(send, "text/plain", null, null))
        assertNull(sharedText(send, null, "s", "t"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*ShareIntentTest*' --console=plain 2>&1 | tail -6`
Expected: FAIL — unresolved `sharedText`.

- [ ] **Step 3: Write the pure function**

```kotlin
package com.hermes.client.share

import android.content.Intent

/**
 * Pure: the shareable text from an ACTION_SEND share, or null if this isn't a usable text share.
 * A shared link commonly puts the page title in EXTRA_SUBJECT and the URL in EXTRA_TEXT, so the two
 * are combined ("subject\ntext") when they differ. Takes primitives (not an Intent) so it stays
 * pure and JVM-unit-testable.
 */
fun sharedText(action: String?, type: String?, subject: String?, text: String?): String? {
    if (action != Intent.ACTION_SEND) return null
    if (type == null || !type.startsWith("text/")) return null
    val s = subject?.trim().orEmpty()
    val t = text?.trim().orEmpty()
    return when {
        s.isNotEmpty() && t.isNotEmpty() && s != t -> "$s\n$t"
        t.isNotEmpty() -> t
        s.isNotEmpty() -> s
        else -> null
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*ShareIntentTest*' --console=plain 2>&1 | tail -6`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hermes/client/share/ShareIntent.kt app/src/test/java/com/hermes/client/share/ShareIntentTest.kt
git commit -m "feat(share): pure sharedText() extractor with tests"
```

---

### Task 2: `PendingShareStore` (TDD)

**Files:**
- Create: `app/src/main/java/com/hermes/client/share/PendingShareStore.kt`
- Test: `app/src/test/java/com/hermes/client/share/PendingShareStoreTest.kt`

**Interfaces:**
- Produces: `@Singleton class PendingShareStore @Inject constructor()` with `fun put(sessionId: String, text: String)` and `fun take(sessionId: String): String?`.
- Note: constructor-injected `@Singleton` — Hilt provides it automatically, **no `AppModule` change needed**.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.hermes.client.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingShareStoreTest {
    @Test fun put_then_take_same_id_returns_text_once() {
        val store = PendingShareStore()
        store.put("s1", "hello")
        assertEquals("hello", store.take("s1"))
        assertNull(store.take("s1"))   // consumed — one-shot
    }
    @Test fun take_other_id_returns_null_and_keeps_value() {
        val store = PendingShareStore()
        store.put("s1", "hello")
        assertNull(store.take("s2"))            // not the target session
        assertEquals("hello", store.take("s1")) // still available for the right session
    }
    @Test fun take_when_empty_is_null() {
        assertNull(PendingShareStore().take("s1"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*PendingShareStoreTest*' --console=plain 2>&1 | tail -6`
Expected: FAIL — unresolved `PendingShareStore`.

- [ ] **Step 3: Write the store**

```kotlin
package com.hermes.client.share

import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot, in-process handoff of shared text from the share entry point to the chat that opens for
 * it. Not persisted — it only needs to survive a single navigation. take() is keyed by sessionId so
 * a normal chat open never consumes a share meant for a different (freshly-created) session.
 */
@Singleton
class PendingShareStore @Inject constructor() {
    private var pending: Pair<String, String>? = null

    @Synchronized
    fun put(sessionId: String, text: String) {
        pending = sessionId to text
    }

    @Synchronized
    fun take(sessionId: String): String? {
        val p = pending ?: return null
        if (p.first != sessionId) return null
        pending = null
        return p.second
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*PendingShareStoreTest*' --console=plain 2>&1 | tail -6`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hermes/client/share/PendingShareStore.kt app/src/test/java/com/hermes/client/share/PendingShareStoreTest.kt
git commit -m "feat(share): PendingShareStore one-shot handoff with tests"
```

---

### Task 3: Composer pre-fill (`ChatViewModel` + `ChatScreen`)

**Files:**
- Modify: `app/src/main/java/com/hermes/client/ui/chat/ChatViewModel.kt`
- Modify: `app/src/main/java/com/hermes/client/ui/chat/ChatScreen.kt`
- Modify (if present): `app/src/test/java/com/hermes/client/ui/chat/ChatViewModelTest.kt` (constructor gains a param)

**Interfaces:**
- Consumes: `PendingShareStore` (Task 2).
- Produces (on `ChatViewModel`): `val initialDraft: StateFlow<String?>`, `fun clearInitialDraft()`.

- [ ] **Step 1: Add `initialDraft` to `ChatViewModel`**

Add the store to the constructor (it currently ends `..., private val favoritesStore: ModelFavoritesStore,`):

```kotlin
    private val favoritesStore: com.hermes.client.data.repository.ModelFavoritesStore,
    private val pendingShareStore: com.hermes.client.share.PendingShareStore,
) : ViewModel() {
```

Add the state (near `_currentModel`/`_providers`):

```kotlin
    // Text handed off from a share (Share-to-Hermes). ChatScreen pre-fills the composer with it once.
    private val _initialDraft = MutableStateFlow<String?>(null)
    val initialDraft: StateFlow<String?> = _initialDraft.asStateFlow()
    fun clearInitialDraft() { _initialDraft.value = null }
```

In `open(id)`, consume the store synchronously near the top (right after `connJob?.cancel()`, before the `viewModelScope.launch { ... }`):

```kotlin
        // A share created this session and stashed its text; surface it as the initial composer draft.
        pendingShareStore.take(id)?.let { _initialDraft.value = it }
```

- [ ] **Step 2: Pre-fill the composer in `ChatScreen`**

Where `draft` is declared (`var draft by remember { mutableStateOf("") }`), add the collection + one-time fill right after it:

```kotlin
    var draft by remember { mutableStateOf("") }
    val initialDraft by vm.initialDraft.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(initialDraft) {
        initialDraft?.takeIf { it.isNotEmpty() }?.let { draft = it; vm.clearInitialDraft() }
    }
```

(`collectAsStateWithLifecycle` is already imported in `ChatScreen.kt`.)

- [ ] **Step 3: Fix `ChatViewModelTest` construction (if it exists)**

If `ChatViewModelTest.kt` constructs `ChatViewModel(...)` directly, add a real `PendingShareStore()` argument (it's a plain no-arg class — use the real one, not a mock) in the matching constructor position. Do not weaken existing assertions.

- [ ] **Step 4: Compile + full unit suite**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --console=plain 2>&1 | grep -E "FAILED|BUILD" | head`
Expected: `BUILD SUCCESSFUL`, no `FAILED`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/chat/ChatViewModel.kt app/src/main/java/com/hermes/client/ui/chat/ChatScreen.kt app/src/test/java/com/hermes/client/ui/chat/ChatViewModelTest.kt
git commit -m "feat(share): pre-fill chat composer from a pending share"
```

---

### Task 4: Share entry point (`MainActivity` + manifest)

**Files:**
- Modify: `app/src/main/java/com/hermes/client/MainActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `sharedText(...)` (Task 1), `PendingShareStore` (Task 2), `ChatRepository.connect()`/`createSession()`, the existing `pendingRoute`.

- [ ] **Step 1: Inject the deps into `MainActivity`**

Add after the existing `@Inject` fields (below `notificationSettings`):

```kotlin
    @Inject lateinit var chat: com.hermes.client.data.repository.ChatRepository
    @Inject lateinit var pendingShare: com.hermes.client.share.PendingShareStore
```

- [ ] **Step 2: Add `handleShare` and call it from create + new-intent**

Add the method (next to `shareCrash`):

```kotlin
    /**
     * Handle an incoming ACTION_SEND text share: open a new chat with the shared text pre-filled.
     * Reuses the notification deep-link rail (pendingRoute -> HermesNav.deepLinkRoute).
     */
    private fun handleShare(intent: Intent?) {
        val text = com.hermes.client.share.sharedText(
            intent?.action,
            intent?.type,
            intent?.getStringExtra(Intent.EXTRA_SUBJECT),
            intent?.getStringExtra(Intent.EXTRA_TEXT),
        ) ?: return
        // Not configured yet -> the app opens to setup; drop the share in v1.
        if (credentialStore.load() == null) return
        lifecycleScope.launch {
            chat.connect()  // idempotent; a cold-start share has no open socket yet, and
                            // createSession() would otherwise block on the ready-gate forever.
            runCatching { chat.createSession() }
                .onSuccess { id ->
                    pendingShare.put(id, text)
                    pendingRoute.value = "chat/$id"
                }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    android.widget.Toast.makeText(
                        this@MainActivity, "Couldn't start a chat", android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
        }
    }
```

Call it in `onCreate` right after the `extra_route` read (after `intent?.removeExtra("extra_route")`):

```kotlin
        handleShare(intent)
```

And in `onNewIntent`, after the `extra_route` handling (after `intent.removeExtra("extra_route")`):

```kotlin
        handleShare(intent)
```

Add the import (near the other imports):

```kotlin
import androidx.lifecycle.lifecycleScope
```

- [ ] **Step 3: Add the intent-filter to `AndroidManifest.xml`**

Inside `MainActivity`'s `<activity>` element (the launcher activity — it already has the `MAIN`/`LAUNCHER` intent-filter; add a second `<intent-filter>` alongside it):

```xml
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="text/plain" />
            </intent-filter>
```

- [ ] **Step 4: Compile + full unit suite + assemble**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --console=plain 2>&1 | grep -E "FAILED|BUILD" | head`
Expected: `BUILD SUCCESSFUL`, no `FAILED`.
Run: `./gradlew :app:assembleBeta --console=plain 2>&1 | grep -E "^e: |BUILD" | tail -2`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hermes/client/MainActivity.kt app/src/main/AndroidManifest.xml
git commit -m "feat(share): ACTION_SEND text share opens a new pre-filled chat"
```

---

### Task 5: On-device verification

**Files:** none (verification only).

- [ ] **Step 1: Build + install the beta**

Run: `./gradlew :app:assembleBeta --console=plain 2>&1 | grep -E "^e: |BUILD" | tail -1` then `adb -e install -r app/build/outputs/apk/beta/app-beta.apk` (or `-d` for a device). Point it at a working gateway (real, or the mock at `http://10.0.2.2:8899`).

- [ ] **Step 2: Verify the share flow**

- From Chrome (or any app), use the system **Share** → confirm **Hermes Beta** appears in the sheet for a link/text.
- Tap it → a **new Hermes chat opens** in the active profile with the shared URL/text **in the composer** (not sent).
- Add a note and send → the message goes through normally.
- Repeat while the app is already open (backgrounded) → `onNewIntent` still routes to a fresh pre-filled chat.
- Share when the app is not yet configured → it opens to setup without crashing (share dropped).

- [ ] **Step 3: Commit (only if verification-only fixups were needed)**

```bash
git add -A
git commit -m "chore(share): share verification fixups"   # only if needed
```

---

## Self-Review

- **Spec coverage:** `ACTION_SEND` text/plain intent-filter (Task 4) ✓; pure `sharedText` extractor unit-tested (Task 1) ✓; `chat.connect()` (idempotent) + `createSession()` (Task 4) ✓; `PendingShareStore` race-proof by sessionId, unit-tested (Task 2) ✓; reuse of `pendingRoute` deep-link (Task 4) ✓; `ChatViewModel.initialDraft` consumed in `open()` + `ChatScreen` pre-fill (Task 3) ✓; not-configured drops share, `createSession` failure → Toast + no navigation (Task 4) ✓; on-device verify incl. onNewIntent + not-configured (Task 5) ✓; text/plain-only scope, images/chooser/auto-send excluded ✓; no bridge changes ✓.
- **Placeholder scan:** every code step has full code; the only "if needed" is a conditional verification commit.
- **Type consistency:** `sharedText(action, type, subject, text): String?`, `PendingShareStore.put(sessionId, text)`/`take(sessionId): String?`, `ChatViewModel.initialDraft: StateFlow<String?>`/`clearInitialDraft()`, `handleShare(intent)`, and the reused `pendingRoute`/`ChatRepository.connect()`/`createSession()` are consistent across Tasks 1–4.

**Ordering note:** Tasks 1 & 2 are the pure/testable core (independent, compile alone). Task 3 (pre-fill) depends on Task 2. Task 4 (entry) depends on Tasks 1, 2, and 3. Task 5 verifies the whole.
