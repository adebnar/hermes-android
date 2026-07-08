# Search within a Thread Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Find text in the open chat thread and jump between matches, highlighting the current match.

**Architecture:** A pure `matchIndices` helper finds matching message indices; `ChatScreen` owns search state (query, cursor) and a lifted `LazyListState`, scrolls to the current match, and threads a `highlighted` flag down to the bubble. Client-only over the already-loaded `state.messages`.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, JUnit.

## Global Constraints

- **No gateway/bridge API changes** — search is client-side over `state.messages`. Material 3.
- Per-tenant accent: highlight + controls tint from `LocalProfileAccent.current.accent`; top-bar icon from `AccentChrome.onBar`.
- JDK 21: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
- Compile: `./gradlew :app:compileDebugKotlin --console=plain`. Tests: `./gradlew :app:testDebugUnitTest --console=plain`. Beta: `./gradlew :app:assembleBeta`.
- **No AI/assistant attribution** in commits, files, or PRs.

## Grounding

- `domain/Models.kt`: `ChatMessage(id: String, role: Role, text: String, …)`; `enum Role { USER, ASSISTANT, SYSTEM }`.
- `ui/chat/ChatComponents.kt`: `ChatMessageList(state, sessionId, modifier, listState: LazyListState = rememberLazyListState(), isGenerating, onEditResend, onRegenerate)` with `LazyColumn(state=listState){ itemsIndexed(state.messages, key={i,m->"$i:${m.id}"}) { _, msg -> MessageBubble(msg, canRegenerate, onEditResend, onRegenerate) } }` — **no header items** (index == message index). `MessageBubble` → `UserBubble`/`AssistantTurn`, each a `Column` with a `combinedClickable`/`clip`/`background`/`padding` chain. `LocalProfileAccent.current.accent` already used here.
- `ui/chat/ChatScreen.kt`: `HermesTopBar(title="Chat", subtitle, navigationIcon, actions={ AssistChip(...) ; StatusDot(connState) })`; `ChatScreen` does NOT currently pass a `listState` to `ChatMessageList`. `var draft by remember { mutableStateOf("") }` is the local-state precedent. `AccentChrome.onBar` used for the model chip.
- Pure-test precedent: `MessageActionsTest`.

---

### Task 1: Pure `matchIndices` (TDD)

**Files:**
- Create: `app/src/main/java/com/hermes/client/ui/chat/Search.kt`
- Test: `app/src/test/java/com/hermes/client/ui/chat/SearchTest.kt`

**Interfaces:** Produces `fun matchIndices(messages: List<ChatMessage>, query: String): List<Int>`.

- [ ] **Step 1: Write the failing test** — create `SearchTest.kt`:

```kotlin
package com.hermes.client.ui.chat

import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchTest {
    private fun m(id: String, role: Role, text: String) = ChatMessage(id = id, role = role, text = text)
    private val msgs = listOf(
        m("u0", Role.USER, "Summarize the Stripe incident"),
        m("a0", Role.ASSISTANT, "The stripe charge failed for a null customer_id"),
        m("s0", Role.SYSTEM, "📎 image attached"),
        m("u1", Role.USER, "open a PR"),
    )

    @Test fun blank_query_returns_empty() {
        assertEquals(emptyList<Int>(), matchIndices(msgs, ""))
        assertEquals(emptyList<Int>(), matchIndices(msgs, "   "))
    }
    @Test fun case_insensitive_substring() {
        assertEquals(listOf(0, 1), matchIndices(msgs, "STRIPE"))
    }
    @Test fun returns_all_matches_in_order() {
        assertEquals(listOf(0, 3), matchIndices(msgs, "p")) // "Summarize"/"Stripe"? -> both u0,a0,u1 contain 'p'
    }
    @Test fun no_match_returns_empty() {
        assertEquals(emptyList<Int>(), matchIndices(msgs, "zzz"))
    }
    @Test fun matches_system_and_assistant_text() {
        assertEquals(listOf(2), matchIndices(msgs, "attached"))
        assertEquals(listOf(1), matchIndices(msgs, "customer_id"))
    }
}
```

Note: the `returns_all_matches_in_order` expectation must match reality — compute it from the fixture. "p" appears in "Stri**p**e" (u0), "The stri**p**e char**g**e"? (a0 has "stripe" → 'p'), and "o**p**en a **P**R" (u1, case-insensitive 'p'/'P'). So the correct expectation is `listOf(0, 1, 3)`. Use that:

```kotlin
    @Test fun returns_all_matches_in_order() {
        assertEquals(listOf(0, 1, 3), matchIndices(msgs, "p"))
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*SearchTest*' --console=plain 2>&1 | tail -6`
Expected: FAIL — unresolved `matchIndices`.

- [ ] **Step 3: Implement `Search.kt`**

```kotlin
package com.hermes.client.ui.chat

import com.hermes.client.domain.ChatMessage

/** Indices of [messages] whose text contains [query] (case-insensitive); empty for a blank query. */
fun matchIndices(messages: List<ChatMessage>, query: String): List<Int> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    return messages.indices.filter { messages[it].text.contains(q, ignoreCase = true) }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*SearchTest*' --console=plain 2>&1 | tail -6`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/chat/Search.kt app/src/test/java/com/hermes/client/ui/chat/SearchTest.kt
git commit -m "feat(chat): matchIndices helper for in-thread search"
```

---

### Task 2: Highlight threading (`ChatComponents`)

**Files:** Modify `app/src/main/java/com/hermes/client/ui/chat/ChatComponents.kt`

**Interfaces:** Produces `ChatMessageList(..., highlightIndex: Int? = null)`, and `highlighted: Boolean` on `MessageBubble`/`UserBubble`/`AssistantTurn`.

- [ ] **Step 1: Add `highlightIndex` to `ChatMessageList` + pass `highlighted` down**

Add `highlightIndex: Int? = null` to the `ChatMessageList` parameter list. In its `itemsIndexed(state.messages, …) { index, msg -> … }`, pass the flag:
```kotlin
        MessageBubble(msg, canRegenerate, onEditResend, onRegenerate, highlighted = index == highlightIndex)
```

- [ ] **Step 2: Thread `highlighted` through `MessageBubble` → bubbles**

Add `highlighted: Boolean = false` to `MessageBubble(...)`, `UserBubble(...)`, and `AssistantTurn(...)`, forwarding it from `MessageBubble` into whichever bubble it renders.

- [ ] **Step 3: Apply the highlight modifier on each bubble Column**

Read the two bubble `Column` modifier chains first. For the **user bubble**, add — after its existing `.background(bg)` (reusing its shape) — a highlight overlay when `highlighted`:
```kotlin
        val accent = LocalProfileAccent.current.accent
        val userShape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .clip(userShape)
                .background(bg)
                .then(if (highlighted) Modifier.background(accent.copy(alpha = 0.18f)).border(1.5.dp, accent, userShape) else Modifier)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .combinedClickable(onClick = {}, onLongClick = { menuOpen = true }),
        ) { … }
```
For the **assistant turn** (full-width, no bubble shape today), wrap its content highlight with a rounded shape:
```kotlin
        val accent = LocalProfileAccent.current.accent
        val hlShape = RoundedCornerShape(12.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (highlighted) Modifier.clip(hlShape).background(accent.copy(alpha = 0.12f)).border(1.5.dp, accent, hlShape).padding(6.dp) else Modifier)
                .padding(vertical = 2.dp)
                .combinedClickable(onClick = {}, onLongClick = { menuOpen = true }),
        ) { … }
```
Add imports if missing: `androidx.compose.foundation.border`, `androidx.compose.foundation.background`, `androidx.compose.foundation.shape.RoundedCornerShape`, `androidx.compose.ui.draw.clip`, `com.hermes.client.ui.theme.LocalProfileAccent` (check which are already imported).

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileDebugKotlin --console=plain 2>&1 | grep -E "^e: |BUILD" | head`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/chat/ChatComponents.kt
git commit -m "feat(chat): whole-message highlight for the active search match"
```

---

### Task 3: Search state + top-bar toggle + search Row (`ChatScreen`)

**Files:** Modify `app/src/main/java/com/hermes/client/ui/chat/ChatScreen.kt`

**Interfaces:** Consumes `matchIndices` (Task 1), `ChatMessageList(highlightIndex=…, listState=…)` (Task 2).

- [ ] **Step 1: Add search state + the lifted list state**

Near `draft`, add:
```kotlin
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var currentMatch by rememberSaveable { mutableStateOf(0) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val matches = remember(query, state.messages) { matchIndices(state.messages, query) }
    LaunchedEffect(matches) { currentMatch = 0 }
    val highlightIndex = if (searchOpen) matches.getOrNull(currentMatch) else null
    LaunchedEffect(currentMatch, matches) {
        matches.getOrNull(currentMatch)?.let { listState.animateScrollToItem(it) }
    }
```

- [ ] **Step 2: Add the top-bar search icon**

In the `HermesTopBar` `actions = { … }` block, **before** the `AssistChip`, add:
```kotlin
            IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) query = "" }) {
                Icon(
                    androidx.compose.material.icons.Icons.Rounded.Search,
                    contentDescription = "Search in chat",
                    tint = com.hermes.client.ui.components.AccentChrome.onBar,
                )
            }
```

- [ ] **Step 3: Pass `listState` + `highlightIndex` to `ChatMessageList`**

Find the `ChatMessageList(...)` call (the normal message-list view) and add `listState = listState` and `highlightIndex = highlightIndex` to its arguments.

- [ ] **Step 4: Render the search Row above the message list**

In the normal message-list branch, wrap the `ChatMessageList(...)` in a `Column` and render the search Row above it when `searchOpen`:
```kotlin
            Column(Modifier.fillMaxSize()) {
                if (searchOpen) {
                    val accent = LocalProfileAccent.current.accent
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Search in chat…") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (matches.isEmpty()) "0/0" else "${currentMatch + 1}/${matches.size}",
                            color = accent,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                        IconButton(
                            onClick = { if (matches.isNotEmpty()) currentMatch = (currentMatch - 1 + matches.size) % matches.size },
                            enabled = matches.isNotEmpty(),
                        ) { Icon(androidx.compose.material.icons.Icons.Rounded.KeyboardArrowUp, contentDescription = "Previous match", tint = accent) }
                        IconButton(
                            onClick = { if (matches.isNotEmpty()) currentMatch = (currentMatch + 1) % matches.size },
                            enabled = matches.isNotEmpty(),
                        ) { Icon(androidx.compose.material.icons.Icons.Rounded.KeyboardArrowDown, contentDescription = "Next match", tint = accent) }
                        IconButton(onClick = { searchOpen = false; query = "" }) {
                            Icon(androidx.compose.material.icons.Icons.Rounded.Close, contentDescription = "Close search")
                        }
                    }
                }
                ChatMessageList(
                    state = state,
                    sessionId = sessionId,
                    listState = listState,
                    highlightIndex = highlightIndex,
                    isGenerating = state.isGenerating,
                    onEditResend = { text -> draft = text; focusRequester.requestFocus() },
                    onRegenerate = { vm.regenerate() },
                    modifier = Modifier.weight(1f),
                )
            }
```
(Match the actual existing `ChatMessageList(...)` argument names/values — copy them from the current call; only ADD `listState`, `highlightIndex`, and wrap in the `Column` + search Row. If the current call already sets `modifier`, keep it; `Modifier.weight(1f)` makes the list fill the space under the Row.)
Add imports if missing: `androidx.compose.material.icons.rounded.Search`, `androidx.compose.material.icons.rounded.KeyboardArrowUp`, `androidx.compose.material.icons.rounded.KeyboardArrowDown`, `androidx.compose.material.icons.rounded.Close`, `androidx.compose.foundation.lazy.rememberLazyListState`, `androidx.compose.foundation.layout.Column`.

- [ ] **Step 5: Compile + suite + assemble**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --console=plain 2>&1 | grep -E "^e: |FAILED|BUILD" | head` → `BUILD SUCCESSFUL`, no `FAILED`.
Run: `./gradlew :app:assembleBeta --console=plain 2>&1 | grep -E "^e: |BUILD" | tail -2` → `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/chat/ChatScreen.kt
git commit -m "feat(chat): in-thread search — toggle, match counter, jump + highlight"
```

---

### Task 4: On-device verification

**Files:** none (verification only).

- [ ] **Step 1: Build + install**

`./gradlew :app:assembleBeta` then `adb -e install -r app/build/outputs/apk/beta/app-beta.apk`; point at the mock (`http://10.0.2.2:8899`) and open a chat with several messages (mock `s2`).

- [ ] **Step 2: Verify**

1. Tap the top-bar **search icon** → the search Row appears.
2. Type a term present in multiple messages → counter shows `1/N`, the first match scrolls into view with an **accent highlight** (tint + border).
3. **▼** advances to the next match (scrolls + moves the highlight), **▲** goes back, wrapping at the ends; the counter updates.
4. A term with **no matches** → `0/0`, chevrons disabled, no highlight.
5. **✕** closes the Row and clears the highlight; composer + thread otherwise unchanged.
6. Tenant accent on the highlight + counter + chevrons.

- [ ] **Step 3: Commit (only if verification-only fixups were needed)**

```bash
git add -A
git commit -m "chore(chat): thread-search verification fixups"   # only if needed
```

---

## Self-Review

- **Spec coverage:** Element 1 (`matchIndices`) → Task 1 (+ tests) ✓; Element 2 (highlight threading) → Task 2 ✓; Element 3 (state + top-bar toggle + search Row + lifted listState + scroll) → Task 3 ✓; on-device → Task 4 ✓. Deferred (per-word markdown highlight, highlight-all) intentionally absent.
- **Placeholder scan:** every code step has full code; the `returns_all_matches_in_order` expectation is pinned to `listOf(0, 1, 3)` (computed from the fixture); the only "read the file / match existing args" note is Task 3 Step 4 (the exact `ChatMessageList(...)` argument list is discovered on read) with the additions fully specified.
- **Type consistency:** `matchIndices(messages, query): List<Int>` (Task 1) → consumed in Task 3; `ChatMessageList(highlightIndex, listState)` + `highlighted: Boolean` (Task 2) → set in Task 3; `LocalProfileAccent.current.accent`, `AccentChrome.onBar`, `Icons.Rounded.{Search,KeyboardArrowUp,KeyboardArrowDown,Close}` consistent throughout.

**Ordering:** Task 1 pure → Task 2 highlight threading → Task 3 ChatScreen wiring → Task 4 verify.
