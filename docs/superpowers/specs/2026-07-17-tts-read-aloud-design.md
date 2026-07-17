# TTS Read-Aloud — Design

**Wave:** Quick-wins (client-only). **Branch:** `feature/tts-read-aloud` (off `dev`).

**Goal:** Let the user have an assistant response read aloud via native Android `TextToSpeech`, from the existing per-bubble action menu. Fully client-only; no gateway involvement.

**Constraints:** Kotlin / Compose / Material3 / Hilt. No AI attribution; gitleaks before push; PR into `dev`.

## Scope

- **In:** a "Read aloud" / "Stop" item on the assistant-bubble dropdown; a lifecycle-safe TTS wrapper; markdown-stripped speech text; stop when leaving the chat.
- **Out:** voice/language pickers, per-message playback UI/scrubber, auto-read-on-arrival, reading user/system messages (assistant turns only).

## Architecture

### 1. `ui/chat/SpeechText.kt` (new) — pure helper
`fun speechText(raw: String): String` — strip common markdown so TTS doesn't read syntax aloud: fenced code blocks removed, inline code backticks stripped, `**`/`*`/`_` emphasis markers removed, heading `#` markers removed, link `[text](url)` → `text`, collapse blank runs. Pure, unit-tested.

### 2. `data/tts/TextToSpeechController.kt` (new) — interface + Android impl
```kotlin
interface TextToSpeechController {
    val speaking: StateFlow<Boolean>
    fun speak(text: String)   // QUEUE_FLUSH (replaces any current utterance)
    fun stop()
}
```
`AndroidTtsManager(context)` (Hilt `@Singleton`): wraps `android.speech.tts.TextToSpeech`. Lazy async init via `OnInitListener` — a `speak` before init completes is queued and spoken on ready (or dropped if init failed). An `UtteranceProgressListener` drives `speaking` (`true` on start, `false` on done/error/stop). `speak` uses a fixed utterance id + `QUEUE_FLUSH`. Provided via a Hilt module using `@ApplicationContext`.

### 3. `ui/chat/ChatViewModel.kt` (modify)
Inject `TextToSpeechController`; expose:
```kotlin
val speaking: StateFlow<Boolean> = tts.speaking
fun readAloud(text: String) = tts.speak(speechText(text))
fun stopReading() = tts.stop()
```

### 4. UI — `ui/chat/ChatComponents.kt` + `ChatScreen.kt` (modify)
Thread `isSpeaking: Boolean`, `onReadAloud: (String) -> Unit`, `onStopReading: () -> Unit` through `ChatMessageList` → `MessageBubble` → `AssistantTurn` (mirroring the existing `onRegenerate` threading). In `AssistantTurn`'s `DropdownMenu`, add an item:
- `isSpeaking` → **"Stop"** → `onStopReading()`; else → **"Read aloud"** → `onReadAloud(msg.text)` (only when `msg.text.isNotBlank()` and `!msg.isError`).

In `ChatScreen`: collect `vm.speaking`; pass `isSpeaking`, `onReadAloud = { vm.readAloud(it) }`, `onStopReading = { vm.stopReading() }` into `ChatMessageList` (call site ~line 475). Add a `DisposableEffect` (or reuse the existing leave path) calling `vm.stopReading()` when the chat screen leaves composition, so audio doesn't continue after navigating away.

## Data flow
```
long-press assistant bubble → "Read aloud" → onReadAloud(msg.text)
  → vm.readAloud → tts.speak(speechText(text)) → TextToSpeech (QUEUE_FLUSH)
  → UtteranceProgressListener → speaking=true → menu item shows "Stop"
"Stop" / leave chat → vm.stopReading() → tts.stop() → speaking=false
```

## Error handling
- TTS init failure → `speak` is a no-op (nothing read; no crash); `speaking` stays false.
- Blank/`isError` message → no "Read aloud" item shown.
- `speechText` on empty → "" → nothing spoken.

## Testing
- **`SpeechTextTest`** (pure): code fence removed; inline backticks stripped; `**bold**`/`# heading`/`[t](u)` → clean text; plain text unchanged; empty → empty.
- **`ChatViewModelTest`** (mock `TextToSpeechController`): `readAloud("**hi**")` calls `tts.speak` with the markdown-stripped text; `stopReading()` calls `tts.stop()`; `speaking` reflects the controller's flow.
- The `AndroidTtsManager`/`TextToSpeech` glue is Android — verified on-device.

## On-device verification
Open a chat with an assistant reply → long-press → **Read aloud** → confirm audio plays and the item flips to **Stop**; tap **Stop** → audio stops. Start reading, then navigate back → confirm audio stops. Read a code-heavy reply → confirm code syntax isn't read as literal backticks/markdown.

## Files
| Action | Path |
|--------|------|
| New | `ui/chat/SpeechText.kt` + test |
| New | `data/tts/TextToSpeechController.kt` (interface + `AndroidTtsManager`) |
| Modify | `di/AppModule.kt` (provide the controller) |
| Modify | `ui/chat/ChatViewModel.kt` |
| Modify | `ui/chat/ChatComponents.kt` (menu + threading) |
| Modify | `ui/chat/ChatScreen.kt` (wire + stop-on-leave) |
| New test | `SpeechTextTest.kt`; extend `ChatViewModelTest` |

## Build & gates
`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`: `:app:compileDebugKotlin`, `:app:testDebugUnitTest`, `:app:assembleBeta`. gitleaks before push; PR into `dev`.
