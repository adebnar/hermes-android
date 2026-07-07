# Regenerate + Edit-and-Resend (chat) — Design

**Date:** 2026-07-07
**Status:** Approved (brainstorming) → implementation plan next
**Branch:** `feature/regenerate-edit-resend`
**Source:** improvement roadmap Phase 1 · Wave 2 (`docs/ideas/improvement-roadmap-2026-07-07.md`).

## Goal

Two chat ergonomics — **Regenerate** the last answer, and **Edit & resend** a prior user message — via a per-message action menu. **Client-only**, reusing the existing `send()`/`submit()`.

## The gateway reality (drives the design — read this)

The gateway has **no** message truncate / delete / edit / regenerate capability. The only chat send is the WS RPC `prompt.submit {session_id, text}`, which **always appends a new turn**. So true in-place edit/replace/branch is **not possible client-only**. This wave ships the honest "append" version:
- **Regenerate = re-ask.** Re-submits the last user prompt → a fresh answer appended below (the old one stays).
- **Edit & resend = reuse the prompt.** Prefills the composer with a user message's text to tweak and send as a new turn.

Both are the ergonomic 80% (re-ask without retyping; reuse-and-tweak). **True in-place edit + branching is deferred to a gateway follow-up** (a `session.rewind` / `message.delete` endpoint) — noted below.

## Hard constraints

- **No gateway/bridge API changes.** Reuse `ChatViewModel.send(text)` → `ChatRepository.submit` (`prompt.submit`). Material 3.
- Multi-tenant isolation preserved. No AI/assistant attribution in commits, files, or PRs.

## Grounding (from exploration)

- `ChatViewModel.send(text: String)` appends a client user message (`withUserMessage`) + calls `chat.submit(sessionId, text)` (or `slashExec` for `/`). `stop()`/`interrupt`. `state.isGenerating`. `ChatUiState.messages: List<ChatMessage>`.
- `ChatMessage(id, role: Role{USER,ASSISTANT,SYSTEM}, text, tools, thinking, isStreaming, isError, interrupted)` (`domain/Models.kt`). No stable server id.
- `ChatComponents.kt`: `UserBubble` + `AssistantTurn` each wrap a `Column` with `Modifier.combinedClickable(onClick = {}, onLongClick = { copyToClipboard(msg.text, …) })` — the long-press-copy is the only per-message affordance today; `onClick` is a no-op (free real estate). `copyToClipboard` + Toast.
- `draft` is `var draft by remember { mutableStateOf("") }` in `ChatScreen` (a `String`).

## Element 1 — Pure helper (`ui/chat/MessageActions.kt`, unit-tested)
```kotlin
/** The text of the most recent USER message, or null if there is none (used to re-ask). */
fun lastUserMessageText(messages: List<ChatMessage>): String? =
    messages.lastOrNull { it.role == Role.USER }?.text?.takeIf { it.isNotBlank() }
```

## Element 2 — `ChatViewModel.regenerate()`
```kotlin
/** Re-ask: re-submit the last user prompt (appends a new answer; the gateway can't replace). */
fun regenerate() {
    if (_state.value.isGenerating) return
    val prompt = lastUserMessageText(_state.value.messages) ?: return
    send(prompt)
}
```
(No new network call — `send()` already appends the user turn + streams the reply.)

## Element 3 — Per-message action menu (`ChatComponents.kt` + `ChatScreen.kt`)

Extend the long-press affordance from "copy toast" to a small **`DropdownMenu`** per bubble:
- Wrap each bubble in a `Box`; hoist `var menuFor by remember { mutableStateOf<String?>(null) }` in the message-list scope. `onLongClick = { menuFor = msg.id }` opens it (`DropdownMenu(expanded = menuFor == msg.id, onDismissRequest = { menuFor = null })`).
- **Items (contextual):**
  - **Copy** — all bubbles → `copyToClipboard(msg.text, …)` (keep the existing helper); `menuFor = null`.
  - **Edit & resend** — only `role == USER` → `onEditResend(msg.text)`; `menuFor = null`.
  - **Regenerate** — only when `msg.id == lastAssistantId` **and** `!isGenerating` → `onRegenerate()`; `menuFor = null`.
- The message list computes `val lastAssistantId = messages.lastOrNull { it.role == Role.ASSISTANT }?.id` and threads `isGenerating`, `onEditResend: (String) -> Unit`, `onRegenerate: () -> Unit` down to the bubbles.
- **`ChatScreen` wiring:**
  - `onRegenerate = { vm.regenerate() }`.
  - `onEditResend = { text -> draft = text; focusRequester.requestFocus() }` — add a `val focusRequester = remember { FocusRequester() }` on the composer `OutlinedTextField` (`Modifier.focusRequester(focusRequester)`), so tapping Edit & resend loads the text into the composer and focuses it for editing. (`draft` is replaced, not appended — this is "load this message to edit"; the rare unsent-draft case is acceptable.)

## Testing

- **Unit (pure), TDD — `MessageActionsTest`:** `lastUserMessageText` — last user's text with trailing user/assistant/system mix; null when there is no user message; skips a blank user message → null; picks the LAST user when several exist.
- **On-device:**
  1. Long-press a **user** bubble → menu shows **Copy** + **Edit & resend**; tapping Edit & resend prefills the composer with that text (editable, focused); editing + Send appends a new turn.
  2. Long-press the **last assistant** turn → menu shows **Copy** + **Regenerate**; tapping Regenerate appends a fresh answer to the same last prompt. An **older** assistant turn's menu shows only Copy (no Regenerate). Regenerate is absent while generating.
  3. Copy still works from the menu on any bubble.
  4. Tenant accent preserved.

## Not doing (YAGNI) / deferred

- **True in-place edit + branching / replacing the old answer** — needs a gateway `session.rewind` / `message.delete` endpoint (a **Phase-6 / gateway-assist follow-up**). This wave is the honest append version.
- Version arrows / multi-response navigation.
- Editing an assistant message; deleting a message.
- Any gateway/API change.
