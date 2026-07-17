# Thread Export / Share Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Checkbox steps.

**Goal:** Copy or share a chat as a plain-text transcript via a chat top-bar overflow menu.

**Spec:** `docs/superpowers/specs/2026-07-17-thread-export-design.md`

## Global Constraints
- Client-only; reuse loaded history + existing clipboard/`ACTION_SEND` patterns. No AI attribution.
- Build env: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
- Branch `feature/thread-export` (off `dev`).

---

### Task 1: `transcriptText` pure helper

**Files:** Modify `app/src/main/java/com/hermes/client/ui/chat/MessageActions.kt`; Test `app/src/test/java/com/hermes/client/ui/chat/MessageActionsTest.kt` (extend if it exists, else create)

- [ ] **Step 1: Write the failing test.** Add to (or create) `MessageActionsTest.kt`:
```kotlin
package com.hermes.client.ui.chat

import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageActionsTranscriptTest {
    private fun msg(role: Role, text: String, isError: Boolean = false) =
        ChatMessage(id = "x", role = role, text = text, isError = isError)

    @Test fun empty_is_empty() {
        assertEquals("", transcriptText(emptyList()))
    }

    @Test fun labels_user_and_assistant() {
        val t = transcriptText(listOf(msg(Role.USER, "hi"), msg(Role.ASSISTANT, "hello")))
        assertEquals("You:\nhi\n\nAssistant:\nhello", t)
    }

    @Test fun skips_blank_turns() {
        val t = transcriptText(listOf(msg(Role.USER, "q"), msg(Role.ASSISTANT, "   "), msg(Role.ASSISTANT, "a")))
        assertEquals("You:\nq\n\nAssistant:\na", t)
    }

    @Test fun error_and_system_labelled_error() {
        assertTrue(transcriptText(listOf(msg(Role.ASSISTANT, "boom", isError = true))).startsWith("Error:"))
        assertTrue(transcriptText(listOf(msg(Role.SYSTEM, "sys"))).startsWith("Error:"))
    }

    @Test fun markdown_preserved_verbatim() {
        val t = transcriptText(listOf(msg(Role.ASSISTANT, "```kotlin\nval x = 1\n```")))
        assertTrue(t.contains("```kotlin"))
        assertTrue(t.contains("val x = 1"))
    }
}
```
(If `MessageActionsTest.kt` already exists, add these as a new class in the same file or a new file `MessageActionsTranscriptTest.kt` — either is fine; keep the existing tests intact.)

- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest --tests "com.hermes.client.ui.chat.MessageActionsTranscriptTest"` → FAIL (unresolved `transcriptText`).

- [ ] **Step 3: Implement** — append to `MessageActions.kt` (it already imports `ChatMessage` and `Role`):
```kotlin
/**
 * Render the conversation to a plain-text, role-labeled transcript. Body text is verbatim
 * (markdown preserved). Blank-text turns (tool-only / still-streaming stubs) are skipped.
 */
fun transcriptText(messages: List<ChatMessage>): String =
    messages
        .filter { it.text.isNotBlank() }
        .joinToString("\n\n") { m ->
            val label = when {
                m.isError || m.role == Role.SYSTEM -> "Error"
                m.role == Role.USER -> "You"
                else -> "Assistant"
            }
            "$label:\n${m.text}"
        }
```

- [ ] **Step 4:** Run the test → PASS (5 tests).

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/hermes/client/ui/chat/MessageActions.kt \
        app/src/test/java/com/hermes/client/ui/chat/MessageActionsTranscriptTest.kt
git commit -m "feat: add transcriptText conversation formatter"
```
(Adjust the `git add` test path if you extended the existing `MessageActionsTest.kt` instead.)

---

### Task 2: Chat top-bar overflow menu

**Files:** Modify `app/src/main/java/com/hermes/client/ui/chat/ChatScreen.kt`

**Interfaces:** Consumes `transcriptText` (Task 1).

- [ ] **Step 1: Add imports + state.** Ensure these imports exist in `ChatScreen.kt` (add any missing):
```kotlin
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
```
Near the other composer/UI state (e.g. by `val context = LocalContext.current` at line ~161), add:
```kotlin
    val clipboard = LocalClipboardManager.current
    var transcriptMenu by remember { mutableStateOf(false) }
```

- [ ] **Step 2: Add the overflow to the top-bar `actions`.** In the `HermesTopBar(...) { actions = { … } }` block, after `StatusDot(connState)`, add:
```kotlin
                    Box {
                        IconButton(onClick = { transcriptMenu = true }) {
                            Icon(
                                Icons.Rounded.MoreVert,
                                contentDescription = "More",
                                tint = com.hermes.client.ui.components.AccentChrome.onBar,
                            )
                        }
                        DropdownMenu(expanded = transcriptMenu, onDismissRequest = { transcriptMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Copy transcript") },
                                onClick = {
                                    val t = transcriptText(state.messages)
                                    if (t.isBlank()) {
                                        android.widget.Toast.makeText(context, "Nothing to export yet", android.widget.Toast.LENGTH_SHORT).show()
                                    } else {
                                        clipboard.setText(AnnotatedString(t))
                                        android.widget.Toast.makeText(context, "Transcript copied", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                    transcriptMenu = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Share transcript") },
                                onClick = {
                                    val t = transcriptText(state.messages)
                                    if (t.isBlank()) {
                                        android.widget.Toast.makeText(context, "Nothing to export yet", android.widget.Toast.LENGTH_SHORT).show()
                                    } else {
                                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(android.content.Intent.EXTRA_SUBJECT, "Hermes chat transcript")
                                            putExtra(android.content.Intent.EXTRA_TEXT, t)
                                        }
                                        context.startActivity(android.content.Intent.createChooser(send, "Share transcript"))
                                    }
                                    transcriptMenu = false
                                },
                            )
                        }
                    }
```
NOTE: use the actual name of the chat UI-state variable in this file for `state.messages` — read the file to confirm whether it's `state`, `uiState`, or similar (the same variable already rendered by `ChatMessageList(...)`). `DropdownMenu`/`DropdownMenuItem` are already imported (lines 47-48). If `IconButton`/`Icon`/`Box`/`Text`/`remember`/`mutableStateOf` aren't imported, add them (they're standard and almost certainly already present).

- [ ] **Step 3: Compile + full suite + assembleBeta.** Run each (JAVA_HOME set): `:app:compileDebugKotlin`, `:app:testDebugUnitTest` (0 failures), `:app:assembleBeta` — all BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**
```bash
git add app/src/main/java/com/hermes/client/ui/chat/ChatScreen.kt
git commit -m "feat: Copy/Share transcript overflow menu in the chat top bar"
```

---

### Task 3: On-device verification

- [ ] **Step 1:** `:app:installBeta` (target `emulator-5554` if multiple devices). The app should be configured/connected; open a chat that has a few turns (or create one and send a message).
- [ ] **Step 2:** Chat top bar → ⋮ → **Copy transcript** → confirm a "Transcript copied" toast; paste into another field/app and confirm the role-labeled transcript ("You:" / "Assistant:").
- [ ] **Step 3:** ⋮ → **Share transcript** → confirm the system share chooser opens with the transcript as text.
- [ ] **Step 4:** On a brand-new empty chat, ⋮ → either item → confirm "Nothing to export yet" and no chooser/clipboard change.
- [ ] **Step 5:** Record pass/fail in the PR description.

---

## Notes for the executor
- Do NOT add file export (FileProvider/.txt), include `thinking`/tool output, or add ViewModel/Activity changes — explicit anti-scope. Everything is wired inline in `ChatScreen`.
- Confirm the in-file chat-state variable name before using `state.messages`.
