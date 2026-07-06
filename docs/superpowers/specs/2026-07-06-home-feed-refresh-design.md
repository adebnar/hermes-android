# Home Feed Refresh — Design

**Date:** 2026-07-06
**Status:** Approved (brainstorming) → implementation plan next
**Branch:** `feature/home-feed-refresh`
**Source:** user reference mockup (segmented tabs + collapsible counted sections + status pills), adapted to the Mission Control / Home feed.

## Goal

Bring a task-manager mockup's structure into Home: **segmented time-filter tabs**, **collapsible counted sections**, and **status pills** on rows — reskinning/refiltering *on top of* the existing feed (its data/logic are untouched). Keep the per-tenant accent as the identity/safety signal (not the mockup's gold). Compose-only, no gateway changes.

## Hard constraints

- **No gateway/bridge API changes.** Compose UI over existing `MissionControlState` only. Material 3.
- Multi-tenant isolation: all new chrome tints to `LocalProfileAccent.current` (per-tenant accent), never a hardcoded colour.
- No AI/assistant attribution in commits, files, or PRs.

## Grounding (from exploration)

- `MissionControlScreen`: `Scaffold` topBar = `Column { HermesTopBar("Home"); if (names.size>1) ProfileSwitcher(...) }` (shared chrome); a `HorizontalPager` of `MissionControlPage(profile)`. Each page hoists `expandedIds` (`rememberSaveable`) and renders `MissionControlContent` (a `LazyColumn`: needs-you header+rows → `state.sections.forEach { header + items }` → quicklinks footer).
- `MissionControlState(sections: List<ActivitySection>, needsYou: List<CronAlert>, loading, error, unauthorized)`. `ActivitySection(title, items)`, titles ∈ {"Live now","Upcoming","Recent"}. `ActivityItem(id, kind, title, subtitle, timestampMs: Long?, upcoming: Boolean, status: String?, route, sessionId)` — **`status` is always null today** (pills must derive from `upcoming` + the live window, not a status string).
- `LIVE_WINDOW_MS = 15*60_000`. `SingleChoiceSegmentedButtonRow`/`SegmentedButton` already used in `AppearanceScreen`/`ModelSelector` (the tab primitive). `rememberSaveable` id-set + toggle is the collapse pattern. `relativeTime`/`isoToEpochMs` in `ui/util/Time.kt`; **no** day-boundary helper exists.

## Layout decision

The tabs render as **per-page fixed chrome inside `MissionControlPage`**, above the content `LazyColumn` (so each profile's tab **counts** reflect that page's own feed, and the tabs stay visible while the feed scrolls). Visual stack: status bar → `HermesTopBar("Home")` → `ProfileSwitcher` (shared) → **FeedTabs** (per page) → feed. This is one more chrome row than wave-3's lifted hero — an accepted, mockup-faithful trade. (Collapsing the switcher into a top-bar avatar-dropdown is a cleaner but bigger restructure — **deferred**.)

## Element 1 — Segmented time-filter tabs

`All / Today / Upcoming / Recent`, each with a count, tinted to the active tenant accent.

- **Pure model (testable), in `ActivityModels.kt`:**
```kotlin
enum class FeedFilter { ALL, TODAY, UPCOMING, RECENT }

data class FeedView(val needsYou: List<CronAlert>, val sections: List<ActivitySection>) {
    val count: Int get() = needsYou.size + sections.sumOf { it.items.size }
}

fun feedView(
    sections: List<ActivitySection>,
    needsYou: List<CronAlert>,
    nowMs: Long,
    filter: FeedFilter,
): FeedView = when (filter) {
    FeedFilter.ALL -> FeedView(needsYou, sections)
    FeedFilter.TODAY -> FeedView(
        needsYou,
        sections.map { s -> s.copy(items = s.items.filter { it.timestampMs != null && isSameDay(it.timestampMs, nowMs) }) }
            .filter { it.items.isNotEmpty() },
    )
    FeedFilter.UPCOMING -> FeedView(emptyList(), sections.filter { it.title.equals("Upcoming", ignoreCase = true) })
    FeedFilter.RECENT -> FeedView(emptyList(), sections.filterNot { it.title.equals("Upcoming", ignoreCase = true) })
}
```
  - **ALL** = everything. **TODAY** = Needs-you (always — they need you now) + items dated today. **UPCOMING** = the "Upcoming" section only (scheduled). **RECENT** = the non-Upcoming sections ("Live now" + "Recent"). Empty sections drop out.
- **New day helper, in `ui/util/Time.kt`:**
```kotlin
fun isSameDay(aMs: Long, bMs: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Boolean =
    java.time.Instant.ofEpochMilli(aMs).atZone(zone).toLocalDate() ==
        java.time.Instant.ofEpochMilli(bMs).atZone(zone).toLocalDate()
```
- **`FeedTabs` composable** (new, `ui/activity/FeedTabs.kt`): a `SingleChoiceSegmentedButtonRow` of the 4 filters; each `SegmentedButton(selected = filter == it, onClick = { onSelect(it) }, shape = SegmentedButtonDefaults.itemShape(index, 4), colors = SegmentedButtonDefaults.colors(activeContainerColor = LocalProfileAccent.current.accent, activeContentColor = LocalProfileAccent.current.onAccent))` with a label `"<Name> <count>"` (count computed via `feedView(...).count` per filter). Compact (fits one row).
- **Wiring:** `MissionControlPage` gains `var filter by rememberSaveable { mutableStateOf(FeedFilter.ALL) }`; renders `Column { FeedTabs(state, filter, onSelect = { filter = it }); MissionControlContent(view = feedView(state.sections, state.needsYou, now, filter), …) }`. `MissionControlContent` renders from the filtered `FeedView` (its `needsYou`/`sections`) instead of raw `state` for the feed body (loading/error/empty branches still read `state`).

## Element 2 — Collapsible sections

- `SectionHeader(label, count, collapsed: Boolean, onToggle: () -> Unit)` — the whole header is `clickable(onClick = onToggle)`; trailing icon `if (collapsed) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess` (⌄ collapsed / ⌃ expanded), `contentDescription` = "Expand <label>" / "Collapse <label>". **The header→Cron deep-link is removed** (Cron stays reachable via the quicklinks footer + any cron row).
- **State:** `MissionControlPage` hoists `var collapsed by rememberSaveable { mutableStateOf(emptySet<String>()) }` (default all expanded); `onToggleSection(title) = { collapsed = if (title in collapsed) collapsed - title else collapsed + title }`. In `MissionControlContent`, the needs-you header and each `section` header pass `collapsed = title in collapsed`; the `items(...)` block for that section/needs-you renders **only when not collapsed**. (Keys: needs-you uses `"Needs you"`; sections use `section.title`.)

## Element 3 — Status pills

- **Pure helper, in `ActivityModels.kt`:**
```kotlin
/** Short state label for an activity row, or null (plain recent items get no pill). */
fun statusPill(item: ActivityItem, nowMs: Long): String? = when {
    item.upcoming -> "Scheduled"
    item.timestampMs != null && (nowMs - item.timestampMs) in 0..LIVE_WINDOW_MS -> "Live"
    else -> null
}
```
- **`ActivityRow`:** compute `val pill = statusPill(item, nowMs)`. `trailingContent` becomes: if `pill != null || expandable`, a `Row(verticalAlignment = CenterVertically) { pill?.let { StatusPill(it) }; if (expandable) Icon(ExpandMore/ExpandLess…) }`; else `null`. `StatusPill(label)` = a small `Surface(shape = RoundedCornerShape(50), color = LocalProfileAccent.current.container) { Text(label, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = labelSmall, color = LocalProfileAccent.current.onContainer) }`.
- **Needs-you rows unchanged** (they already carry "Failed/Overdue" + "Run now"). **Left time-rail deferred** (relative time is already in the subtitle).

## Testing

- **Unit (pure), TDD:**
  - `isSameDay`: same-calendar-day (fixed zone) → true; different day → false; across a day boundary → false.
  - `feedView`: ALL returns all; TODAY keeps needsYou + only today's items (drops empty sections); UPCOMING = only the "Upcoming" section, no needsYou; RECENT = non-Upcoming sections, no needsYou; `count` sums correctly.
  - `statusPill`: upcoming → "Scheduled"; within live window → "Live"; older → null.
- **On-device:**
  1. Home shows the `All/Today/Upcoming/Recent` tabs (accent-tinted) under the switcher, with counts; selecting a tab filters the feed; counts reflect the active profile.
  2. Tapping a section header collapses/expands it (⌃/⌄), and the state survives scroll/rotation.
  3. Rows show a "Scheduled"/"Live" pill where applicable; recent chats show none; Needs-you rows still show Run-now.
  4. Everything stays tenant-accent tinted (green acme / gold globex); swiping profiles keeps each page's tab + collapse state.

## Not doing (YAGNI)

- Left time-rail on rows (deferred).
- Collapsing the switcher into a top-bar avatar-dropdown (deferred).
- Threading real cron `lastStatus` onto `ActivityItem.status` for richer pills (the derive-from-`upcoming`/live-window approach is honest to current data; failed crons already surface in Needs-you).
- "Priority High/Medium" tags and a literal "Completed" state (no clean mapping; Hermes isn't a to-do app).
- Any gateway/API change.
