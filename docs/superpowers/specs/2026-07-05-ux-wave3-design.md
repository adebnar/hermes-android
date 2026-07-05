# UX Wave 3 — Home as Navigation (batch) — Design

**Date:** 2026-07-05
**Status:** Approved (brainstorming) → implementation plan next
**Branch:** `feature/ux-wave3`
**Source:** `docs/ideas/ux-review-2026-07-04.md` (bigger bet #3 + remaining Home P1s)

## Goal

Advance "make the Home feed the navigation, act on a few": actionable Needs-You rows (relative time + chevron + one-tap "Run now"), tappable cron section headers, a unified coloured-avatar tenant switcher, a lifted Home hero, and a cleaner composer placeholder. **No new gateway/bridge endpoints** (reuses the existing `triggerCron`).

## Hard constraints

- **No NEW gateway/bridge endpoints.** Reuse existing REST/WS. (`ToolsRepository.triggerCron` / `POST /api/cron/jobs/{id}/trigger` already exists.)
- Material 3; multi-tenant isolation; follow patterns (`ProfileAvatar`, `AccentChrome`, `SectionHeader`, `needsAttention`/`CronAlert`, `onOpen`/deep-link).
- No AI/assistant attribution in commits, files, or PRs.

## Item 1 — Actionable "Needs You" rows (P1)

`CronAlert` (`ui/activity/NeedsYou.kt`) carries no timestamp and `NeedsYouRow` (`MissionControlScreen.kt`) is icon + name + static reason (tap → `cron_detail`). Make each row telegraph what it is and offer the fix inline. All Needs-You alerts are FAILED/OVERDUE by construction, so every row gets "Run now".

- **`CronAlert.lastRunAtMs: Long? = null`** — in `needsAttention`, set `lastRunAtMs = isoToEpochMs(job.lastRunAt)` in both the FAILED and OVERDUE branches (the DTO is in scope). Pure; unit-tested.
- **`MissionControlViewModel.runCron(jobId): suspend (): Boolean`** — `runCatching { tools.triggerCron(jobId, profile); true }.getOrDefault(false)`, and on success call `refresh()` so the row's state updates. `ToolsRepository` is already injected.
- **`NeedsYouRow`** (add `nowMs: Long`, `onRunNow: () -> Unit`):
  - `supportingContent`: reason + relative time — `listOfNotNull(reasonText, alert.lastRunAtMs?.let { relativeTime(it, nowMs) }).joinToString(" · ")` → e.g. "Last run failed · 2h ago" (falls back to just the reason if `lastRunAtMs` is null).
  - `trailingContent`: a `Row { TextButton("Run now", onClick = onRunNow); Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) }`. The row body tap still → `cron_detail` (existing `onClick`).
  - In `MissionControlContent`/`MissionControlPage`, wire `onRunNow = { scope.launch { val ok = vm.runCron(alert.jobId); Toast.makeText(context, if (ok) "Triggered ${alert.name}" else "Couldn't run ${alert.name}", Toast.LENGTH_SHORT).show() } }`. Pass the page's `now` as `nowMs`.

*Files: `ui/activity/NeedsYou.kt`, `ui/activity/MissionControlViewModel.kt`, `ui/activity/MissionControlScreen.kt`.*

## Item 2 — Tappable cron section headers (P2, bet #3)

`SectionHeader(label, count)` is static text. Make cron-related headers deep-link to the Cron list.

- Add `onClick: (() -> Unit)? = null` to `SectionHeader`; when non-null, the `Row` gets `Modifier.clickable(onClick = onClick)` and a trailing `Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight)` (the "›") after the count.
- **Needs-you header:** `SectionHeader("Needs you", state.needsYou.size, onClick = { onOpen("cron") })`.
- **Upcoming header:** in the `state.sections.forEach { section -> … }`, pass `onClick = { onOpen("cron") }` only when `section.title.equals("Upcoming", ignoreCase = true)`; else `null`.
- **Live now / Recent:** stay plain (their rows already navigate; no clean single push target).

*File: `ui/activity/MissionControlScreen.kt`.*

## Item 3 — Unified coloured-avatar tenant switcher (P1 [01/06])

Home (`MissionControlScreen`) and Chats (`SessionsScreen`) use `ProfileChips` (plain `FilterChip`s); the You tab uses coloured `ProfileAvatar`s. Standardise on one control that carries the tenant colour.

- **New `ui/components/ProfileSwitcher.kt`** — same contract as `ProfileChips` (`names: List<String>, active: String?, onSelect: (String) -> Unit, modifier`): a horizontally-scrolling `Row` of `FilterChip`s, each `FilterChip(selected = name == active, onClick = { onSelect(name) }, label = { Text(name) }, leadingIcon = { ProfileAvatar(name, size = 18.dp) })`. This adds the per-tenant coloured initial to the existing chip while keeping M3 selected styling.
- **Swap both callers** (`MissionControlScreen.kt:121`, `SessionsScreen.kt:108`) from `ProfileChips(...)` to `ProfileSwitcher(...)` — **identical arguments**, so the Home pager's two-way `active = currentProfile` / `onSelect = { animateScrollToPage(idx) }` linkage and the Sessions `onSelect = vm::switchProfile` are preserved untouched.
- **Delete `ui/components/ProfileChips.kt`** (now unused — only those two callers exist).

*Files: create `ui/components/ProfileSwitcher.kt`; modify `MissionControlScreen.kt`, `SessionsScreen.kt`; delete `ui/components/ProfileChips.kt`.*

## Item 4 — Lift the Home hero (P1 §1 [01])

The quicklinks Row is the first feed item, pushing "NEEDS YOU" down; the "Profile: acme" subtitle is redundant with the accent bar + the avatar switcher.

- **Move the `item(key = "quicklinks") { … }`** from the **top** of `MissionControlContent`'s `LazyColumn` to the **bottom** (after the `when { … state.sections … }` block), so it reads as a "tools" footer and NEEDS YOU / the sections are the first content under the switcher.
- **Drop the Home subtitle:** `HermesTopBar(title = "Home", subtitle = currentProfile?.let { "Profile: $it" })` → `HermesTopBar(title = "Home")`. (Scope: **Home only** — ChatScreen keeps its subtitle, since Chat has no switcher row.)

*File: `ui/activity/MissionControlScreen.kt`.*

## Item 5 — Simpler composer placeholder (P2 §4)

The send arrow already tints the accent when `canSend` (grey only when empty/offline — correct), so only the placeholder changes.

- `ChatScreen` `OutlinedTextField` `placeholder = { Text("Message Hermes…  (/ commands · @ attach)") }` → `placeholder = { Text("Message Hermes…") }`.

*File: `ui/chat/ChatScreen.kt`.*

## Testing

- **Unit (pure), TDD — extend `NeedsYouTest`:** a job with `lastRunAt` set → the returned `CronAlert.lastRunAtMs` equals `isoToEpochMs(lastRunAt)`; a job with null `lastRunAt` → `lastRunAtMs == null`.
- **Unit — `MissionControlViewModel.runCron`:** success (mock `tools.triggerCron`) returns `true` and calls a reload (`coVerify` on `sessions.activityFeed`/`tools.cronJobs` again, or a state re-emit); failure returns `false` (mock `triggerCron` throws) and does not crash.
- **On-device:**
  1. A failed/overdue cron in "Needs you" shows a relative time + a "Run now" button + a "›"; tapping **Run now** shows a "Triggered …" toast and the row refreshes; tapping the row body still opens cron detail.
  2. The **UPCOMING** header and the **Needs you** header show a "›" and deep-link to the Cron list.
  3. Home + Chats show the **coloured-avatar** tenant switcher (matching You); switching still drives the Home pager and the Chats filter.
  4. On Home, **NEEDS YOU** sits near the top (quicklinks now a footer); the "Profile: acme" subtitle is gone.
  5. The chat composer placeholder reads "Message Hermes…".

## Not doing (YAGNI)

- Confirmation dialog before "Run now" (triggering a cron is non-destructive; a toast suffices).
- Deep-linking Live-now/Recent headers (no clean single push target; rows navigate).
- Dropping the Chat subtitle or restyling the tall app bar (separate P2s).
- Send-arrow tint change (already correct).
- Any new gateway endpoint.

## Open questions (non-blocking; plan may settle)

- Whether "Run now" should also show a brief in-row spinner while triggering (a toast is the MVP; a spinner is optional polish).
