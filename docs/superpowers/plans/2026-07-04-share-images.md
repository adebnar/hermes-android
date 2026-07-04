# Share-to-Hermes: Images Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Sharing an image into Hermes opens a new chat with the image attached (and any caption pre-filled), ready to send — reusing the composer's existing attach path.

**Architecture:** Add an image branch to `MainActivity.handleShare`; generalize the one-shot `PendingShareStore` to carry an optional image; attach it on chat open via the existing `ChatRepository.attachImageBytes` (WS `image.attach_bytes`). No gateway/bridge changes.

**Tech Stack:** Kotlin, Compose, Hilt, Android intents, JUnit.

## Global Constraints

- **No bridge/gateway API changes.** Reuse `ChatRepository.attachImageBytes(sessionId, dataBase64, mimeType)` (WS `image.attach_bytes`), the `pendingRoute` → `HermesNav` deep-link, and `chat.connect()`/`createSession()`.
- JDK 21: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`. compileSdk/targetSdk 36, minSdk 26.
- Compile: `./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tail -5`. Tests: `./gradlew :app:testDebugUnitTest --console=plain`. Beta: `./gradlew :app:assembleBeta`.
- Follow patterns: pure unit tests like `ShareIntentTest`/`NotificationMapperTest`; the existing `PendingShareStore` handoff.
- **No AI/assistant attribution** in commits, files, or PRs.
- v1: single image (`ACTION_SEND`), optional caption. `ACTION_SEND_MULTIPLE`/non-image binaries excluded.

## File Structure

- Modify `app/src/main/java/com/hermes/client/share/ShareIntent.kt` — add pure `isImageShare`.
- Modify `app/src/test/java/com/hermes/client/share/ShareIntentTest.kt` — `isImageShare` cases.
- Modify `app/src/main/java/com/hermes/client/share/PendingShareStore.kt` — `PendingShare` data class + generalized store.
- Modify `app/src/test/java/com/hermes/client/share/PendingShareStoreTest.kt` — updated for `PendingShare`.
- Modify `app/src/main/java/com/hermes/client/ui/chat/ChatViewModel.kt` — consume `PendingShare` (text now; image in Task 3).
- Modify `app/src/main/java/com/hermes/client/MainActivity.kt` — image branch in `handleShare`.
- Modify `app/src/main/AndroidManifest.xml` — `image/*` data type.

---

### Task 1: Pure `isImageShare` (TDD)

**Files:**
- Modify: `app/src/main/java/com/hermes/client/share/ShareIntent.kt`
- Modify: `app/src/test/java/com/hermes/client/share/ShareIntentTest.kt`

**Interfaces:**
- Produces: `fun isImageShare(action: String?, type: String?): Boolean`.

- [ ] **Step 1: Add the failing tests** — append these to `ShareIntentTest`'s class body:

```kotlin
    @Test fun image_send_is_image_share() {
        assertTrue(isImageShare(Intent.ACTION_SEND, "image/png"))
        assertTrue(isImageShare(Intent.ACTION_SEND, "image/jpeg"))
    }
    @Test fun text_send_is_not_image_share() {
        assertFalse(isImageShare(Intent.ACTION_SEND, "text/plain"))
    }
    @Test fun non_send_image_is_not_image_share() {
        assertFalse(isImageShare(Intent.ACTION_VIEW, "image/png"))
    }
    @Test fun null_type_is_not_image_share() {
        assertFalse(isImageShare(Intent.ACTION_SEND, null))
    }
```

Add `import org.junit.Assert.assertFalse` and `import org.junit.Assert.assertTrue` to the test file's imports if not already present.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*ShareIntentTest*' --console=plain 2>&1 | tail -6`
Expected: FAIL — unresolved `isImageShare`.

- [ ] **Step 3: Add the pure function** to `ShareIntent.kt` (below `sharedText`; the file already imports `android.content.Intent`):

```kotlin
/** Pure: is this an ACTION_SEND of an image (image/*)? */
fun isImageShare(action: String?, type: String?): Boolean =
    action == Intent.ACTION_SEND && type != null && type.startsWith("image/")
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*ShareIntentTest*' --console=plain 2>&1 | tail -6`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hermes/client/share/ShareIntent.kt app/src/test/java/com/hermes/client/share/ShareIntentTest.kt
git commit -m "feat(share): pure isImageShare helper with tests"
```

---

### Task 2: Generalize `PendingShareStore` to `PendingShare` (keeps text working)

**Files:**
- Modify: `app/src/main/java/com/hermes/client/share/PendingShareStore.kt`
- Modify: `app/src/test/java/com/hermes/client/share/PendingShareStoreTest.kt`
- Modify: `app/src/main/java/com/hermes/client/ui/chat/ChatViewModel.kt`
- Modify: `app/src/main/java/com/hermes/client/MainActivity.kt`
- Modify (if referenced): `app/src/test/java/com/hermes/client/ui/chat/ChatViewModelTest.kt`

**Interfaces:**
- Produces: `data class PendingShare(text: String? = null, imageBase64: String? = null, imageMime: String? = null)`; `PendingShareStore.put(sessionId: String, share: PendingShare)`; `take(sessionId: String): PendingShare?`.

- [ ] **Step 1: Update the store test first (TDD)** — replace the body of `PendingShareStoreTest` with:

```kotlin
    @Test fun put_then_take_same_id_returns_share_once() {
        val store = PendingShareStore()
        store.put("s1", PendingShare(text = "hello"))
        assertEquals("hello", store.take("s1")?.text)
        assertNull(store.take("s1"))   // consumed
    }

    @Test fun take_other_id_returns_null_and_keeps_value() {
        val store = PendingShareStore()
        store.put("s1", PendingShare(text = "hello"))
        assertNull(store.take("s2"))
        assertEquals("hello", store.take("s1")?.text)
    }

    @Test fun stores_image_fields() {
        val store = PendingShareStore()
        store.put("s1", PendingShare(imageBase64 = "AAA", imageMime = "image/png"))
        val s = store.take("s1")!!
        assertEquals("AAA", s.imageBase64)
        assertEquals("image/png", s.imageMime)
        assertNull(s.text)
    }

    @Test fun take_when_empty_is_null() {
        assertNull(PendingShareStore().take("s1"))
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*PendingShareStoreTest*' --console=plain 2>&1 | tail -6`
Expected: FAIL — `PendingShare` unresolved / `take` type mismatch.

- [ ] **Step 3: Rewrite `PendingShareStore.kt`**

```kotlin
package com.hermes.client.share

import javax.inject.Inject
import javax.inject.Singleton

/** A pending share (text and/or an image) handed to the chat that opens for it. */
data class PendingShare(
    val text: String? = null,
    val imageBase64: String? = null,
    val imageMime: String? = null,
)

/**
 * One-shot, in-process handoff from the share entry point to the chat that opens for it. Not
 * persisted — survives a single navigation. take() is keyed by sessionId so a normal chat open
 * never consumes a share meant for a different (freshly-created) session.
 */
@Singleton
class PendingShareStore @Inject constructor() {
    private var pending: Pair<String, PendingShare>? = null

    @Synchronized
    fun put(sessionId: String, share: PendingShare) {
        pending = sessionId to share
    }

    @Synchronized
    fun take(sessionId: String): PendingShare? {
        val p = pending ?: return null
        if (p.first != sessionId) return null
        pending = null
        return p.second
    }
}
```

- [ ] **Step 4: Update the text call sites** so the build stays green.

In `ChatViewModel.open(id)`, find the existing line (added by the share feature):
```kotlin
        pendingShareStore.take(id)?.let { _initialDraft.value = it }
```
Replace it with (capture the share into a local `ps` so Task 3 can also read its image):
```kotlin
        val ps = pendingShareStore.take(id)
        ps?.text?.let { _initialDraft.value = it }
```

In `MainActivity.handleShare`, change the text put:
```kotlin
                    pendingShare.put(id, text)
```
to:
```kotlin
                    pendingShare.put(id, com.hermes.client.share.PendingShare(text = text))
```

- [ ] **Step 5: Fix `ChatViewModelTest` if it references the old store API**

If `ChatViewModelTest` calls `pendingShareStore.put(...)`/`take(...)` directly, update those calls to the `PendingShare` API. It constructs `PendingShareStore()` (no-arg) — that constructor is unchanged, so likely no change is needed beyond any direct put/take calls. Do not weaken existing assertions.

- [ ] **Step 6: Compile + full unit suite**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --console=plain 2>&1 | grep -E "^e: |FAILED|BUILD" | head`
Expected: `BUILD SUCCESSFUL`, no `FAILED`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/hermes/client/share/PendingShareStore.kt app/src/test/java/com/hermes/client/share/PendingShareStoreTest.kt app/src/main/java/com/hermes/client/ui/chat/ChatViewModel.kt app/src/main/java/com/hermes/client/MainActivity.kt app/src/test/java/com/hermes/client/ui/chat/ChatViewModelTest.kt
git commit -m "refactor(share): generalize PendingShareStore to PendingShare (text/image)"
```

---

### Task 3: Image share — entry, attach-on-open, manifest

**Files:**
- Modify: `app/src/main/java/com/hermes/client/MainActivity.kt`
- Modify: `app/src/main/java/com/hermes/client/ui/chat/ChatViewModel.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `isImageShare` (Task 1), `PendingShare` (Task 2), `ChatRepository.attachImageBytes`, `chat.connect()/createSession()`, `pendingRoute`.

- [ ] **Step 1: Replace `MainActivity.handleShare` with the text-or-image version**

Replace the entire existing `handleShare` method with:

```kotlin
    /**
     * Handle an incoming ACTION_SEND share (text or a single image): open a new chat with the text
     * pre-filled and/or the image attached. Reuses the notification deep-link rail.
     */
    private fun handleShare(intent: Intent?) {
        val text = com.hermes.client.share.sharedText(
            intent?.action, intent?.type,
            intent?.getStringExtra(Intent.EXTRA_SUBJECT), intent?.getStringExtra(Intent.EXTRA_TEXT),
        )
        val isImage = com.hermes.client.share.isImageShare(intent?.action, intent?.type)
        val imageUri: android.net.Uri? = if (isImage) {
            if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
            } else {
                @Suppress("DEPRECATION") intent?.getParcelableExtra(Intent.EXTRA_STREAM)
            }
        } else null

        if (text == null && imageUri == null) return

        // For an image share the caption (if any) is EXTRA_TEXT; a text share's caption is `text`.
        val caption = if (isImage) {
            intent?.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf { it.isNotEmpty() }
        } else text

        // Consume the extras so a config-change recreation doesn't re-fire the share.
        intent?.removeExtra(Intent.EXTRA_TEXT)
        intent?.removeExtra(Intent.EXTRA_SUBJECT)
        intent?.removeExtra(Intent.EXTRA_STREAM)

        if (credentialStore.load() == null) return

        lifecycleScope.launch {
            var b64: String? = null
            var mime: String? = null
            if (imageUri != null) {
                val read = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        val bytes = contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
                            ?: return@runCatching null
                        val m = contentResolver.getType(imageUri) ?: "image/*"
                        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP) to m
                    }.getOrNull()
                }
                if (read == null) {
                    android.widget.Toast.makeText(
                        this@MainActivity, "Couldn't read the image", android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    if (caption == null) return@launch  // nothing left to share
                } else {
                    b64 = read.first; mime = read.second
                }
            }
            // connect() first — a cold-start share has no open socket yet, and createSession()
            // would otherwise fail after the ready-gate timeout. connect() is idempotent.
            chat.connect()
            runCatching { chat.createSession() }
                .onSuccess { id ->
                    pendingShare.put(
                        id,
                        com.hermes.client.share.PendingShare(text = caption, imageBase64 = b64, imageMime = mime),
                    )
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

Add `import kotlinx.coroutines.withContext` to `MainActivity.kt` if not present. (`android.net.Uri`, `android.util.Base64`, `kotlinx.coroutines.Dispatchers` are referenced fully-qualified.)

- [ ] **Step 2: Attach the shared image on chat open**

In `ChatViewModel.open(id)`, inside the existing `viewModelScope.launch { ... }` block, **after** the resume handle is resolved (the line `handle?.let { sessionId = it }` and its `DebugLog.log("session", "resume(...)…")`), add — using the `ps` local introduced in Task 2:

```kotlin
            // A share may have handed off an image; attach it now that the session handle is live.
            ps?.let { share ->
                val imgB64 = share.imageBase64
                val imgMime = share.imageMime
                if (imgB64 != null && imgMime != null) {
                    runCatching { chat.attachImageBytes(sessionId, imgB64, imgMime) }
                        .onSuccess { appendSystem("📎 Image attached — it will be sent with your next message.") }
                        .onFailure { e ->
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            appendError("Attach failed: ${e.message}")
                        }
                }
            }
```

- [ ] **Step 3: Add `image/*` to the manifest share filter**

In `AndroidManifest.xml`, the `MainActivity` `ACTION_SEND` intent-filter currently has `<data android:mimeType="text/plain" />`. Add the image type alongside it:

```xml
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="text/plain" />
                <data android:mimeType="image/*" />
            </intent-filter>
```

- [ ] **Step 4: Compile + full unit suite + assemble**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --console=plain 2>&1 | grep -E "^e: |FAILED|BUILD" | head`
Expected: `BUILD SUCCESSFUL`, no `FAILED`.
Run: `./gradlew :app:assembleBeta --console=plain 2>&1 | grep -E "^e: |BUILD" | tail -2`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hermes/client/MainActivity.kt app/src/main/java/com/hermes/client/ui/chat/ChatViewModel.kt app/src/main/AndroidManifest.xml
git commit -m "feat(share): accept shared images — attach on open + image/* filter"
```

---

### Task 4: On-device verification

**Files:** none (verification only).

- [ ] **Step 1: Build + install the beta**

Run `./gradlew :app:assembleBeta` then `adb -e install -r app/build/outputs/apk/beta/app-beta.apk` (or `-d` for a device). Point it at a working gateway (or the mock at `http://10.0.2.2:8899`).

- [ ] **Step 2: Verify the image share**

- From **Photos** (or a screenshot's share sheet, or Chrome "share image"), Share → **Hermes Beta** appears for an image. Tap it → a new chat opens, shows **"📎 Image attached — …"**; type a prompt and send → the image goes with the message.
- Share an image **with a caption** (an app that includes `EXTRA_TEXT`) → the caption is pre-filled in the composer *and* the image is attached.
- Share while the app is already open (backgrounded) → `onNewIntent` still routes to a fresh chat with the image attached.
- Text share still works (regression): share a link from Chrome → new chat with the URL pre-filled.

- [ ] **Step 3: Commit (only if verification-only fixups were needed)**

```bash
git add -A
git commit -m "chore(share): image-share verification fixups"   # only if needed
```

---

## Self-Review

- **Spec coverage:** pure `isImageShare` unit-tested (Task 1) ✓; `PendingShare(text, imageBase64, imageMime)` + generalized store, unit-tested, text call-sites kept green (Task 2) ✓; `handleShare` image branch — read `EXTRA_STREAM` (SDK-33 `getParcelableExtra` branch), base64 on IO, optional caption, clear extras, connect+createSession (Task 3) ✓; attach-on-open after resume via `attachImageBytes` + the "📎 attached" note (Task 3) ✓; manifest `image/*` (Task 3) ✓; error handling — unreadable image Toast, attach failure `appendError`, not-configured/createSession failure (Task 3) ✓; on-device incl. caption + onNewIntent + text regression (Task 4) ✓; single-image-only, no multiple/binary/thumbnail ✓; no bridge changes (reuses `image.attach_bytes`) ✓.
- **Placeholder scan:** every code step has full code; the only "if needed" is a conditional verification commit and the conditional `ChatViewModelTest` fixup (guarded by "if it references the old API").
- **Type consistency:** `isImageShare(action, type)`, `PendingShare(text, imageBase64, imageMime)`, `PendingShareStore.put(sessionId, share)`/`take(sessionId): PendingShare?`, the `ps` local in `open()`, and `chat.attachImageBytes(sessionId, b64, mime)` are used consistently across Tasks 1–3.

**Ordering note:** Task 1 (pure) and Task 2 (store generalization, keeps text working) each end green. Task 3 depends on 1 + 2 (uses `isImageShare`, `PendingShare`, and the `ps` local). Task 4 verifies on-device.
