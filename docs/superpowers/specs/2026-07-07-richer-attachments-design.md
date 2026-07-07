# Richer Attachments (chat composer) — Design

**Date:** 2026-07-07
**Status:** Approved (brainstorming) → implementation plan next
**Branch:** `feature/richer-attachments`
**Source:** improvement roadmap Phase 1 · Wave 3 (`docs/ideas/improvement-roadmap-2026-07-07.md`).

## Goal

Camera capture + multiple images in the chat composer, with a **local staging area** (thumbnail chips + remove) sent on Send. Replaces today's single fire-and-forget gallery image.

## The gateway reality (drives scope)

The only attach path is one WS RPC — `image.attach_bytes {session_id, data(base64), mime_type}` (no filename, no generic file-attach, no REST upload), and it's **fire-and-forget** (no detach RPC). Consequences:
- **PDF/doc is deferred** — needs a gateway `file.attach` (or confirmation `image.attach_bytes` accepts non-image bytes). Out of scope; noted as a follow-up.
- **Multi-image is best-effort** — we send all staged images (sequential `attachImageBytes`) right before `prompt.submit`. If the gateway *replaces* rather than *accumulates* pending attachments, it degrades to last-image-wins (no worse than today). **Verify on the real gateway.**
- **Remove requires local staging** — since there's no detach RPC, the only way to support remove-✕ is to hold picked bytes locally and attach on Send (not on pick).

## Hard constraints

- **No gateway/bridge API changes** — reuse `image.attach_bytes`. Material 3; per-tenant accent (`AccentChrome`/`LocalProfileAccent`).
- **No `CAMERA` permission** — `TakePicture` delegates to the camera app; only a `FileProvider` is needed. No AI/assistant attribution.

## Grounding (from exploration)

- `ChatRepository.attachImageBytes(sessionId, dataBase64, mimeType)` → `client.call("image.attach_bytes", {session_id, data, mime_type})`. `submit(sessionId, text)` → `prompt.submit {session_id, text}` (attachments bind server-side to the next prompt).
- `ChatViewModel.attachImage(b64, mime)` currently calls `attachImageBytes` once + `appendSystem("📎 Image attached …")`. `ChatScreen` `pickImage = rememberLauncherForActivityResult(GetContent()) { … vm.attachImage(b64, mime) }`, launched `pickImage.launch("image/*")`.
- `ChatUiState(messages, pendingApproval, pendingClarify, isGenerating)` — **no** attachment state today. `send(text)` adds an optimistic user bubble (`withUserMessage`) + `submit`/`slashExec`. `canSend = connected && draft.isNotBlank() && !isGenerating`.
- Share-intent (`ACTION_SEND` image) currently attaches directly. Manifest: no `<provider>`, no `CAMERA`. Camera/FileProvider/multi-select are greenfield.

## Element 1 — Model + pure helpers (`ui/chat/Attachments.kt`, unit-tested)
```kotlin
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

## Element 2 — `ChatUiState` staging
Add `val pendingAttachments: List<PendingAttachment> = emptyList()`. Pure reducer helpers:
```kotlin
fun ChatUiState.withAttachment(a: PendingAttachment) = copy(pendingAttachments = pendingAttachments.plusCapped(a))
fun ChatUiState.withoutAttachment(id: String) = copy(pendingAttachments = pendingAttachments.filterNot { it.id == id })
```
(Clearing on send is inline: `copy(pendingAttachments = emptyList())`.)

## Element 3 — `ChatViewModel`
```kotlin
private var attachSeq = 0
fun stageAttachment(bytes: ByteArray, mimeType: String) {
    _state.update { it.withAttachment(PendingAttachment("att-${attachSeq++}", bytes, mimeType)) }
}
fun removeAttachment(id: String) { _state.update { it.withoutAttachment(id) } }
```
`send(text)` changes to flush staged attachments **before** submit:
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
        } catch (e: Exception) { appendError(e.message ?: "Failed to send message") }
    }
}
```
The **share-intent image** path is rerouted to `stageAttachment(bytes, mime)` (so a shared image appears as a chip) instead of the old direct `attachImageBytes`. The old `attachImage(b64, mime)` method is removed (replaced by staging).

## Element 4 — Manifest FileProvider + pick launchers (`AndroidManifest.xml`, `res/xml/file_paths.xml`, `ChatScreen.kt`)
- **Manifest** (inside `<application>`):
```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/file_paths" />
</provider>
```
- **`res/xml/file_paths.xml`:** `<paths><cache-path name="captures" path="." /></paths>`.
- **Attach button → menu:** replace the single `AttachFile` `IconButton` with one that opens a `DropdownMenu`: **Camera** · **Photo library**.
  - **Photo library:** `rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(ATTACH_CAP)) { uris -> uris.forEach { readBytes(it)?.let { b -> vm.stageAttachment(b, contentResolver.getType(it) ?: "image/*") } } }`; launch `PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)`. No permission.
  - **Camera:** hold `var captureUri by remember { mutableStateOf<Uri?>(null) }` + a cache temp file via `FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(context.cacheDir, "capture_<seq>.jpg"))`; `rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) captureUri?.let { readBytes(it)?.let { b -> vm.stageAttachment(b, "image/jpeg") } } }`; launch with the FileProvider uri. No `CAMERA` permission.
  - `readBytes(uri) = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }` (a local helper), inside `runCatching`.

## Element 5 — Staging chips UI + send wiring (`ChatScreen.kt`)
- Above the composer input row: `if (state.pendingAttachments.isNotEmpty())` a horizontal `LazyRow` of chips. Each chip: a small (~56.dp) rounded thumbnail decoded from bytes (`remember(a.id) { BitmapFactory.decodeByteArray(a.bytes, 0, a.bytes.size)?.asImageBitmap() }`, downsampled is fine) with a remove-✕ badge (accent-tinted) → `vm.removeAttachment(a.id)`.
- **Send gate:** `val canSend = canSend(connected, draft.isNotBlank(), state.pendingAttachments.isNotEmpty(), state.isGenerating)`; `submit()` calls `vm.send(draft)` (which flushes attachments) then `draft = ""`.

## Testing

- **Unit (pure), TDD — `AttachmentsTest`:** `canSend` truth table (blank+no-attach→false; text→true; attach-only→true; disconnected→false; generating→false); `plusCapped` (adds under cap; no-op at cap=6); `withoutAttachment` removes by id, leaves others.
- **On-device:** pick 2 from Photo library → 2 thumbnail chips; remove one → 1 chip; Camera → capture → chip added; type text + Send → attaches fire (system/no-crash), chips clear, message sent; Send with only an attachment (blank text) works; tenant accent on menu/chips. (Real multi-delivery depends on the gateway — verify against the live gateway; the mock verifies the calls fire + UI behavior.)

## Not doing (YAGNI) / deferred

- **PDF/doc** — gateway follow-up (`file.attach` or confirmed non-image `image.attach_bytes`).
- Reordering staged items; >6 images; video; drag-and-drop; editing a captured photo.
- Any gateway/API change; `CAMERA` permission (not needed for `TakePicture`).
