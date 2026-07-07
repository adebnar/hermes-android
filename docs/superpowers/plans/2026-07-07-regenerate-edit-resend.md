# Regenerate + Edit-and-Resend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a per-message action menu to chat — Copy, Edit & resend (user), Regenerate (last answer) — reusing the existing `send()`.

**Architecture:** A pure `lastUserMessageText` helper + a `ChatViewModel.regenerate()` that re-submits it; a per-bubble `DropdownMenu` extends the current long-press-copy; `ChatScreen` wires Edit & resend to prefill+focus the composer. Both actions **append** (the gateway can't truncate).

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, JUnit.

## Global Constraints

- **No gateway/bridge API changes.** Reuse `send()` → `chat.submit` (`prompt.submit`); both actions append, they cannot replace/truncate. Material 3.
- Multi-tenant isolation preserved (accent unchanged).
- JDK 21: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
- Compile: `./gradlew :app:compileDebugKotlin --console=plain`. Tests: `./gradlew :app:testDebugUnitTest --console=plain`. Beta: `./gradlew :app:assembleBeta`.
- **No AI/assistant attribution** in commits, files, or PRs.

## Grounding

- `domain/Models.kt`: `data class ChatMessage(id: String, role: Role, text: String, tools, thinking, isStreaming, isError, interrupted)`; `enum class Role { USER, ASSISTANT, SYSTEM }`.
- `ui/chat/ChatViewModel.kt`: `fun send(text: String)` (appends a user message + `chat.submit`); `_state.value.isGenerating`; `_state.value.messages: List<ChatMessage>`.
- `ui/chat/ChatComponents.kt`: `UserBubble`/`AssistantTurn` each wrap a `Column` with `Modifier.combinedClickable(onClick = {}, onLongClick = { copyToClipboard(msg.text, clipboard, context) })`; `copyToClipboard(text, clipboard, context)` sets the clipboard + Toast. The message-list composable renders these per `msg`.
- `ui/chat/ChatScreen.kt`: `var draft by remember { mutableStateOf("") }`; the composer `OutlinedTextField(value = draft, …)`.
- `DropdownMenu` pattern: `CronScreen.kt`'s row overflow menu.

---

### Task 1: Pure `lastUserMessageText` (TDD)

**Files:**
- Create: `app/src/main/java/com/hermes/client/ui/chat/MessageActions.kt`
- Test: `app/src/test/java/com/hermes/client/ui/chat/MessageActionsTest.kt`

**Interfaces:** Produces `fun lastUserMessageText(messages: List<ChatMessage>): String?`.

- [ ] **Step 1: Write the failing test** — create `MessageActionsTest.kt`:

```kotlin
package com.hermes.client.ui.chat

import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageActionsTest {
    private fun m(id: String, role: Role, text: String) = ChatMessage(id = id, role = role, text = text)

    @Test fun returns_last_user_text_ignoring_trailing_assistant() {
        val msgs = listOf(
            m("u0", Role.USER, "first"),
            m("a0", Role.ASSISTANT, "answer 0"),
            m("u1", Role.USER, "second"),
            m("a1", Role.ASSISTANT, "answer 1"),
        )
        assertEquals("second", lastUserMessageText(msgs))
    }
    @Test fun null_when_no_user_message() {
        assertNull(lastUserMessageText(listOf(m("a0", Role.ASSISTANT, "hi"), m("s0", Role.SYSTEM, "sys"))))
        assertNull(lastUserMessageText(emptyList()))
    }
    @Test fun blank_last_user_returns_null() {
        assertNull(lastUserMessageText(listOf(m("u0", Role.USER, "   "))))
    }
    @Test fun picks_the_last_user_when_several() {
        val msgs = listOf(m("u0", Role.USER, "a"), m("u1", Role.USER, "b"), m("u2", Role.USER, "c"))
        assertEquals("c", lastUserMessageText(msgs))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*MessageActionsTest*' --console=plain 2>&1 | tail -6`
Expected: FAIL — unresolved `lastUserMessageText`.

- [ ] **Step 3: Implement `MessageActions.kt`**

```kotlin
package com.hermes.client.ui.chat

import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role

/** The text of the most recent USER message, or null if there is none (used to re-ask). */
fun lastUserMessageText(messages: List<ChatMessage>): String? =
    messages.lastOrNull { it.role == Role.USER }?.text?.takeIf { it.isNotBlank() }
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*MessageActionsTest*' --console=plain 2>&1 | tail -6`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/chat/MessageActions.kt app/src/test/java/com/hermes/client/ui/chat/MessageActionsTest.kt
git commit -m "feat(chat): lastUserMessageText helper for regenerate"
```

---

### Task 2: `ChatViewModel.regenerate()`

**Files:** Modify `app/src/main/java/com/hermes/client/ui/chat/ChatViewModel.kt`

**Interfaces:** Consumes `lastUserMessageText` (Task 1). Produces `fun regenerate()`.

- [ ] **Step 1: Add `regenerate()`** (near `send()`)

```kotlin
    /** Re-ask: re-submit the last user prompt (appends a new answer; the gateway can't replace). */
    fun regenerate() {
        if (_state.value.isGenerating) return
        val prompt = lastUserMessageText(_state.value.messages) ?: return
        send(prompt)
    }
```

- [ ] **Step 2: Compile + full suite**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --console=plain 2>&1 | grep -E "^e: |FAILED|BUILD" | head`
Expected: `BUILD SUCCESSFUL`, no `FAILED`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/chat/ChatViewModel.kt
git commit -m "feat(chat): regenerate() re-asks the last prompt"
```

---

### Task 3: Per-message action menu + composer wiring

**Files:** Modify `app/src/main/java/com/hermes/client/ui/chat/ChatComponents.kt`, `app/src/main/java/com/hermes/client/ui/chat/ChatScreen.kt`

**Interfaces:** Consumes `vm.regenerate()` (Task 2).

- [ ] **Step 1: Thread action params through the message list + bubbles (`ChatComponents.kt`)**

Read the file first. The message-list composable (the one that iterates `messages` and renders `UserBubble`/`AssistantTurn`) gains parameters: `isGenerating: Boolean`, `onEditResend: (String) -> Unit`, `onRegenerate: () -> Unit`. Compute once inside it: `val lastAssistantId = messages.lastOrNull { it.role == Role.ASSISTANT }?.id`. Pass down to each bubble: `isGenerating`, `lastAssistantId`, `onEditResend`, `onRegenerate` (or pass the already-resolved booleans `canRegenerate = msg.id == lastAssistantId && !isGenerating`).

- [ ] **Step 2: Replace the long-press-copy with a `DropdownMenu` on each bubble**

For **both** `UserBubble` and `AssistantTurn`, wrap the bubble `Column` in a `Box`, hoist `var menuFor by remember { mutableStateOf(false) }` (per-bubble), change `onLongClick = { menuFor = true }`, and add the menu as a sibling in the Box:
```kotlin
        androidx.compose.material3.DropdownMenu(expanded = menuFor, onDismissRequest = { menuFor = false }) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Copy") },
                onClick = { copyToClipboard(msg.text, clipboard, context); menuFor = false },
            )
            if (msg.role == Role.USER) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Edit & resend") },
                    onClick = { onEditResend(msg.text); menuFor = false },
                )
            }
            if (msg.id == lastAssistantId && !isGenerating) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Regenerate") },
                    onClick = { onRegenerate(); menuFor = false },
                )
            }
        }
```
(Keep `onClick = {}` on the bubble; only `onLongClick` changes to open the menu. `clipboard`/`context` are already in scope where `copyToClipboard` is called today; if a bubble doesn't have them, add `val context = LocalContext.current` / `val clipboard = LocalClipboardManager.current` as the existing copy call uses.)

- [ ] **Step 3: Wire the callbacks in `ChatScreen.kt`**

Read the composer. Add `val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }` and attach `Modifier.focusRequester(focusRequester)` to the composer `OutlinedTextField` (merge with its existing `Modifier.weight(1f)` etc). Pass to the message-list composable: `isGenerating = state.isGenerating`, `onRegenerate = { vm.regenerate() }`, `onEditResend = { text -> draft = text; focusRequester.requestFocus() }`.

- [ ] **Step 4: Compile + suite + assemble**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --console=plain 2>&1 | grep -E "^e: |FAILED|BUILD" | head` → `BUILD SUCCESSFUL`, no `FAILED`.
Run: `./gradlew :app:assembleBeta --console=plain 2>&1 | grep -E "^e: |BUILD" | tail -2` → `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/chat/ChatComponents.kt app/src/main/java/com/hermes/client/ui/chat/ChatScreen.kt
git commit -m "feat(chat): per-message menu — Copy / Edit & resend / Regenerate"
```

---

### Task 4: On-device verification

**Files:** none (verification only).

- [ ] **Step 1: Build + install**

`./gradlew :app:assembleBeta` then `adb -e install -r app/build/outputs/apk/beta/app-beta.apk`; point at the mock (`http://10.0.2.2:8899`) and open a chat with history (the mock's `s2` has a user + assistant turn).

- [ ] **Step 2: Verify**

1. **Long-press a user bubble** → menu with **Copy** + **Edit & resend**; tapping **Edit & resend** loads that text into the composer (focused); editing + Send appends a new turn.
2. **Long-press the last assistant turn** → menu with **Copy** + **Regenerate**; tapping **Regenerate** appends a fresh answer to the last prompt. An **older** assistant turn's menu shows **only Copy**. While generating, **Regenerate is absent**.
3. **Copy** works from the menu on any bubble.
4. Tenant accent preserved.

- [ ] **Step 3: Commit (only if verification-only fixups were needed)**

```bash
git add -A
git commit -m "chore(chat): regenerate/edit-resend verification fixups"   # only if needed
```

---

## Self-Review

- **Spec coverage:** Element 1 (`lastUserMessageText`) → Task 1 (+ tests) ✓; Element 2 (`regenerate()`) → Task 2 ✓; Element 3 (per-message menu + Edit & resend prefill/focus + Regenerate gated to last-assistant-not-generating) → Task 3 ✓; on-device → Task 4 ✓. Deferred (true in-place edit/branch, gateway follow-up) intentionally absent, per the spec.
- **Placeholder scan:** every code step shows full code; the one soft spot ("the message-list composable — read the file") is unavoidable (the exact composable name is discovered on read) but the params + menu code are fully specified; only the Task-4 verification commit is conditional.
- **Type consistency:** `lastUserMessageText(messages): String?` (Task 1) consumed by `regenerate()` (Task 2); `regenerate()` wired as `onRegenerate` (Task 3); `onEditResend: (String) -> Unit`, `isGenerating`, `lastAssistantId`, `Role.USER/ASSISTANT`, `copyToClipboard(text, clipboard, context)` all match the codebase.

**Ordering:** Task 1 (pure) → Task 2 (VM, consumes it) → Task 3 (`ChatComponents` + `ChatScreen`, consumes `regenerate`) → Task 4 verifies.
