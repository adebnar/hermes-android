# Richer Attachments Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Camera capture + multi-image in the chat composer, staged locally as removable thumbnail chips and sent on Send.

**Architecture:** Picked images stage into `ChatUiState.pendingAttachments` (bytes held locally); `send()` flushes them via the existing `image.attach_bytes` RPC right before `prompt.submit`, then clears. Camera uses `TakePicture` + a `FileProvider` (no `CAMERA` permission); multi-image uses the system Photo Picker.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, AndroidX ActivityResult (`TakePicture`, `PickMultipleVisualMedia`), `FileProvider`, JUnit.

## Global Constraints

- **No gateway/bridge API changes** — reuse `image.attach_bytes`. **PDF deferred**; multi-image is **best-effort** (degrades to last-wins if the gateway replaces).
- **No `CAMERA` permission** — `TakePicture` delegates to the camera app.
- Material 3; per-tenant accent (`AccentChrome`/`LocalProfileAccent`).
- JDK 21: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
- Compile: `./gradlew :app:compileDebugKotlin --console=plain`. Tests: `./gradlew :app:testDebugUnitTest --console=plain`. Beta: `./gradlew :app:assembleBeta`.
- **No AI/assistant attribution** in commits, files, or PRs.

## Grounding

- `ChatRepository.attachImageBytes(sessionId, dataBase64, mimeType)` → `image.attach_bytes {session_id, data, mime_type}`. `submit(sessionId, text)` → `prompt.submit`.
- `ChatViewModel`: `send(text)` adds `withUserMessage(text)` then `chat.slashExec`/`chat.submit`; `attachImage(b64, mime)` (to be removed) calls `attachImageBytes` + `appendSystem`. Share-intent image currently attaches directly. `_state` is a `MutableStateFlow<ChatUiState>` (uses `_state.value` and `_state.update { }`).
- `ChatScreen`: `var draft by remember { mutableStateOf("") }`; `val context = LocalContext.current`; the `pickImage = rememberLauncherForActivityResult(GetContent()) { … vm.attachImage(...) }` launcher + the `AttachFile` `IconButton(onClick = { pickImage.launch("image/*") })`; `canSend = connected && draft.isNotBlank() && !state.isGenerating`; `submit()`.
- `DropdownMenu` pattern: `CronScreen.kt`. Pure-test pattern: `MessageActionsTest`/`ScheduleTest`.

---

### Task 1: Pure model + helpers (TDD)

**Files:**
- Create: `app/src/main/java/com/hermes/client/ui/chat/Attachments.kt`
- Test: `app/src/test/java/com/hermes/client/ui/chat/AttachmentsTest.kt`

**Interfaces:** Produces `class PendingAttachment(id, bytes, mimeType)`, `const val ATTACH_CAP`, `fun List<PendingAttachment>.plusCapped(a, cap)`, `fun canSend(connected, hasText, hasAttachments, isGenerating): Boolean`.

- [ ] **Step 1: Write the failing test** — create `AttachmentsTest.kt`:

```kotlin
package com.hermes.client.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentsTest {
    private fun att(id: String) = PendingAttachment(id, byteArrayOf(1, 2, 3), "image/jpeg")

    @Test fun canSend_requires_connection_content_and_not_generating() {
        assertFalse(canSend(connected = true, hasText = false, hasAttachments = false, isGenerating = false))
        assertTrue(canSend(connected = true, hasText = true, hasAttachments = false, isGenerating = false))
        assertTrue(canSend(connected = true, hasText = false, hasAttachments = true, isGenerating = false))
        assertFalse(canSend(connected = false, hasText = true, hasAttachments = true, isGenerating = false))
        assertFalse(canSend(connected = true, hasText = true, hasAttachments = true, isGenerating = true))
    }

    @Test fun plusCapped_adds_under_cap_and_noops_at_cap() {
        val four = (0 until 4).map { att("a$it") }
        assertEquals(5, four.plusCapped(att("a4")).size)
        val six = (0 until ATTACH_CAP).map { att("a$it") }
        assertEquals(ATTACH_CAP, six.plusCapped(att("aX")).size) // no-op at cap
    }

    @Test fun pendingAttachment_equality_by_id() {
        assertEquals(PendingAttachment("x", byteArrayOf(1), "image/png"), PendingAttachment("x", byteArrayOf(9, 9), "image/jpeg"))
        assertFalse(PendingAttachment("x", byteArrayOf(1), "image/png") == PendingAttachment("y", byteArrayOf(1), "image/png"))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*AttachmentsTest*' --console=plain 2>&1 | tail -6`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Implement `Attachments.kt`**

```kotlin
package com.hermes.client.ui.chat

/** A picked-but-unsent attachment, held locally until Send. Identity is [id] (bytes excluded). */
class PendingAttachment(val id: String, val bytes: ByteArray, val mimeType: String) {
    override fun equals(other: Any?) = other is PendingAttachment && other.id == id
    override fun hashCode() = id.hashCode()
}

const val ATTACH_CAP = 6

/** Add [a] unless already at [cap]; returns the list unchanged when full. */
fun List<PendingAttachment>.plusCapped(a: PendingAttachment, cap: Int = ATTACH_CAP): List<PendingAttachment> =
    if (size >= cap) this else this + a

/** True when a message may be sent: connected, has text or an attachment, and not mid-generation. */
fun canSend(connected: Boolean, hasText: Boolean, hasAttachments: Boolean, isGenerating: Boolean): Boolean =
    connected && (hasText || hasAttachments) && !isGenerating
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*AttachmentsTest*' --console=plain 2>&1 | tail -6`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/chat/Attachments.kt app/src/test/java/com/hermes/client/ui/chat/AttachmentsTest.kt
git commit -m "feat(chat): PendingAttachment model + canSend/plusCapped helpers"
```

---

### Task 2: `ChatUiState` staging field + reducers

**Files:** Modify `app/src/main/java/com/hermes/client/ui/chat/ChatUiState.kt`

**Interfaces:** Consumes `PendingAttachment`, `plusCapped` (Task 1). Produces `ChatUiState.pendingAttachments`, `ChatUiState.withAttachment(a)`, `ChatUiState.withoutAttachment(id)`.

- [ ] **Step 1: Add the field to `ChatUiState`**

Add to the `data class ChatUiState(...)` constructor (after `isGenerating`):
```kotlin
    val pendingAttachments: List<PendingAttachment> = emptyList(),
```

- [ ] **Step 2: Add the pure reducer helpers** (top-level in the same file, next to the other `ChatUiState.` extensions)

```kotlin
fun ChatUiState.withAttachment(a: PendingAttachment): ChatUiState =
    copy(pendingAttachments = pendingAttachments.plusCapped(a))

fun ChatUiState.withoutAttachment(id: String): ChatUiState =
    copy(pendingAttachments = pendingAttachments.filterNot { it.id == id })
```

- [ ] **Step 3: Compile**

Run: `./gradlew :app:compileDebugKotlin --console=plain 2>&1 | grep -E "^e: |BUILD" | head`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/chat/ChatUiState.kt
git commit -m "feat(chat): pendingAttachments state + reducers"
```

---

### Task 3: `ChatViewModel` — stage / remove / send-flush + share path

**Files:** Modify `app/src/main/java/com/hermes/client/ui/chat/ChatViewModel.kt`

**Interfaces:** Consumes `withAttachment`/`withoutAttachment` (Task 2), `PendingAttachment` (Task 1). Produces `fun stageAttachment(bytes: ByteArray, mimeType: String)`, `fun removeAttachment(id: String)`, and a rewritten `fun send(text: String)`.

- [ ] **Step 1: Add staging methods** (near `send()`)

```kotlin
    private var attachSeq = 0
    fun stageAttachment(bytes: ByteArray, mimeType: String) {
        _state.update { it.withAttachment(PendingAttachment("att-${attachSeq++}", bytes, mimeType)) }
    }
    fun removeAttachment(id: String) { _state.update { it.withoutAttachment(id) } }
```

- [ ] **Step 2: Rewrite `send(text)` to flush staged attachments before submit**

Replace the existing `send(text)` body with:
```kotlin
    fun send(text: String) {
        val atts = _state.value.pendingAttachments
        if (text.isBlank() && atts.isEmpty()) return
        val isSlash = text.trimStart().startsWith("/")
        val shown = text.ifBlank { "📎 ${atts.size} image${if (atts.size > 1) "s" else ""}" }
        _state.value = _state.value.withUserMessage(shown).copy(pendingAttachments = emptyList())
        viewModelScope.launch {
            try {
                atts.forEach { a ->
                    val b64 = android.util.Base64.encodeToString(a.bytes, android.util.Base64.NO_WRAP)
                    runCatching { chat.attachImageBytes(sessionId, b64, a.mimeType) }
                        .onFailure { appendError("Attach failed: ${it.message}") }
                }
                if (isSlash) chat.slashExec(sessionId, text.trim()) else chat.submit(sessionId, text)
            } catch (e: Exception) {
                appendError(e.message ?: "Failed to send message")
            }
        }
    }
```
(Note: `withUserMessage` is called with `shown` so an attachment-only send shows a "📎 N images" bubble rather than a blank one.)

- [ ] **Step 3: Reroute the share-intent image path + remove `attachImage`**

Read the file for the share-intent handling (where a shared image's bytes/base64 were passed to `attachImageBytes`/`attachImage`) and the old `attachImage(dataBase64, mimeType)` method. Change the share path so the shared image is **staged** instead of directly attached: decode the shared image to a `ByteArray` and call `stageAttachment(bytes, mime)` (if the share currently carries base64, decode with `android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)` to get the bytes). Then **delete** the now-unused `fun attachImage(...)` method. Confirm no other caller of `attachImage` remains (grep).

- [ ] **Step 4: Compile + full suite**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --console=plain 2>&1 | grep -E "^e: |FAILED|BUILD" | head`
Expected: `BUILD SUCCESSFUL`, no `FAILED`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/chat/ChatViewModel.kt
git commit -m "feat(chat): stage attachments locally, flush on send; share stages too"
```

---

### Task 4: FileProvider + camera/photo-picker launchers

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/xml/file_paths.xml`
- Modify: `app/src/main/java/com/hermes/client/ui/chat/ChatScreen.kt`

**Interfaces:** Consumes `vm.stageAttachment(bytes, mimeType)` (Task 3), `ATTACH_CAP` (Task 1).

- [ ] **Step 1: Declare the FileProvider** — inside `<application>` in `AndroidManifest.xml`:

```xml
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>
```

- [ ] **Step 2: Create `res/xml/file_paths.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="captures" path="." />
</paths>
```

- [ ] **Step 3: Add the pick launchers + a read helper in `ChatScreen`**

Near the existing image launcher, add (and a local `readBytes` helper):
```kotlin
    fun readBytes(uri: android.net.Uri): ByteArray? =
        runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()

    // Photo library: multi-select via the system photo picker (no permission).
    val pickPhotos = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(ATTACH_CAP),
    ) { uris ->
        uris.forEach { uri ->
            readBytes(uri)?.let { vm.stageAttachment(it, context.contentResolver.getType(uri) ?: "image/*") }
        }
    }

    // Camera: capture into a FileProvider cache uri, then read it back. No CAMERA permission (delegates).
    var captureUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture(),
    ) { ok ->
        if (ok) captureUri?.let { uri -> readBytes(uri)?.let { vm.stageAttachment(it, "image/jpeg") } }
    }
    fun launchCamera() {
        val file = java.io.File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        captureUri = uri
        runCatching { takePhoto.launch(uri) }
    }
```

- [ ] **Step 4: Replace the single attach button with a Camera / Photo library menu**

Remove the old `pickImage` (`GetContent`) launcher and its `IconButton(onClick = { pickImage.launch("image/*") })`. In its place, in the composer `Row`, add:
```kotlin
        var attachMenu by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { attachMenu = true }, enabled = connected) {
                Icon(Icons.Rounded.AttachFile, contentDescription = "Attach")
            }
            androidx.compose.material3.DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Camera") },
                    onClick = { attachMenu = false; launchCamera() },
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Photo library") },
                    onClick = {
                        attachMenu = false
                        pickPhotos.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                )
            }
        }
```

- [ ] **Step 5: Compile**

Run: `./gradlew :app:compileDebugKotlin --console=plain 2>&1 | grep -E "^e: |BUILD" | head`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/res/xml/file_paths.xml app/src/main/java/com/hermes/client/ui/chat/ChatScreen.kt
git commit -m "feat(chat): camera + photo-picker attach via FileProvider (no CAMERA perm)"
```

---

### Task 5: Staging chips UI + send gate wiring

**Files:** Modify `app/src/main/java/com/hermes/client/ui/chat/ChatScreen.kt`

**Interfaces:** Consumes `canSend` (Task 1), `state.pendingAttachments` (Task 2), `vm.removeAttachment` + `vm.send` (Task 3).

- [ ] **Step 1: Render the staging chips above the input row**

In the composer's bottomBar, wrap the input `Row` in a `Column` and, above the input `Row`, add:
```kotlin
        if (state.pendingAttachments.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.pendingAttachments, key = { it.id }) { a ->
                    val thumb = remember(a.id) {
                        android.graphics.BitmapFactory.decodeByteArray(a.bytes, 0, a.bytes.size)?.asImageBitmap()
                    }
                    Box(Modifier.size(56.dp)) {
                        if (thumb != null) {
                            Image(
                                bitmap = thumb,
                                contentDescription = "Attachment",
                                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
                        }
                        IconButton(
                            onClick = { vm.removeAttachment(a.id) },
                            modifier = Modifier.align(Alignment.TopEnd).size(20.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "Remove attachment",
                                tint = com.hermes.client.ui.components.AccentChrome.fabContainer,
                            )
                        }
                    }
                }
            }
        }
```
Add imports as needed: `androidx.compose.foundation.Image`, `androidx.compose.foundation.lazy.LazyRow`, `androidx.compose.foundation.lazy.items`, `androidx.compose.ui.graphics.asImageBitmap`, `androidx.compose.ui.layout.ContentScale`, `androidx.compose.material.icons.rounded.Close`, `androidx.compose.foundation.background`, `androidx.compose.foundation.shape.RoundedCornerShape`, `androidx.compose.foundation.clip`/`androidx.compose.ui.draw.clip`.

- [ ] **Step 2: Update the send gate + submit**

Change `canSend` to use the pure helper and make `submit()` clear the draft after `vm.send`:
```kotlin
    val canSend = canSend(connected, draft.isNotBlank(), state.pendingAttachments.isNotEmpty(), state.isGenerating)
```
Ensure `submit()` is:
```kotlin
    fun submit() {
        if (!canSend) return
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        vm.send(draft)
        draft = ""
    }
```

- [ ] **Step 3: Compile + assemble**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --console=plain 2>&1 | grep -E "^e: |FAILED|BUILD" | head` → `BUILD SUCCESSFUL`, no `FAILED`.
Run: `./gradlew :app:assembleBeta --console=plain 2>&1 | grep -E "^e: |BUILD" | tail -2` → `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/chat/ChatScreen.kt
git commit -m "feat(chat): staging thumbnail chips with remove; attachment-aware send gate"
```

---

### Task 6: On-device verification

**Files:** none (verification only).

- [ ] **Step 1: Build + install**

`./gradlew :app:assembleBeta` then `adb -e install -r app/build/outputs/apk/beta/app-beta.apk`; point at the mock (`http://10.0.2.2:8899`) and open a chat.

- [ ] **Step 2: Verify**

1. Attach button → menu shows **Camera** + **Photo library**.
2. **Photo library** → pick 2 images → two thumbnail chips appear above the composer.
3. Tap a chip's **remove-✕** → that chip disappears (one remains).
4. **Camera** → capture → a chip is added (accent-tinted remove badge).
5. Type text + **Send** → attaches fire (no crash), chips clear, the message (or "📎 N images" bubble) appears.
6. **Attachment-only send** (blank composer, one chip staged) → Send is enabled and works.
7. Tenant accent preserved on the menu + chip badges.

(Real multi-image delivery depends on the gateway accumulating — verify against the live gateway; the mock verifies the calls fire + all UI behavior + no crash.)

- [ ] **Step 3: Commit (only if verification-only fixups were needed)**

```bash
git add -A
git commit -m "chore(chat): richer-attachments verification fixups"   # only if needed
```

---

## Self-Review

- **Spec coverage:** Element 1 (model + helpers) → Task 1 ✓; Element 2 (state) → Task 2 ✓; Element 3 (VM stage/remove/send-flush + share) → Task 3 ✓; Element 4 (FileProvider + camera/photo launchers + menu) → Task 4 ✓; Element 5 (chips UI + send gate) → Task 5 ✓; on-device → Task 6 ✓. PDF deferral + no-CAMERA-permission honored (nothing adds `CAMERA` or a file-attach RPC).
- **Placeholder scan:** every code step has full code; the only "read the file" is Task 3 Step 3 (share-path location is discovered on read; the transformation + method removal are fully specified). Task-6 commit is conditional.
- **Type consistency:** `PendingAttachment(id, bytes, mimeType)`, `plusCapped`, `canSend(connected, hasText, hasAttachments, isGenerating)`, `withAttachment`/`withoutAttachment`, `stageAttachment(bytes, mimeType)`/`removeAttachment(id)`, `state.pendingAttachments` — all consistent across tasks and with the codebase (`AccentChrome.fabContainer`, `withUserMessage`, `chat.attachImageBytes`).

**Ordering:** Task 1 pure → Task 2 state → Task 3 VM → Task 4 manifest+launchers → Task 5 chips+gate → Task 6 verify.
