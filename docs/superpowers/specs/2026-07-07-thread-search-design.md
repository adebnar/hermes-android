# Search within a Thread (chat) — Design

**Date:** 2026-07-07
**Status:** Approved (brainstorming) → implementation plan next
**Branch:** `feature/thread-search`
**Source:** improvement roadmap Phase 1 · Wave 4 (final Phase-1 item).

## Goal

Find text within the open conversation and jump between matches. **Client-only** over the already-loaded `ChatUiState.messages` (no pagination, no gateway call).

## Hard constraints

- **No gateway/bridge API changes.** Material 3; per-tenant accent (`LocalProfileAccent.current.accent`). Keep it small. No AI/assistant attribution.

## Grounding (from exploration)

- Top bar is the shared `HermesTopBar(title="Chat", subtitle="Profile: X", navigationIcon, actions={ AssistChip(model) + StatusDot })`. A search icon goes in `actions`, tinted `AccentChrome.onBar`.
- `ChatMessageList(state, sessionId, listState: LazyListState = rememberLazyListState(), …)` already accepts a `listState` but `ChatScreen` doesn't pass one — it must be **lifted** into `ChatScreen` and passed in, so search can `animateScrollToItem`.
- The `LazyColumn` renders `itemsIndexed(state.messages, key={i,m->"$i:${m.id}"})` with **no header/spacer items** → message index `i` maps 1:1 to list item `i` (scroll-to-match needs no offset).
- Assistant text is markdown-rendered (`com.mikepenz.markdown.m3.Markdown(content=msg.text,…)`) → per-word highlight is hard; a **whole-bubble tint** is a trivial `Modifier` on the bubble `Column`.
- `MessageBubble(msg, canRegenerate, onEditResend, onRegenerate)` → `UserBubble`/`AssistantTurn`; a `highlighted: Boolean` must be threaded through.
- Auto-scroll-to-bottom-while-streaming runs on the same `listState` (two `LaunchedEffect`s) — it backs off when the user is browsing history, so scrolling to an earlier match is respected.

## Element 1 — Pure helper (`ui/chat/Search.kt`, unit-tested)
```kotlin
/** Indices of [messages] whose text contains [query] (case-insensitive); empty for a blank query. */
fun matchIndices(messages: List<ChatMessage>, query: String): List<Int> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    return messages.indices.filter { messages[it].text.contains(q, ignoreCase = true) }
}
```

## Element 2 — Highlight threading (`ChatComponents.kt`)
- `ChatMessageList` gains `highlightIndex: Int? = null`; in `itemsIndexed`, pass `highlighted = (index == highlightIndex)` into `MessageBubble`.
- `MessageBubble` / `UserBubble` / `AssistantTurn` gain `highlighted: Boolean = false`. When true, the bubble `Column` adds a highlight: a faint accent background + accent border, e.g.
```kotlin
val accent = LocalProfileAccent.current.accent
// user bubble already clips to its bubble shape; assistant turn uses RoundedCornerShape(12.dp)
Modifier.then(
    if (highlighted) Modifier
        .background(accent.copy(alpha = 0.12f), shape)
        .border(1.5.dp, accent, shape)
    else Modifier,
)
```
(User bubble: reuse its existing `RoundedCornerShape(20,20,6,20)`. Assistant turn: `RoundedCornerShape(12.dp)` + a little padding so the border isn't flush to text.)

## Element 3 — Search state + UI (`ChatScreen.kt`)
- **State (local, like `draft`):** `var searchOpen by rememberSaveable { mutableStateOf(false) }`; `var query by rememberSaveable { mutableStateOf("") }`; `var currentMatch by rememberSaveable { mutableStateOf(0) }`; `val listState = rememberLazyListState()` (lifted, passed to `ChatMessageList`).
- `val matches = remember(query, state.messages) { matchIndices(state.messages, query) }`.
- Reset the cursor when the match set changes: `LaunchedEffect(matches) { currentMatch = 0 }`.
- `val highlightIndex = if (searchOpen) matches.getOrNull(currentMatch) else null` → passed to `ChatMessageList`.
- Scroll on navigation: `LaunchedEffect(currentMatch, matches) { matches.getOrNull(currentMatch)?.let { listState.animateScrollToItem(it) } }`.
- **Top-bar search icon** (in `HermesTopBar` `actions`, before the `AssistChip`): `IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) query = "" }) { Icon(Icons.Rounded.Search, "Search in chat", tint = AccentChrome.onBar) }`.
- **Search Row** (rendered above `ChatMessageList` in the normal message-list view when `searchOpen`):
  `[ OutlinedTextField(query) ]  {counter}  [▲ prev]  [▼ next]  [✕ close]`
  - counter: `if (matches.isEmpty()) "0/0" else "${currentMatch + 1}/${matches.size}"`.
  - **next**: `if (matches.isNotEmpty()) currentMatch = (currentMatch + 1) % matches.size`. **prev**: `(currentMatch - 1 + matches.size) % matches.size`. Chevrons `enabled = matches.isNotEmpty()`.
  - close (✕): `searchOpen = false; query = ""`.
  - Accent-tinted chevrons/counter (`LocalProfileAccent.current.accent`).

## Testing

- **Unit (pure), TDD — `SearchTest`:** `matchIndices` — blank/whitespace query → empty; case-insensitive substring match; multiple matches return all indices in order; no match → empty; matches across user/assistant/system text.
- **On-device:**
  1. Tap the top-bar search icon → the search Row appears; type a term present in several messages → counter shows `1/N`, the first match scrolls into view and is highlighted (accent tint/border).
  2. ▼ advances to the next match (scrolls + moves highlight), ▲ goes back; wrapping at the ends; counter updates.
  3. A term with no matches → `0/0`, chevrons disabled, no highlight.
  4. ✕ closes the Row and clears the highlight; the composer/thread is otherwise unchanged.
  5. Tenant accent on the highlight + controls.

## Not doing (YAGNI) / deferred

- **Per-word highlighting inside markdown** (the renderer owns text layout) — whole-message highlight only.
- Highlighting all matches at once (only the current match is tinted); regex/whole-word; searching tool output/thinking text (matches on `message.text` only).
- Any gateway/API change or cross-session search (that already exists at the sessions/admin level).
