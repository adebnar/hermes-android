# Voice Dictation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a mic to the chat composer that dictates speech into the message draft via the system speech recognizer.

**Architecture:** A pure `appendDictation` helper (unit-tested) merges the transcript into the draft; the composer gains a mic `IconButton` that launches `RecognizerIntent` and appends the result. No `RECORD_AUDIO`, no manifest/gateway change.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, Android `RecognizerIntent`, JUnit.

## Global Constraints

- **No gateway/bridge API changes** (no `/api/audio/transcribe`); **no new permission** (`RecognizerIntent` needs no `RECORD_AUDIO`). Material 3.
- Multi-tenant isolation: the mic tints to the tenant accent (`AccentChrome.fabContainer` / `LocalProfileAccent`).
- JDK 21: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
- Compile: `./gradlew :app:compileDebugKotlin --console=plain`. Tests: `./gradlew :app:testDebugUnitTest --console=plain`. Beta: `./gradlew :app:assembleBeta`.
- **No AI/assistant attribution** in commits, files, or PRs.

## Grounding

- `ui/chat/ChatScreen.kt`: `draft` is `var draft by remember { mutableStateOf("") }` (~line 82); `val context = LocalContext.current` is already bound; image attach uses `val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> … }` launched from an `IconButton` in the composer `Row` (order: `ProfileAvatar` → attach `IconButton` → `OutlinedTextField` → Stop/Send). `AccentChrome.fabContainer` is the accent tint used by the Send icon.

---

### Task 1: Pure `appendDictation` helper (TDD)

**Files:**
- Create: `app/src/main/java/com/hermes/client/ui/chat/Dictation.kt`
- Test: `app/src/test/java/com/hermes/client/ui/chat/DictationTest.kt`

**Interfaces:** Produces `fun appendDictation(current: String, spoken: String): String`.

- [ ] **Step 1: Write the failing test** — create `DictationTest.kt`:

```kotlin
package com.hermes.client.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class DictationTest {
    @Test fun blank_current_returns_spoken() {
        assertEquals("hello", appendDictation("", "hello"))
        assertEquals("hello", appendDictation("   ", "  hello  "))
    }
    @Test fun appends_with_single_space() {
        assertEquals("type and speak", appendDictation("type", "and speak"))
        assertEquals("type more", appendDictation("type ", "  more "))
    }
    @Test fun blank_spoken_leaves_current_unchanged() {
        assertEquals("type", appendDictation("type", ""))
        assertEquals("type", appendDictation("type", "   "))
    }
    @Test fun trims_spoken() {
        assertEquals("hi there", appendDictation("hi", "  there  "))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*DictationTest*' --console=plain 2>&1 | tail -6`
Expected: FAIL — unresolved `appendDictation`.

- [ ] **Step 3: Implement `Dictation.kt`**

```kotlin
package com.hermes.client.ui.chat

/** Append dictated [spoken] to the current [current] draft (single-space-joined; blank-safe). */
fun appendDictation(current: String, spoken: String): String {
    val s = spoken.trim()
    if (s.isEmpty()) return current
    return if (current.isBlank()) s else current.trimEnd() + " " + s
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*DictationTest*' --console=plain 2>&1 | tail -6`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/chat/Dictation.kt app/src/test/java/com/hermes/client/ui/chat/DictationTest.kt
git commit -m "feat(chat): appendDictation helper for merging speech into the draft"
```

---

### Task 2: Mic button + `RecognizerIntent` launcher (`ChatScreen`)

**Files:** Modify `app/src/main/java/com/hermes/client/ui/chat/ChatScreen.kt`

**Interfaces:** Consumes `appendDictation` (Task 1).

- [ ] **Step 1: Add the availability flag + launcher + intent (near the `pickImage` launcher)**

After the existing `pickImage` launcher block, add:
```kotlin
    // Voice dictation: the system speech recognizer returns a transcript we append to the draft.
    // RecognizerIntent needs no RECORD_AUDIO (the system speech app owns the mic + permission).
    val speechAvailable = remember { android.speech.SpeechRecognizer.isRecognitionAvailable(context) }
    val speech = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull().orEmpty()
            draft = appendDictation(draft, spoken)
        }
    }
    fun startDictation() {
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault())
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Speak your message")
        }
        runCatching { speech.launch(intent) }
    }
```
(`context` and `draft` are already in scope in the composable; `rememberLauncherForActivityResult` is already imported for `pickImage`.)

- [ ] **Step 2: Render the mic between attach and the text field**

In the composer `Row`, immediately after the attach `IconButton { … Icons.Rounded.AttachFile … }` and before the `OutlinedTextField`, add:
```kotlin
        if (speechAvailable) {
            IconButton(onClick = { startDictation() }) {
                Icon(
                    Icons.Rounded.Mic,
                    contentDescription = "Voice input",
                    tint = com.hermes.client.ui.components.AccentChrome.fabContainer,
                )
            }
        }
```
Add the import `androidx.compose.material.icons.rounded.Mic` (other `Icons.Rounded.*` are already imported). The mic is **not** gated on `connected` (dictation fills the local draft).

- [ ] **Step 3: Compile + full suite**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --console=plain 2>&1 | grep -E "^e: |FAILED|BUILD" | head`
Expected: `BUILD SUCCESSFUL`, no `FAILED`.

- [ ] **Step 4: Assemble the beta variant**

Run: `./gradlew :app:assembleBeta --console=plain 2>&1 | grep -E "^e: |BUILD" | tail -2`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/chat/ChatScreen.kt
git commit -m "feat(chat): voice dictation mic in the composer (RecognizerIntent)"
```

---

### Task 3: On-device verification

**Files:** none (verification only).

- [ ] **Step 1: Build + install**

`./gradlew :app:assembleBeta` then `adb -e install -r app/build/outputs/apk/beta/app-beta.apk`; point at the mock (`http://10.0.2.2:8899`) and open a chat.

- [ ] **Step 2: Verify**

- **If the emulator has speech services:** an accent-tinted mic sits between the attach button and the text field; tapping it opens the system speech overlay; a spoken phrase appends to the draft (single space-joined with any existing text); Send works; cancelling the overlay leaves the draft unchanged.
- **If the emulator lacks speech services** (a plain AVD may — `SpeechRecognizer.isRecognitionAvailable` is false): the mic is **gracefully absent**, the composer is otherwise unchanged (attach + field + Send), and there is **no crash**. This still verifies the availability-gating + placement + that nothing regressed. (Real dictation needs a device/image with Google speech; the `appendDictation` merge logic is covered by the Task-1 unit test.)

- [ ] **Step 3: Commit (only if verification-only fixups were needed)**

```bash
git add -A
git commit -m "chore(chat): voice dictation verification fixups"   # only if needed
```

---

## Self-Review

- **Spec coverage:** pure helper → Task 1 (`appendDictation` + tests) ✓; mic button + `RecognizerIntent` launcher + availability gate + accent tint + placement + not-gated-on-connected → Task 2 ✓; on-device (incl. graceful-absent path) → Task 3 ✓. Deferred items (in-app `SpeechRecognizer`, server fallback, cursor insertion) intentionally absent, per the spec.
- **Placeholder scan:** every code step has full code; the only conditional is the Task-3 verification-only commit.
- **Type consistency:** `appendDictation(current, spoken): String` (Task 1) is called exactly that way in Task 2; `speech.launch(intent)`, `RESULT_OK`, `EXTRA_RESULTS`, `Icons.Rounded.Mic`, `AccentChrome.fabContainer` all match the spec/codebase.

**Ordering:** Task 1 (pure) first; Task 2 consumes it and touches only `ChatScreen.kt`; Task 3 verifies.
