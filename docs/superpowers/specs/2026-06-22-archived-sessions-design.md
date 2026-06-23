# Archived Sessions — Design

**Date:** 2026-06-22
**Status:** Approved (key decision confirmed with user)

## Problem

A session can be archived today (long-press → Archive), and archived sessions are listed in
the buried "Session admin" screen — but there is **no way to un-archive** one, and the
archived list is hard to find. Users want a clear way to view archived sessions and restore
or delete them.

## What already exists (reused, not rebuilt)

- `SessionRepository.archived(profile)` → `GET /api/sessions?archived=only` — list archived.
- `SessionRepository.archive(id, archived)` — set the flag (pass `false` to restore).
- `SessionRepository.delete(id)` — permanent delete.
- `SessionRow` long-press menu pattern (Open / Pin / Rename / Archive / Delete) in
  `SessionsScreen` — the interaction model to mirror.

## Decision

A **dedicated Archived Sessions screen**, reachable from an **action on the Sessions screen
top bar**. Each archived row supports **Open**, **Unarchive (restore)**, and **Delete**.

## Design

### Navigation
- `SessionsScreen` gains an `onOpenArchived` callback, wired in `HermesNav` to
  `nav.navigate("archived")`, surfaced as a top-bar action (label/glyph "Archived").
- New route `composable("archived")` → `ArchivedSessionsScreen(onOpen = { nav.navigate("chat/$id") }, onBack = { nav.popBackStack() })`.

### `ArchivedSessionsViewModel` (new)
- State: `sessions: List<Session>`, `loading`, `error`, `unauthorized` (mirrors
  `SessionsViewModel`).
- `init` + ON_RESUME `refresh()`; reloads on active-profile change.
- `refresh()` → `sessions.archived(profileManager.active.value)`.
- `unarchive(id)` → `sessions.archive(id, archived = false)` then `refresh()`.
- `delete(id)` → `sessions.delete(id)` then `refresh()`.

### `ArchivedSessionsScreen` (new)
- `TopAppBar("Archived")` with a back navigation icon.
- `LazyColumn` of archived sessions; empty state "No archived sessions."
- Each row: tap = Open; long-press menu = **Unarchive**, **Delete** (Delete behind a confirm
  dialog, matching the active-list pattern).
- ON_RESUME refresh so a session archived from the active list appears here on return, and a
  restore disappears from here.

### Active list
- Unchanged except the new top-bar "Archived" action. The existing long-press **Archive**
  stays as-is.

## Testing
- `ArchivedSessionsViewModelTest` (unit): `refresh` loads archived list; `unarchive` calls
  `archive(id, false)` then reloads; `delete` calls `delete(id)` then reloads.

## Non-goals
- No bulk select / multi-archive. No new REST endpoints (all exist). No change to how
  archiving is triggered from the active list.
