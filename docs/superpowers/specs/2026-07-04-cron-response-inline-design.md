# Cron Response Inline (Agent Activity) — Design

**Date:** 2026-07-04
**Status:** Approved (brainstorming) → implementation plan next
**Branch:** `feature/cron-response-inline`
**Parent idea:** `docs/ideas/activity-home-and-cron-response.md` (Piece 1 — ship first)

## Goal

In the Agent Activity (Mission Control) feed, tapping a **cron run** expands its **response** — the run's final assistant message — **inline**, so you see what the scheduled job answered without opening the full transcript. A "View full chat" link inside the expanded card still opens the complete session. **No gateway/bridge changes.**

## Interaction (decided: response-first)

- Tapping a **cron-run row** expands/collapses its response inline (response-first — the response is the primary thing).
- The expanded card carries a **"View full chat"** affordance that navigates to the full transcript (`chat/<id>`, today's behavior).
- **Only cron runs expand.** Conversation rows and **upcoming** cron next-runs keep today's behavior (tap → navigate to their `route`).

## Hard constraints

- **No bridge/gateway API changes.** Reuse `SessionRepository.history(sessionId, profile)` (REST `GET /api/sessions/<id>/messages`).
- Follow existing patterns: pure unit-tested logic (like `ActivityModels`/`sessionsToActivity`), Hilt VM, Material3 `ListItem` feed rows.
- No AI/assistant attribution in commits, files, or PRs.

## What the app already provides (grounding)

- `MissionControlViewModel` (Hilt): injects `SessionRepository sessions`, `ToolsRepository tools`, `ProfileManager`; exposes `state: StateFlow<MissionControlState>` of `ActivitySection`/`ActivityItem`; `load/refresh`, and `switchTo(profile)`.
- `ActivityItem(id, kind: CONVERSATION|CRON, title, subtitle, timestampMs, upcoming, status, route)`. Cron **runs** come from `sessionsToActivity` (source="cron" sessions → `kind=CRON`, `route="chat/<id>"`, `upcoming=false`); cron **next-runs** come from `cronsToActivity` (`upcoming=true`, `route="cron_detail/<id>"`).
- `SessionRepository.history(sessionId, profile): List<ChatMessage>` — REST, no socket.
- `ChatMessage(id, role: USER|ASSISTANT|SYSTEM, text, tools: List<ToolCall>, thinking, …)`; `ToolCall(id, name, status, output)`.
- `MissionControlScreen`: `LazyColumn` → `items(section.items){ ActivityRow(item, nowMs, onClick = { onOpen(item.route) }) }`; `onOpen(route)` awaits `vm.switchTo(profile)` then `onNavigate(route)`. `ActivityRow` is a Material3 `ListItem`.

## Architecture

Add a small pure extractor, a lazy per-run response loader on the VM, an `ActivityItem.sessionId`, and inline expansion in the feed row. Each unit is independently testable.

### Components

1. **Pure `cronResponse(messages: List<ChatMessage>): String`** — `app/src/main/java/com/hermes/client/ui/activity/CronResponse.kt`. The unit-tested core.
   - Last message with `role == Role.ASSISTANT && text.isNotBlank()` → its `text.trim()`.
   - Else the last message that has a tool with non-blank `output` → `"${tool.name}: ${tool.output.trim()}"` truncated to a summary length (`MAX = 500` chars, ellipsis if longer).
   - Else `"No text output."`.

2. **`ActivityItem.sessionId: String? = null`** — `ActivityModels.kt`. Set to the raw session id in `sessionsToActivity` (`sessionId = s.id`). Left null by `cronsToActivity`. Expansion is offered only when `kind == ActivityKind.CRON && sessionId != null && !upcoming`.

3. **`MissionControlViewModel` — lazy response loader.**
   - `data class CronResponseUi(val loading: Boolean = false, val text: String? = null, val error: Boolean = false)`.
   - `private val _responses = MutableStateFlow<Map<String, CronResponseUi>>(emptyMap())`; `val responses: StateFlow<Map<String, CronResponseUi>>` keyed by **sessionId**.
   - `fun loadResponse(sessionId: String)`: if the map already has a non-loading success/entry for it, return (cache). Else set `loading=true`; `viewModelScope.launch { runCatching { sessions.history(sessionId, profile) }.onSuccess { put(sessionId, CronResponseUi(text = cronResponse(it))) }.onFailure { put(sessionId, CronResponseUi(error = true)) } }`. A retry clears the errored entry and re-calls.
   - Uses the VM's existing `profile` field; no profile switch needed to *read* history (the REST call takes the profile param), but the "View full chat" navigation reuses the existing `onOpen`/`switchTo` path.

4. **`MissionControlScreen` — inline expansion.**
   - Expansion state: `val expanded = remember { mutableStateMapOf<String, Boolean>() }` in `MissionControlPage`, keyed by `ActivityItem.id`. Collect `vm.responses` as state.
   - `ActivityRow` gains: `expandable: Boolean`, `isExpanded: Boolean`, `response: CronResponseUi?`, `onToggle: () -> Unit`, `onOpenFull: () -> Unit`. For an expandable item, the row's `onClick` calls `onToggle` (and on first expand, `vm.loadResponse(sessionId)`); a trailing chevron rotates with state. Non-expandable rows keep `onClick = onOpen(route)`.
   - Expanded content (below the row): `loading` → a small `CircularProgressIndicator`; `error` → "Couldn't load response" + a "Retry" text button; else the response `text` in a card (selectable, capped height with internal scroll), followed by a **"View full chat"** text button → `onOpen(item.route)`.

### Data flow

```
tap cron-run row → toggle expanded[item.id]
   on first expand → vm.loadResponse(sessionId)
      → sessions.history(sessionId, profile)  [REST]
      → cronResponse(messages)  [pure]
      → _responses[sessionId] = CronResponseUi(text=…)   (or error=true)
   screen renders responses[sessionId]: spinner | error+retry | text
"View full chat" → onOpen("chat/<id>")  → switchTo(profile) + navigate  (unchanged)
```

## Error handling

- **History fetch fails:** `CronResponseUi(error = true)` → inline "Couldn't load response" + Retry (clears the entry and re-calls). The feed itself is unaffected.
- **Empty / non-text run:** the fallback chain yields the tool-result summary or "No text output." — never a blank card.
- **Rapid expand/collapse:** the response is cached by sessionId, so re-expanding doesn't refetch; an in-flight load isn't re-launched (guard on `loading`).
- **Profile:** history is read with the VM's `profile` param; opening the full chat still routes through the existing `switchTo` so the chat screen acts on the right per-profile DB.

## Testing

- **Unit (pure), TDD — `CronResponseTest`:** assistant-text case (picks the **last** assistant message when several); tool-only fallback (`"name: output"`, and truncation past `MAX`); empty list → "No text output."; a run whose only assistant messages are blank → falls through to tool/none.
- **Unit — `MissionControlViewModel` response loader:** `loadResponse` sets `loading` then `text` on success and `error` on failure (mock `SessionRepository.history`); a second `loadResponse` for the same sessionId does **not** refetch (verify one `history` call).
- **On-device:** in Agent Activity, tap a cron run → response expands inline (spinner → text); "View full chat" opens the transcript; tapping a conversation or an upcoming cron next-run navigates as before; a failing history fetch shows the error + Retry.

## Not doing (YAGNI)

- Expanding **conversation** rows or **upcoming** cron next-runs (cron runs only).
- Threading the Product/Technical toggle through this view — the response is the assistant's **text**, inherently payload-free; the toggle still governs the full transcript.
- Pre-fetching responses for the whole feed (lazy on expand only).
- Streaming/live updates of a running cron's partial output (show the stored final message).
- Any new gateway endpoint.

## Open questions (non-blocking; plan may settle)

- Exact truncation length for the tool-result fallback (`MAX = 500` proposed) and response card max height before internal scroll.
