# Record-to-Task (v2) — Design

**Wave:** Quick-wins (client-only). **Branch:** `feature/record-to-task` (off `dev`).

**Goal:** Capture a spoken task as a longer voice note, transcribe it server-side, and open a **new** chat prefilled with the transcript — turning a voice note into a task without typing.

**Constraints:** Kotlin/Compose/Material3/Hilt, per-tenant accent for chrome (semantic colors for error). No AI attribution; gitleaks before push; PR into `dev`.

## Feasibility (from the gateway + app audit)
- **Transcribe endpoint exists:** `POST /api/audio/transcribe` (`hermes_cli/web_server.py:3743`) takes JSON `{ data_url, mime_type? }` where `data_url` is a base64 data URL (`data:<mime>;base64,<b64>`). Accepts `audio/*` (incl. `audio/mp4`/m4a, aac, webm, wav…); 25 MB cap. Returns `{ ok, transcript, provider }`; failures are non-2xx with a `{detail}` body.
- **Gateway prerequisite (not client-controlled):** transcription needs an STT backend on the gateway host — default `local` (faster-whisper), or a cloud STT key + `stt.provider`. If none is configured the endpoint returns an error; the app surfaces it gracefully.
- **Authenticated REST client ready:** `HermesRestApi` (hand-rolled OkHttp) already attaches the gateway base URL + `X-Hermes-Session-Token` on every call. A new `transcribe(...)` method just POSTs.
- **New-chat + prefill rail ready:** `ChatRepository.createSession(profile)` + the existing `PendingShareStore.put(id, PendingShare(text=…))` → `ChatViewModel` picks it up as `initialDraft` → the composer draft. This is exactly how text-share opens a prefilled chat today.
- **No new Gradle deps:** `MediaRecorder` + Base64 are framework; OkHttp already present; `okhttp-mockwebserver` is already a test dep.

## Scope
- **In:** a "Record a task" mic action on the home session list → RECORD_AUDIO runtime permission → record (MediaRecorder, `audio/mp4`/AAC) → stop → base64 data URL → `POST /api/audio/transcribe` → on success, create a new chat and **prefill** the transcript into its composer (review-before-send). Recording/transcribing/error UX in a bottom sheet.
- **Out (deferred):** auto-submit without review; in-chat "record note into this thread"; audio playback/waveform; on-device offline transcription; a settings toggle for STT provider (gateway-config-owned).

## Why prefill, not auto-send
STT is imperfect and the note may be long. Prefilling the new chat's composer lets the user glance/fix before sending — and reuses the existing share rail verbatim (zero new navigation plumbing). Auto-send is the deferred variant.

## Architecture

### 1. `data/network/HermesRestApi.kt` (modify) — transcribe
```kotlin
suspend fun transcribe(dataUrl: String, mimeType: String): String = withContext(Dispatchers.IO) {
    val obj = buildJsonObject { put("data_url", dataUrl); put("mime_type", mimeType) }
    val payload = json.encodeToString(JsonObject.serializer(), obj)
        .toRequestBody("application/json".toMediaType())
    okHttp.newCall(builder("/api/audio/transcribe").post(payload).build()).execute().use { resp ->
        val body = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw HermesApiException(resp.code, "transcription failed")
        json.decodeFromString<JsonObject>(body)["transcript"]?.jsonPrimitive?.content?.trim() ?: ""
    }
}
```
Mirrors `revealEnv`. Tested with MockWebServer (already a test dep).

### 2. `data/audio/AudioDataUrl.kt` (new, pure) — encoding
```kotlin
/** Build a base64 data URL the gateway accepts: data:<mime>;base64,<b64>. */
fun audioDataUrl(bytes: ByteArray, mime: String): String =
    "data:$mime;base64," + java.util.Base64.getEncoder().encodeToString(bytes)
```
Uses `java.util.Base64` (JVM — unit-testable without Robolectric), not `android.util.Base64`.

### 3. `data/audio/AudioRecorder.kt` (new) — recorder abstraction + MediaRecorder impl
```kotlin
data class Recording(val bytes: ByteArray, val mime: String)

/** Records a single voice note. Injected so the ViewModel is testable with a fake. */
interface AudioRecorder {
    fun start()                 // begin capture (no-op if already recording)
    fun stop(): Recording?      // stop + return the clip (null on failure/empty)
    fun cancel()                // stop + discard
}
```
`MediaAudioRecorder(@ApplicationContext ctx)` — MediaRecorder `MPEG_4`/`AAC` to a cache temp file, `stop()` reads bytes + returns mime `audio/mp4`, `cancel()` deletes. Device-bound → covered on-device, not unit-tested. Provided via Hilt.

### 4. `ui/record/RecordTaskViewModel.kt` (new) — orchestration
Injects `AudioRecorder`, `HermesRestApi`, `ChatRepository`, `ProfileManager`, `PendingShareStore`.
```kotlin
enum class RecordPhase { IDLE, RECORDING, TRANSCRIBING }
data class RecordUi(val phase: RecordPhase = RecordPhase.IDLE, val error: String? = null)
val ui: StateFlow<RecordUi>
val navigateTo: SharedFlow<String>            // created session id → host navigates

fun startRecording()                          // recorder.start(); phase=RECORDING
fun stopAndTranscribe()                       // recorder.stop() → encode → transcribe → new chat
fun cancel()                                  // recorder.cancel(); phase=IDLE
fun dismissError()                            // phase=IDLE, error=null
```
`stopAndTranscribe`: `recorder.stop()` → null ⇒ error "Nothing recorded"; else `audioDataUrl(bytes,mime)` → phase=TRANSCRIBING → `runCatching { api.transcribe(url, mime) }`; blank ⇒ error "Couldn't transcribe that"; failure ⇒ error "Transcription failed"; success ⇒ `chat.connect()`, `profileManager.refresh()`, `id = chat.createSession(profileManager.active.value)`, `pendingShareStore.put(id, PendingShare(text=transcript))`, emit `id`, phase=IDLE. All `runCatching` rethrows `CancellationException`.

### 5. `ui/record/RecordTaskSheet.kt` (new) + `ui/sessions/SessionsScreen.kt` (modify)
- **Sheet** (`ModalBottomSheet`): IDLE → a large record button + hint; RECORDING → a pulsing record indicator + a **Stop** button + Cancel; TRANSCRIBING → spinner "Transcribing…"; `error != null` → the message (in `colorScheme.error`) + Retry (→ IDLE) / Dismiss. Record/active accents use `LocalProfileAccent`; error uses the semantic error color.
- **SessionsScreen:** add a **mic** `IconButton` ("Record a task") in the existing `TopAppBar` `actions`. Tap → check `RECORD_AUDIO` (a `RequestPermission()` launcher); granted ⇒ open the sheet + `recordVm.startRecording()`; else request, and open+start on grant. Collect `recordVm.navigateTo` → `onOpen(id)` (the existing nav callback) → the new chat opens prefilled.

### 6. `AndroidManifest.xml` (modify) + Hilt
- Add `<uses-permission android:name="android.permission.RECORD_AUDIO" />`.
- `AppModule`: `@Provides fun provideAudioRecorder(@ApplicationContext ctx): AudioRecorder = MediaAudioRecorder(ctx)`.

## Data flow
```
home ⋮ mic → RECORD_AUDIO grant → sheet + recorder.start
Stop → recorder.stop() bytes/mime → audioDataUrl → POST /api/audio/transcribe → transcript
     → createSession(active) → PendingShareStore.put(id, PendingShare(text=transcript)) → onOpen(id)
     → ChatViewModel.open() takes the share → composer prefilled with the transcript
```

## Error handling
- Permission denied → sheet not opened (or a toast "Microphone needed to record a task").
- Nothing recorded / empty clip → error state, no chat created.
- Blank transcript (STT returned nothing) / endpoint error (no STT configured, 4xx/5xx/413) → error state + Retry; no chat created; no crash.
- `createSession` failure after a good transcript → error "Couldn't start a chat" (transcript preserved for Retry is out of scope; Retry re-records).

## Testing
- **`AudioDataUrlTest`** (pure): known bytes + mime → exact `data:<mime>;base64,<b64>` (verify prefix + round-trip decode).
- **`HermesRestApiTranscribeTest`** (MockWebServer): 200 `{transcript:"hi"}` → "hi" (trimmed); 400 → `HermesApiException(400)`; 200 with missing/blank transcript → "".
- **`RecordTaskViewModelTest`** (fakes): stop→transcribe→createSession→stash `PendingShare(text)`→emit id (phase returns IDLE); null recording → error, no api call; blank transcript → error, no session; transcribe throws → error; createSession throws → error.
- `MediaAudioRecorder`, the sheet, and SessionsScreen wiring are device/Compose glue — verified on-device (best-effort; needs mic + a gateway with STT).

## On-device verification
Home → mic → grant RECORD_AUDIO → record a short spoken task → Stop → "Transcribing…" → a new chat opens with the transcript in the composer. With no STT on the gateway → an error state, no chat, no crash. (Best-effort: the emulator mic + a gateway STT backend are required; covered by the unit tests + reviews otherwise.)

## Files
| Action | Path |
|--------|------|
| Modify | `data/network/HermesRestApi.kt` (`transcribe`) + `HermesRestApiTranscribeTest.kt` |
| New | `data/audio/AudioDataUrl.kt` + `AudioDataUrlTest.kt` |
| New | `data/audio/AudioRecorder.kt` (interface + `MediaAudioRecorder`) |
| Modify | `di/AppModule.kt` (provide `AudioRecorder`) |
| New | `ui/record/RecordTaskViewModel.kt` + `RecordTaskViewModelTest.kt` |
| New | `ui/record/RecordTaskSheet.kt` |
| Modify | `ui/sessions/SessionsScreen.kt` (mic action + sheet host + permission) |
| Modify | `AndroidManifest.xml` (RECORD_AUDIO) |

## Build & gates
`JAVA_HOME=…`: `:app:compileDebugKotlin`, `:app:testDebugUnitTest`, `:app:assembleBeta`. gitleaks before push; PR into `dev`.
