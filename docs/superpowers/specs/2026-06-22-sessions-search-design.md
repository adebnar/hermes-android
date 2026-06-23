# Sessions Search — Design

**Date:** 2026-06-22
**Status:** Approved (confirmed with user)

## Problem

The main Sessions screen has no search. Finding a conversation means scrolling, and the only
search (message full-text) is buried in the Session admin screen. Users want to search from
the Sessions list — both a quick title filter and a deeper message-content search.

## Decision

A search bar at the top of the Sessions screen that does **both**:
- **Title filter (instant, client-side):** as you type, the loaded session list is filtered
  by title/workspace — no round-trip.
- **Message-content search (gateway):** pressing the keyboard Search action runs the existing
  `/api/sessions/search` and shows matching sessions in a "Message matches" section above the
  list.

Reuses existing plumbing: `SessionRepository.search(query, profile)` and `SearchResultDto`.

## Design

### `SessionsViewModel`
- `query: StateFlow<String>` + `onQueryChange(q)` (clearing the query clears message results).
- `messageResults: StateFlow<List<SearchResultDto>>` + `searchMessages()` → calls
  `sessions.search(query, activeProfile)`; failures clear results.

### `SessionsScreen`
- An `OutlinedTextField` (placeholder "Search sessions…", clear button, IME action = Search)
  pinned above the list.
- The grouped/pinned list is filtered by `title`/`workspace` containing the query.
- When `messageResults` is non-empty, a "Message matches (N)" section renders first; each row
  opens its session.
- A hint when the title filter yields nothing: "No titles match — press search to search
  message text."

## Testing
- `SessionsViewModelTest`: `onQueryChange` updates query; `searchMessages` populates
  `messageResults` from the repo; a blank query clears results.

## Non-goals
- No search history, no debounced auto message-search (explicit Search action only), no
  changes to the Session admin screen.
