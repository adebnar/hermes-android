# TTS Read-Aloud Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Read an assistant response aloud via native `TextToSpeech`, from the assistant-bubble action menu. Client-only.

**Architecture:** A pure `speechText` markdown-stripper, a `TextToSpeechController` interface (Android impl wraps `TextToSpeech`, exposes `speaking: StateFlow<Boolean>`), `ChatViewModel` delegation, and a "Read aloud"/"Stop" dropdown item threaded through the message list.

**Tech Stack:** Kotlin, Compose, Material3, Hilt, `android.speech.tts.TextToSpeech`.

**Spec:** `docs/superpowers/specs/2026-07-17-tts-read-aloud-design.md`

## Global Constraints
- Client-only; assistant turns only; no AI attribution.
- Build env: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
- Branch: `feature/tts-read-aloud` (off `dev`). All commits land here.

---

### Task 1: `speechText` pure helper

**Files:** Create `app/src/main/java/com/hermes/client/ui/chat/SpeechText.kt`; Test `app/src/test/java/com/hermes/client/ui/chat/SpeechTextTest.kt`

- [ ] **Step 1: Write the failing test**

`SpeechTextTest.kt`:
```kotlin
package com.hermes.client.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextTest {
    @Test fun strips_emphasis_and_headings() {
        assertEquals("Hello world", speechText("**Hello** _world_"))
        assertEquals("Title", speechText("# Title"))
    }

    @Test fun link_becomes_its_text() {
        assertEquals("click here", speechText("[click here](https://example.com)"))
    }

    @Test fun inline_code_backticks_stripped() {
        assertEquals("run ls now", speechText("run `ls` now"))
    }

    @Test fun fenced_code_block_removed() {
        val out = speechText("before\n```kotlin\nval x = 1\n```\nafter")
        assertTrue(out.contains("before"))
        assertTrue(out.contains("after"))
        assertTrue(!out.contains("val x = 1"))
    }

    @Test fun plain_text_unchanged_and_empty_is_empty() {
        assertEquals("just words", speechText("just words"))
        assertEquals("", speechText(""))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.hermes.client.ui.chat.SpeechTextTest"` → FAIL (unresolved).

- [ ] **Step 3: Implement**

`SpeechText.kt`:
```kotlin
package com.hermes.client.ui.chat

/**
 * Strip common markdown so TextToSpeech reads content, not syntax. Best-effort and intentionally
 * simple — fenced code is dropped, inline code/emphasis/heading markers removed, links reduced to
 * their text. Not a full markdown parser.
 */
fun speechText(raw: String): String {
    if (raw.isBlank()) return ""
    var s = raw
    // Fenced code blocks: drop entirely (```lang ... ```).
    s = Regex("```[\\s\\S]*?```").replace(s, " ")
    // Links [text](url) -> text.
    s = Regex("\\[([^\\]]+)]\\([^)]*\\)").replace(s) { it.groupValues[1] }
    // Inline code `code` -> code.
    s = Regex("`([^`]*)`").replace(s) { it.groupValues[1] }
    // Heading markers at line start.
    s = Regex("(?m)^\\s{0,3}#{1,6}\\s*").replace(s, "")
    // Emphasis markers ** * __ _ (leave apostrophes/words intact).
    s = s.replace("**", "").replace("__", "")
    s = Regex("(?<![A-Za-z0-9])[*_](?=\\S)").replace(s, "")
    s = Regex("(?<=\\S)[*_](?![A-Za-z0-9])").replace(s, "")
    // Collapse whitespace runs and trim.
    s = Regex("[ \\t]+").replace(s, " ")
    s = Regex("\\n{2,}").replace(s, "\n").trim()
    return s
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.hermes.client.ui.chat.SpeechTextTest"` → PASS (5 tests). If a regex edge case fails, adjust the regex minimally to satisfy the asserted behavior (the tests are the contract).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/chat/SpeechText.kt \
        app/src/test/java/com/hermes/client/ui/chat/SpeechTextTest.kt
git commit -m "feat: add speechText markdown stripper for TTS"
```

---

### Task 2: TextToSpeechController + Android impl + DI + ChatViewModel wiring

**Files:**
- Create: `app/src/main/java/com/hermes/client/data/tts/TextToSpeechController.kt`
- Modify: `app/src/main/java/com/hermes/client/di/AppModule.kt`
- Modify: `app/src/main/java/com/hermes/client/ui/chat/ChatViewModel.kt`
- Test: `app/src/test/java/com/hermes/client/ui/chat/ChatViewModelTest.kt` (extend)

**Interfaces:**
- Consumes: `speechText` (Task 1).
- Produces: `interface TextToSpeechController { val speaking: StateFlow<Boolean>; fun speak(text: String); fun stop() }`; `class AndroidTtsManager(context: Context) : TextToSpeechController`; `ChatViewModel.speaking`/`readAloud(text)`/`stopReading()`.

- [ ] **Step 1: Write the failing test (extend ChatViewModelTest)**

Add a mock field near the other mocks in `ChatViewModelTest.kt`:
```kotlin
    private val tts = mockk<com.hermes.client.data.tts.TextToSpeechController>(relaxed = true)
```
Update `buildVm()` to pass it (new last arg):
```kotlin
    private fun buildVm() = ChatViewModel(chatRepo, sessionRepo, modelRepo, profileRepo, profileManager, favoritesStore, pendingShareStore, tts)
```
Add tests:
```kotlin
    @Test fun readAloud_speaks_markdown_stripped_text() {
        val vm = buildVm()
        vm.readAloud("**hi** `there`")
        io.mockk.verify { tts.speak("hi there") }
    }

    @Test fun stopReading_stops_tts() {
        val vm = buildVm()
        vm.stopReading()
        io.mockk.verify { tts.stop() }
    }
```
(If `every { tts.speaking } returns MutableStateFlow(false)` is needed for the VM to read `tts.speaking` at construction, add it to the test setup alongside the other `every { … }` stubs. `relaxed = true` returns a default for `speaking`, but if the VM assigns `val speaking = tts.speaking` a relaxed mock returns a relaxed StateFlow — acceptable; add an explicit stub only if a test asserts on `vm.speaking`.)

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.hermes.client.ui.chat.ChatViewModelTest"` → FAIL (unresolved `TextToSpeechController`/`readAloud`/`stopReading` + arity mismatch).

- [ ] **Step 3: Create the controller + Android impl**

`app/src/main/java/com/hermes/client/data/tts/TextToSpeechController.kt`:
```kotlin
package com.hermes.client.data.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Speaks text aloud; [speaking] is true while an utterance is playing. */
interface TextToSpeechController {
    val speaking: StateFlow<Boolean>
    fun speak(text: String)
    fun stop()
}

private const val UTTERANCE_ID = "hermes-read-aloud"

/** Android [TextToSpeech]-backed controller. Init is async; a speak before ready is queued. */
class AndroidTtsManager(context: Context) : TextToSpeechController {
    private val _speaking = MutableStateFlow(false)
    override val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private var ready = false
    private var pending: String? = null

    private val engine = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { _speaking.value = true }
                override fun onDone(utteranceId: String?) { _speaking.value = false }
                @Deprecated("legacy") override fun onError(utteranceId: String?) { _speaking.value = false }
                override fun onError(utteranceId: String?, errorCode: Int) { _speaking.value = false }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { _speaking.value = false }
            })
            pending?.let { speak(it); pending = null }
        }
    }

    override fun speak(text: String) {
        if (text.isBlank()) return
        if (!ready) { pending = text; return }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    override fun stop() {
        pending = null
        engine.stop()
        _speaking.value = false
    }
}
```

- [ ] **Step 4: Provide it via Hilt**

In `app/src/main/java/com/hermes/client/di/AppModule.kt`, add a provider (mirror the existing `@Provides @Singleton` + `@ApplicationContext context: Context` style):
```kotlin
    @Provides
    @Singleton
    fun provideTextToSpeechController(
        @ApplicationContext context: Context,
    ): com.hermes.client.data.tts.TextToSpeechController =
        com.hermes.client.data.tts.AndroidTtsManager(context)
```

- [ ] **Step 5: Wire ChatViewModel**

In `ChatViewModel.kt`, add the constructor param (new last param) and the three members. Add to the `@Inject constructor(...)`:
```kotlin
    private val tts: com.hermes.client.data.tts.TextToSpeechController,
```
Add inside the class body:
```kotlin
    /** True while a response is being read aloud. */
    val speaking: kotlinx.coroutines.flow.StateFlow<Boolean> = tts.speaking

    /** Read [text] aloud (markdown stripped for cleaner speech). */
    fun readAloud(text: String) = tts.speak(speechText(text))

    /** Stop any current read-aloud. */
    fun stopReading() = tts.stop()
```

- [ ] **Step 6: Run tests + compile**

Run: `./gradlew :app:testDebugUnitTest --tests "com.hermes.client.ui.chat.ChatViewModelTest"` → PASS.
Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/hermes/client/data/tts/TextToSpeechController.kt \
        app/src/main/java/com/hermes/client/di/AppModule.kt \
        app/src/main/java/com/hermes/client/ui/chat/ChatViewModel.kt \
        app/src/test/java/com/hermes/client/ui/chat/ChatViewModelTest.kt
git commit -m "feat: TextToSpeechController + ChatViewModel read-aloud"
```

---

### Task 3: Dropdown item + threading + stop-on-leave

**Files:**
- Modify: `app/src/main/java/com/hermes/client/ui/chat/ChatComponents.kt`
- Modify: `app/src/main/java/com/hermes/client/ui/chat/ChatScreen.kt`
- Test: none new (Compose glue). Verified by compile + full suite + assembleBeta + Task 4.

**Interfaces:** Consumes `ChatViewModel.speaking`/`readAloud`/`stopReading` (Task 2).

- [ ] **Step 1: Thread params through the message list**

In `ChatComponents.kt`, add three params to `ChatMessageList` (after `onRegenerate`):
```kotlin
    isSpeaking: Boolean = false,
    onReadAloud: (String) -> Unit = {},
    onStopReading: () -> Unit = {},
```
Pass them into `MessageBubble` where it is called inside `ChatMessageList` (alongside the existing args), and add matching params to `MessageBubble`:
```kotlin
private fun MessageBubble(
    msg: ChatMessage,
    canRegenerate: Boolean,
    onEditResend: (String) -> Unit,
    onRegenerate: () -> Unit,
    isSpeaking: Boolean,
    onReadAloud: (String) -> Unit,
    onStopReading: () -> Unit,
    highlighted: Boolean = false,
)
```
and forward `isSpeaking`/`onReadAloud`/`onStopReading` to `AssistantTurn` (add the same three params to `AssistantTurn`'s signature). (`MessageBubble` dispatches `USER -> UserBubble`, `else -> AssistantTurn(...)` — add the args only to the `AssistantTurn` call.)

- [ ] **Step 2: Add the dropdown item in `AssistantTurn`**

In `AssistantTurn`'s `DropdownMenu`, after the `Copy` item (and the `canRegenerate` item), add:
```kotlin
            if (msg.text.isNotBlank() && !msg.isError) {
                DropdownMenuItem(
                    text = { Text(if (isSpeaking) "Stop" else "Read aloud") },
                    onClick = {
                        if (isSpeaking) onStopReading() else onReadAloud(msg.text)
                        menuOpen = false
                    },
                )
            }
```

- [ ] **Step 3: Wire ChatScreen**

In `ChatScreen.kt`, collect speaking near the other `collectAsStateWithLifecycle` calls:
```kotlin
    val speaking by vm.speaking.collectAsStateWithLifecycle()
```
At the `ChatMessageList(...)` call (~line 475), add:
```kotlin
                        isSpeaking = speaking,
                        onReadAloud = { vm.readAloud(it) },
                        onStopReading = { vm.stopReading() },
```
Add a `DisposableEffect` in `ChatScreen` so audio stops when the screen leaves composition:
```kotlin
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { vm.stopReading() } }
```

- [ ] **Step 4: Compile + full suite + assembleBeta**

Run each (JAVA_HOME set): `:app:compileDebugKotlin`, `:app:testDebugUnitTest` (0 failures), `:app:assembleBeta`. All BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/chat/ChatComponents.kt \
        app/src/main/java/com/hermes/client/ui/chat/ChatScreen.kt
git commit -m "feat: Read aloud/Stop item on assistant bubbles"
```

---

### Task 4: On-device verification

**Files:** none (manual).

- [ ] **Step 1:** `:app:installBeta`.
- [ ] **Step 2:** Open a chat with an assistant reply → long-press the assistant turn → tap **Read aloud** → confirm audio plays and the item flips to **Stop**.
- [ ] **Step 3:** Tap **Stop** → audio stops. Start again, then navigate back → audio stops (stop-on-leave).
- [ ] **Step 4:** Read a code-heavy reply → confirm backticks/markdown aren't read as literal syntax.
- [ ] **Step 5:** Record pass/fail in the PR description (no commit). If the emulator has no TTS engine/audio, note it and rely on the unit tests + review.

---

## Notes for the executor
- `AndroidTtsManager` never `shutdown()`s the engine (a process-lifetime singleton); acceptable for v1. Do not add a language/voice picker or auto-read (anti-scope).
- If `UtteranceProgressListener`'s abstract members differ by API level, implement all required overrides so it compiles against the project's compileSdk.
