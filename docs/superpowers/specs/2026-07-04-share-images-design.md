# Share-to-Hermes: Images — Design

**Date:** 2026-07-04
**Status:** Approved (brainstorming) → implementation plan next
**Branch:** `feature/share-images`
**Extends:** `docs/superpowers/specs/2026-07-03-share-to-hermes-design.md`

## Goal

Extend the shipped Share-to-Hermes target to accept a shared **image** (screenshot/photo), not just text: sharing an image into Hermes opens a new chat with the image **attached** (and any caption text pre-filled), ready to send. Reuses the composer's existing image path; **no gateway/bridge changes.**

## Hard constraints

- **No bridge/gateway API changes.** Reuse the composer's existing attach path: `ChatRepository.attachImageBytes(sessionId, dataBase64, mimeType)` → WS `image.attach_bytes`.
- Follow existing patterns: pure unit-tested helpers (`sharedText`), the `PendingShareStore` handoff, the `pendingRoute` → `HermesNav` deep-link.
- No AI/assistant attribution in commits, files, or PRs.

## What the app already provides (grounding)

- Composer image attach (`ChatScreen`): `contentResolver.openInputStream(uri).use { readBytes() }` → `android.util.Base64.encodeToString(bytes, Base64.NO_WRAP)` → `ChatViewModel.attachImage(b64, mime)` → `ChatRepository.attachImageBytes(sessionId, b64, mime)` (WS `image.attach_bytes`), then a `"📎 Image attached — it will be sent with your next message."` system note.
- Share flow (text): `MainActivity.handleShare` → pure `sharedText(...)` → clears the consumed extras → `chat.connect()` + `createSession()` → `PendingShareStore.put(id, text)` → `pendingRoute = "chat/$id"`; `ChatViewModel.open(id)` does `pendingShareStore.take(id)?.let { _initialDraft.value = it }`; `ChatScreen` pre-fills `draft`.
- `ChatViewModel.open(id)` resolves the live session handle via `chat.resume(...)` inside its `viewModelScope.launch { ... }` (updates `sessionId`); `appendSystem`/`appendError` exist.
- `NotificationTileService`/deep-link/etc. unaffected.

## Architecture

Add an image branch to the existing share entry point and generalize the one-shot handoff to carry an optional image, then attach it on chat open via the existing WS path. Each unit stays small.

### Components

1. **Pure `isImageShare(action: String?, type: String?): Boolean`** — `app/src/main/java/com/hermes/client/share/ShareIntent.kt` (beside `sharedText`). `action == Intent.ACTION_SEND && type != null && type.startsWith("image/")`. Unit-tested.

2. **`PendingShare` + generalized `PendingShareStore`** — `app/src/main/java/com/hermes/client/share/PendingShareStore.kt`.
   - `data class PendingShare(val text: String? = null, val imageBase64: String? = null, val imageMime: String? = null)`.
   - `@Singleton PendingShareStore`: `private var pending: Pair<String, PendingShare>?`; `fun put(sessionId: String, share: PendingShare)`; `fun take(sessionId: String): PendingShare?` — returns and clears only when the sessionId matches (race-proof, as today). Both `@Synchronized`.
   - This replaces the text-only `Pair<String,String>` — the existing text call site (`handleShare`, `ChatViewModel.open`) and `PendingShareStoreTest` update to `PendingShare(text=…)`.

3. **`MainActivity.handleShare`** — branch on the share type:
   - **Text** (`sharedText(...) != null`): as today, but `PendingShare(text = text)`.
   - **Image** (`isImageShare(intent.action, intent.type)`): read the `EXTRA_STREAM` Uri (`intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)` on API 33+, the deprecated single-arg overload below); if null → return. Read any caption as `intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf { it.isNotEmpty() }`. Clear the consumed extras (`EXTRA_STREAM`, `EXTRA_TEXT`, `EXTRA_SUBJECT`) to prevent config-change re-fire. If not configured → return. Then in the existing `lifecycleScope.launch` (activity-scoped, so the `EXTRA_STREAM` read grant holds):
     - on `Dispatchers.IO`: `val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }`; if null/throws → main-thread Toast "Couldn't read the image" and return; `val mime = contentResolver.getType(uri) ?: "image/*"`; `val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)`.
     - `chat.connect()`; `runCatching { chat.createSession() }` → onSuccess: `pendingShare.put(id, PendingShare(text = caption, imageBase64 = b64, imageMime = mime))`; `pendingRoute.value = "chat/$id"`. onFailure (non-cancellation): Toast "Couldn't start a chat".
   - Keep the CancellationException rethrow.

4. **`ChatViewModel.open(id)`** — replace the text-only consumption:
   - `val ps = pendingShareStore.take(id)`; `ps?.text?.let { _initialDraft.value = it }` (existing behavior).
   - Inside the existing open coroutine, **after `chat.resume(...)`** (so `sessionId` is the live handle and the WS is ready), if `ps?.imageBase64 != null && ps.imageMime != null`: `runCatching { chat.attachImageBytes(sessionId, ps.imageBase64, ps.imageMime) }.onSuccess { appendSystem("📎 Image attached — it will be sent with your next message.") }.onFailure { if (it is CancellationException) throw it; appendError("Attach failed: ${it.message}") }`.

5. **Manifest** — the existing `MainActivity` `ACTION_SEND` intent-filter (currently `<data android:mimeType="text/plain" />`) gains a second data type:
   ```xml
   <intent-filter>
       <action android:name="android.intent.action.SEND" />
       <category android:name="android.intent.category.DEFAULT" />
       <data android:mimeType="text/plain" />
       <data android:mimeType="image/*" />
   </intent-filter>
   ```

## Data flow

```
Share image → MainActivity (ACTION_SEND, image/*)
  → isImageShare -> read EXTRA_STREAM Uri bytes (IO) -> base64 (+ optional EXTRA_TEXT caption)
  → chat.connect() + createSession()
  → PendingShareStore.put(id, PendingShare(caption, b64, mime)); pendingRoute="chat/<id>"
  → HermesNav deep-links to chat/<id>
  → ChatViewModel.open(id) → take(id): caption -> initialDraft (composer pre-fill);
    after resume -> attachImageBytes(sessionId, b64, mime) -> "📎 Image attached"
```

## Error handling

- **Unreadable / null Uri or read failure:** main-thread Toast "Couldn't read the image"; if a caption was present the chat still opens with it pre-filled, else no navigation.
- **`attachImageBytes` failure:** existing `appendError("Attach failed: …")` in the chat (same as the paperclip path).
- **Not configured / `createSession` failure:** same handling as the text path (drop / Toast "Couldn't start a chat").
- **Config-change re-fire:** the image branch clears `EXTRA_STREAM`/`EXTRA_TEXT`/`EXTRA_SUBJECT` after reading, mirroring the text-path fix so a recreation doesn't re-share.
- **Large images:** read + base64 happen on `Dispatchers.IO`; no main-thread block. (Very large images rely on the gateway's own limits — unchanged from the paperclip path.)

## Testing

- **Unit (pure), TDD:**
  - `ShareIntentTest` (extend): `isImageShare(ACTION_SEND, "image/png") == true`; `("image/jpeg") == true`; `(ACTION_SEND, "text/plain") == false`; `(ACTION_VIEW, "image/png") == false`; `(ACTION_SEND, null) == false`.
  - `PendingShareStoreTest` (update): `put(id, PendingShare(text="hi"))` → `take(id)` returns it once, then null; `put(id, PendingShare(imageBase64="AAA", imageMime="image/png"))` → `take(id)` returns the image fields; `take(otherId)` returns null and keeps the value.
- **On-device:** from Photos (or Chrome/screenshot share sheet), share an image to **Hermes Beta** → a new chat opens, shows "📎 Image attached", and the composer holds any caption; type a prompt and send → the image goes with the message. Also share an image while the app is backgrounded (`onNewIntent`).

## Not doing (YAGNI)

- `ACTION_SEND_MULTIPLE` / multiple images at once.
- Non-image binary shares (PDF, etc.).
- A preview thumbnail in the composer (the "📎 attached" note matches the current paperclip behavior).
- Any new gateway endpoint or a switch to `/api/files/upload` (the existing `image.attach_bytes` path is reused).

## Open questions (non-blocking; plan may settle)

- Exact `EXTRA_STREAM` retrieval API guard (`getParcelableExtra(name, Uri::class.java)` on API 33+, deprecated single-arg below) — the plan pins the version branch.
