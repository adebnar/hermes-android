# Voice Dictation (chat composer) — Design

**Date:** 2026-07-07
**Status:** Approved (brainstorming) → implementation plan next
**Branch:** `feature/voice-dictation`
**Source:** improvement roadmap Phase 1 · Wave 1 (`docs/ideas/improvement-roadmap-2026-07-07.md`).

## Goal

Add a mic to the chat composer so the user can dictate a message into the draft. **Client-only** via the system speech recognizer (`RecognizerIntent`) — no `RECORD_AUDIO` permission, no manifest change, no gateway change. Small, contained.

## Hard constraints

- **No gateway/bridge API changes** (there is no `/api/audio/transcribe` client method — server fallback is out of scope). Material 3.
- Multi-tenant isolation: the mic tints to `LocalProfileAccent.current`.
- No AI/assistant attribution in commits, files, or PRs.

## Grounding (from exploration)

- `ui/chat/ChatScreen.kt`: `draft` is a plain `String` in the composable — `var draft by remember { mutableStateOf("") }` (line 82). The composer bottomBar `Row` order: `ProfileAvatar` → attach `IconButton(pickImage.launch("image/*"))` → `OutlinedTextField(value=draft, onValueChange={draft=it})` → Stop/Send.
- Image attach uses `rememberLauncherForActivityResult(GetContent()) { uri -> … vm.attachImage(...) }` (the launcher-in-composable pattern to mirror).
- Manifest has only `INTERNET`/`POST_NOTIFICATIONS`/`FOREGROUND_SERVICE*`; **no `RECORD_AUDIO`** (and `RecognizerIntent` doesn't need it). Existing runtime-permission idiom is the POST_NOTIFICATIONS `RequestPermission()` flow — **not needed here**.
- `LocalContext.current` is already bound in the composer; `LocalHapticFeedback` is used by `submit()`.

## Decision (locked)

**`RecognizerIntent.ACTION_RECOGNIZE_SPEECH`** (the system speech overlay), not in-app `SpeechRecognizer`. The system app owns the mic UI, partial results, and the permission, so this needs no `RECORD_AUDIO` and ~15 lines. (Inline live-transcription via `SpeechRecognizer` is a possible later wave.)

## Design

### Pure helper (unit-tested) — `ui/chat/Dictation.kt`
```kotlin
/** Append dictated [spoken] to the current [current] draft (space-joined; blank-safe). */
fun appendDictation(current: String, spoken: String): String {
    val s = spoken.trim()
    if (s.isEmpty()) return current
    return if (current.isBlank()) s else current.trimEnd() + " " + s
}
```

### Mic button + launcher (`ui/chat/ChatScreen.kt`)
- **Availability (once):** `val speechAvailable = remember { android.speech.SpeechRecognizer.isRecognitionAvailable(context) }`. The mic is rendered **only when `speechAvailable`** (no dead button on devices without speech services).
- **Launcher:** mirror the image pattern —
```kotlin
val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    if (result.resultCode == android.app.Activity.RESULT_OK) {
        val spoken = result.data
            ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull().orEmpty()
        draft = appendDictation(draft, spoken)
    }
}
```
- **Launch:** on mic tap, build and launch the intent:
```kotlin
val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault())
    putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Speak your message")
}
runCatching { speech.launch(intent) }
```
- **Placement + style:** a mic `IconButton` between the attach button and the `OutlinedTextField`:
```kotlin
if (speechAvailable) {
    IconButton(onClick = { runCatching { speech.launch(intent) } }) {
        Icon(Icons.Rounded.Mic, contentDescription = "Voice input", tint = com.hermes.client.ui.components.AccentChrome.fabContainer)
    }
}
```
  (Use `Icons.Rounded.Mic`.) **Not gated on `connected`** — dictation fills the local draft, so it works offline; the user sends when connected. The `runCatching` guards the rare `ActivityNotFoundException`.
- **Errors/cancel:** a cancelled or empty result is a silent no-op (the `RESULT_OK` + `appendDictation` blank-safe handling covers it).

## Testing

- **Unit (pure), TDD — `DictationTest`:** blank current + spoken → spoken; non-blank current + spoken → `"current spoken"` (single space, current trimmed); blank/whitespace spoken → current unchanged; spoken trimmed.
- **On-device:** build + install; open a chat.
  - If the emulator/device **has** speech services: the mic renders (accent-tinted) between attach and the field; tapping it opens the system speech overlay; a spoken phrase appends to the draft (space-joined with any existing text); Send works. Cancelling leaves the draft unchanged.
  - If it **lacks** speech services (a plain AVD may): the mic is gracefully **absent**, the composer is otherwise unchanged, no crash. (Note: real dictation needs a device/emulator image with Google speech; verify at least the availability-gating + no-crash + placement on the AVD, and the append logic via the unit test.)

## Not doing (YAGNI)

- In-app `SpeechRecognizer` live inline transcription (later wave).
- Server-side `/api/audio/transcribe` fallback (no endpoint).
- Cursor-aware insertion (`draft` stays a `String`; append only) and TTS playback.
- Any gateway/API change or new permission.
