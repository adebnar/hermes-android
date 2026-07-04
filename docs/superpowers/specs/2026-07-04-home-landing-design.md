# Home Landing (2a) — Design

**Date:** 2026-07-04
**Status:** Approved (brainstorming) → implementation plan next
**Branch:** `feature/home-landing`
**Parent idea:** `docs/ideas/activity-home-and-cron-response.md` (Piece 2, sub-piece 2a — ship first)

## Goal

Open the app to the **Home** tab (today's Mission Control activity feed) instead of the Sessions list, and lead the bottom nav with Home — so a cold launch lands on *what's happening*, never a resumed stale surface. The "Needs you" strip is a later sub-piece (2b).

## Hard constraints

- **No bridge/gateway API changes.** Pure navigation-config change.
- Follow existing patterns (the `HermesNav` `TABS` list + `NavHost`).
- No AI/assistant attribution in commits, files, or PRs.

## What the app already provides (grounding)

- `HermesNav.kt`: `private data class Tab(route, label, icon)`; `TABS = [Tab("sessions","Chats",Chat), Tab("activity","Agent Activity",Bolt), Tab("you","You",Person)]`; `val start = if (hasConfig) "sessions" else "setup"`; a bottom `NavigationBar` rendering `TABS`; `composable("activity") { MissionControlScreen(...) }`.
- Post-setup jump (line ~126): `nav.navigate("sessions") { popUpTo("setup") { inclusive = true } }`.
- Notification deep-link: `MainActivity` reads `extra_route` → `pendingRoute` → `HermesNav`'s `LaunchedEffect(deepLinkRoute) { nav.navigate(it) }` — navigates **on top of** the start destination.
- The "New" FAB (`SessionsScreen`) uses `vm.createSession()`, already scoped to the active/selected profile — decision (f) ("New chat → last-used profile") needs no change and is out of scope here.

## Architecture / changes (all in `HermesNav.kt`)

1. **Reorder + relabel `TABS`** to lead with Home:
   ```kotlin
   private val TABS = listOf(
       Tab("activity", "Home", Icons.Rounded.Home),
       Tab("sessions", "Chats", Icons.AutoMirrored.Rounded.Chat),
       Tab("you", "You", Icons.Rounded.Person),
   )
   ```
   (Relabel `"Agent Activity"` → `"Home"`; swap the tab's `Icons.Rounded.Bolt` for `Icons.Rounded.Home`. Add the `Home` icon import.)

2. **Start destination** → land on Home when configured:
   ```kotlin
   val start = if (hasConfig) "activity" else "setup"
   ```

3. **Post-setup nav** → also land on Home after fresh setup:
   ```kotlin
   nav.navigate("activity") { popUpTo("setup") { inclusive = true } }
   ```

No change to the `MissionControlScreen` composable, the deep-link rail, `SessionsScreen`, or any repository.

## Data flow

```
cold launch (configured) → start = "activity" → Home (Mission Control feed)
notification tap → extra_route="chat/<id>" → pendingRoute → navigate on top → the session (unchanged)
setup complete → navigate("activity") → Home
bottom nav order → Home · Chats · You  (Home selected at launch)
```

## Error handling

- Not configured → `start = "setup"` (unchanged).
- The `MissionControlScreen` already handles its own loading/empty/error states for the feed; landing there adds no new failure mode.

## Testing

Navigation-config change with no new pure logic. Verified **on-device**:
- Cold launch (configured) lands on **Home** (the activity feed), with the bottom nav reading **Home · Chats · You** and **Home** selected.
- A notification tap still opens its `chat/<id>` (deep-link on top of Home).
- **Chats** tab still shows the Sessions list; its **New** FAB still creates + opens a session.
- Fresh setup completes onto **Home** (not the Sessions list).

## Not doing (YAGNI)

- The "Needs you" strip (sub-piece 2b).
- Approvals / messaging-pairing data, or any new endpoint.
- Any change to the "New" FAB / new-chat profile logic (existing behavior already satisfies decision (f)).
- Renaming the `"activity"` route id (only the visible label changes; keeping the route id avoids churn in the deep-link/nav wiring).
