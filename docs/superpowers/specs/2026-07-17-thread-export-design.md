# Thread Export / Share — Design

**Wave:** Quick-wins (client-only). **Branch:** `feature/thread-export` (off `dev`).

**Goal:** Let the user copy or share a whole chat conversation as a plain-text transcript, from an overflow menu in the chat top bar. Client-only; reuses the already-loaded history + existing clipboard/`ACTION_SEND` patterns.

**Constraints:** Kotlin/Compose/Material3. No AI attribution; gitleaks before push; PR into `dev`.

## Scope
- **In:** a pure `transcriptText(messages)` formatter; a `MoreVert` overflow menu in the chat top bar with **Copy transcript** (clipboard) and **Share transcript** (`ACTION_SEND` text/plain chooser).
- **Out (deferred):** file export (`.txt`/`.md` via FileProvider/`EXTRA_STREAM`); including reasoning (`thinking`)/tool output in the transcript; per-selection/range export; import.

## Architecture

### 1. `ui/chat/MessageActions.kt` (modify) — pure helper
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

### 2. `ui/chat/ChatScreen.kt` (modify) — overflow menu
In the `HermesTopBar` `actions` slot (after `StatusDot(connState)`), add a `MoreVert` `IconButton` + a `DropdownMenu` (`var transcriptMenu by remember { mutableStateOf(false) }`). Two items:
- **"Copy transcript"** → `val t = transcriptText(uiState.messages); if (t.isBlank()) toast("Nothing to export yet") else { clipboard.setText(AnnotatedString(t)); toast("Transcript copied") }; transcriptMenu = false`.
- **"Share transcript"** → `val t = transcriptText(uiState.messages); if (t.isBlank()) toast("Nothing to export yet") else context.startActivity(Intent.createChooser(Intent(ACTION_SEND).apply { type = "text/plain"; putExtra(EXTRA_SUBJECT, "Hermes chat transcript"); putExtra(EXTRA_TEXT, t) }, "Share transcript")); transcriptMenu = false`.

`uiState.messages`, `LocalContext.current` (already `val context` at line 161) are in scope; add `val clipboard = LocalClipboardManager.current`. Icon tint uses `AccentChrome.onBar` like the existing Search icon. No ViewModel/Activity changes.

## Data flow
```
chat top bar ⋮ → Copy transcript  → transcriptText(messages) → clipboard.setText + toast
              → Share transcript → transcriptText(messages) → ACTION_SEND text/plain chooser
(empty transcript → "Nothing to export yet" toast, no clipboard/chooser)
```

## Error handling
- Empty conversation / all-blank → both items toast "Nothing to export yet" and do nothing.
- `transcriptText` is pure and total (never throws).

## Testing
- **`MessageActionsTest`** (extend, pure): empty list → `""`; a USER+ASSISTANT pair → `"You:\n…\n\nAssistant:\n…"`; a blank-text turn is skipped; an `isError`/SYSTEM turn → `"Error:\n…"`; markdown in a body is preserved verbatim.
- The top-bar menu, clipboard, and share chooser are Android/Compose glue — verified on-device.

## On-device verification
Open a chat with a few turns → top-bar ⋮ → **Copy transcript** → paste elsewhere shows the role-labeled transcript; **Share transcript** → the system chooser opens with the transcript text. On an empty chat, both show "Nothing to export yet".

## Files
| Action | Path |
|--------|------|
| Modify | `ui/chat/MessageActions.kt` (`transcriptText`) + `MessageActionsTest.kt` |
| Modify | `ui/chat/ChatScreen.kt` (overflow menu) |

## Build & gates
`JAVA_HOME=…`: `:app:compileDebugKotlin`, `:app:testDebugUnitTest`, `:app:assembleBeta`. gitleaks before push; PR into `dev`.
