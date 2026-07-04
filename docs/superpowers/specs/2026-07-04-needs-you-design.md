# "Needs you" strip (2b) + Home header — Design

**Date:** 2026-07-04
**Status:** Approved (brainstorming) → implementation plan next
**Branch:** `feature/needs-you`
**Parent idea:** `docs/ideas/activity-home-and-cron-response.md` (Piece 2, sub-piece 2b)

## Goal

Add a compact **"Needs you"** strip to the top of the Home (Mission Control) feed that surfaces cron jobs needing attention — **failed** or **overdue** — for the current profile, and rename the Home screen's header from "Agent Activity" to **"Home"**. **No gateway/bridge changes** (reuses the cron list the feed already fetches).

## Decided

- "Needs you" flags **failed + overdue** cron (per the user): failed = last run errored; overdue = an enabled, non-paused job whose next run is >5 min past due.
- **Cron-only** (messaging `/api/pairing` isn't wired in the app; approvals are WS-only) — established in the idea doc.
- **Per-profile** (matches the existing per-profile feed pager).
- Auto-refresh on resume is **already provided** by the feed's `LifecycleEventEffect(ON_RESUME) { vm.refresh() }`.

## Hard constraints

- **No bridge/gateway API changes.** Reuse `ToolsRepository.cronJobs(profile)` (already called in `loadInner`).
- Follow existing patterns: pure unit-tested logic (like `cronsToActivity`), `MissionControlState`, Material3 rows.
- No AI/assistant attribution in commits, files, or PRs.

## What the app already provides (grounding)

- `MissionControlViewModel.loadInner`: fetches `val crons = runCatching { tools.cronJobs(profile) }.getOrDefault(emptyList())`, builds `sessionsToActivity(scoped) + cronsToActivity(crons)`, then `_state.value = MissionControlState(sections = groupActivity(items, now))`.
- `MissionControlState(sections, loading, error, unauthorized)`.
- `CronJobDto(id, name, enabled, state, pausedAt, nextRunAt, lastRunAt, lastStatus, lastError, …)`; computed `val isPaused = pausedAt != null || state == "paused"`; `val scheduleText`.
- `isoToEpochMs(iso: String?): Long?` (`com.hermes.client.ui.util`).
- `MissionControlScreen`: per-profile pager; top bar `HermesTopBar(title = "Agent Activity", subtitle = currentProfile?.let { "Profile: $it" })` (line ~119); `MissionControlContent` renders a `LazyColumn` (quicklinks item, then sections); `onOpen(route)` awaits `switchTo(profile)` + `onNavigate(route)`.
- `cron_detail/<id>` is a registered route.

## Architecture

Add a pure alert-deriver, surface its result on the VM state, render a top section on the feed, and rename the header. Each unit is small + independently testable.

### Components

1. **Pure `needsAttention(crons: List<CronJobDto>, nowMs: Long, graceMs: Long = 5 * 60_000L): List<CronAlert>`** — `app/src/main/java/com/hermes/client/ui/activity/NeedsYou.kt`. The unit-tested core.
   - `enum class CronAlertReason { FAILED, OVERDUE }`.
   - `data class CronAlert(val jobId: String, val name: String, val reason: CronAlertReason, val route: String)`.
   - For each job:
     - **FAILED** if `lastStatus.equals("error", true) || lastStatus.equals("failed", true)`.
     - else **OVERDUE** if `enabled && !isPaused && nextRunAt != null && isoToEpochMs(nextRunAt)` is non-null and `< nowMs - graceMs`.
     - else no alert.
   - Failed takes priority (a job is at most one alert). Skip paused/disabled jobs for OVERDUE (but a **disabled/paused** job that *failed* still shows FAILED — a broken job matters regardless of whether it's scheduled again; matches "last run errored").
   - `name = job.name?.ifBlank { null } ?: job.id` (same as `cronsToActivity`); `route = "cron_detail/${job.id}"`.

2. **`MissionControlState.needsYou: List<CronAlert> = emptyList()`** — computed in `loadInner`:
   `_state.value = MissionControlState(sections = groupActivity(items, now), needsYou = needsAttention(crons, now))`. (The error/unauthorized branches leave `needsYou` empty, as today.)

3. **`MissionControlContent` — "Needs you" section** at the top of the `LazyColumn`, **after** the quicklinks item and **before** the `when { … sections … }`, rendered only when `state.needsYou.isNotEmpty()`:
   - A `SectionHeader("Needs you", state.needsYou.size)` (reuse the existing header composable).
   - `items(state.needsYou, key = { "needs-${it.jobId}" }) { alert -> NeedsYouRow(alert, onClick = { onOpen(alert.route) }) }`.
   - **`NeedsYouRow`:** a Material3 `ListItem` — leading `Icons.Rounded.ErrorOutline` tinted `colorScheme.error`; headline `alert.name`; supporting `when (alert.reason) { FAILED -> "Last run failed"; OVERDUE -> "Overdue" }`; `Modifier.clickable(onClick)`.

4. **Header rename** — `HermesTopBar(title = "Home", subtitle = currentProfile?.let { "Profile: $it" })`.

### Data flow

```
feed load / ON_RESUME refresh → loadInner → tools.cronJobs(profile)
  → needsAttention(crons, now) → MissionControlState.needsYou
  → MissionControlContent renders "Needs you" section on top (when non-empty)
  → tap an alert → onOpen("cron_detail/<id>") → switchTo(profile) + navigate  (existing)
top bar → "Home"
```

## Error handling

- A cron fetch failure already degrades gracefully (`getOrDefault(emptyList())`) → `needsAttention([]) = []` → no strip; the conversation feed still renders.
- `isoToEpochMs` returns null for unparseable/absent `nextRunAt` → that job is simply not OVERDUE.
- The feed's own loading/error/empty states are unchanged; the strip is additive.

## Testing

- **Unit (pure), TDD — `NeedsYouTest`:**
  - failed (`lastStatus="error"` and `"failed"`) → FAILED alert; healthy (`"success"`) → none.
  - overdue: enabled, not paused, `nextRunAt` 10 min in the past → OVERDUE; `nextRunAt` in the future → none; within grace (2 min past) → none.
  - paused/disabled job that is NOT failed → no alert (not OVERDUE); paused/disabled job that **failed** → FAILED.
  - failed **and** past-due → a single FAILED alert (priority), not two.
  - `name` falls back to `jobId` when name blank/null; `route == "cron_detail/<id>"`.
- **On-device:** a profile with a failed cron shows a red "Needs you" strip at the top; tapping it opens the job detail; a healthy profile shows no strip; the header reads **"Home"**; backgrounding + resuming refreshes the strip.

## Not doing (YAGNI)

- Messaging-pairing or approvals in "Needs you" (unavailable; see idea doc).
- A cross-profile aggregated strip (stays per-profile with the pager).
- Dismiss/snooze/ack of alerts, or a badge on the Home tab.
- Any new gateway endpoint.

## Open questions (non-blocking; plan may settle)

- Grace window for OVERDUE (`5 min` proposed) — a pure `graceMs` param, trivially tunable.
