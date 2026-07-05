# UX Quick Wins (batch) — Design

**Date:** 2026-07-05
**Status:** Approved (brainstorming) → implementation plan next
**Branch:** `feature/ux-quick-wins`
**Source:** Top-5 quick wins in `docs/ideas/ux-review-2026-07-04.md`

## Goal

Five self-contained, no-bridge-change UI improvements, shipped together (one task each): styled error/empty states, full per-tenant accent wiring, a visible model chip, cron status in the list, and a persistent tenant token near the chat composer.

## Hard constraints

- **No gateway/bridge API changes.** Compose UI + existing state/data only. Material 3.
- Multi-tenant isolation preserved (never blend client data).
- Follow existing patterns; no unrelated refactors.
- No AI/assistant attribution in commits, files, or PRs.

## Item 1 — Route bare error/empty screens through the shared state composables

`ui/components/ScreenStates.kt` already defines `LoadingState`, `ErrorState(message, modifier, onRetry)` (icon + message + **Retry**), and `EmptyState(title, subtitle, icon, actionLabel, onAction)`. Several screens bypass them with a bare `Text(error!!)`. Route these through the shared composables (retry wired to each VM's `load`):

- `ui/cron/CronDetailScreen.kt:77` — `state.job == null -> Text(state.error ?: "Not found", …)` → `ErrorState(message = state.error ?: "Couldn't load this cron job", onRetry = { vm.load(jobId) })`. (`jobId` is the screen's nav arg, already used at line 53.)
- `ui/cron/CronScreen.kt:52-59` — error `Text` → `ErrorState(message = state.error!!, onRetry = { vm.load() })`; empty `Text` → `EmptyState(title = "No cron jobs", subtitle = "Scheduled jobs for this profile will show up here.")`.
- `ui/tools/AgentsToolsScreen.kt:42-43` — error `Text` → `ErrorState(message = state.error!!, onRetry = { vm.load() })` (`ToolsViewModel.load()`); add `EmptyState(title = "No agents or tools")` for the empty case.
- `ui/usage/UsageScreen.kt:111-112` — error `Text` → `ErrorState(message = state.error!!, onRetry = { vm.load() })`.

Leave `ChatScreen`'s `ConnectionBanner` as-is (already a styled `errorContainer` banner with Retry). Each `ErrorState`/`EmptyState` fills its content area (`Modifier.align(Center)` sites become the state composable centered in the same box/scaffold padding).

## Item 2 — Wire the per-tenant accent through in-content elements

**Verified:** `HermesTheme(profile = activeProfile)` (Theme.kt:36, fed by `MainActivity`) provides `LocalProfileAccent` app-wide as the **active** profile's accent; `MissionControlScreen` re-provides it per pager page. So reading `LocalProfileAccent.current.accent` in content yields the active tenant colour everywhere (and the correct per-page colour on the feed).

Replace `MaterialTheme.colorScheme.primary` with `LocalProfileAccent.current.accent` at these **in-content accent** sites (imports: `com.hermes.client.ui.theme.LocalProfileAccent`):

- `ui/activity/MissionControlScreen.kt` — `SectionHeader` label (`:259`), `ActivityRow` non-error icon tint (`:311`).
- `ui/cron/CronScreen.kt:70` — the schedule overline colour (enabled/not-paused branch only; keep the `error` colour for the paused/disabled branch).
- `ui/cron/CronDetailScreen.kt:115,123` — the "PROMPT" / "RUN HISTORY" labels.
- `ui/chat/ChatScreen.kt:197,214` — the "COMMANDS" / "ATTACH · MENTION" labels.
- `ui/chat/ChatComponents.kt:354` — the accent icon tint.
- `ui/usage/UsageScreen.kt:128,134` — the section labels. **Leave `:159` (chart `inputColor`)** as `colorScheme.primary` for now unless it reads cleanly as the accent — the chart colour is a separate concern; changing it is optional and can stay primary.
- `ui/sessions/SessionsScreen.kt:308,315,335,411` — group-header chevron tint, group label, `SectionHeader` label, and the icon tint.
- `ui/models/ModelSelector.kt:148,191` — current-model row label and the favourite-star tint.
- `ui/nav/YouHubScreen.kt:74,230` — "PROFILES" label and the selected-avatar-name label.

Do **not** touch semantic non-accent uses of `colorScheme.error`/`onSurface`/etc. This item is purely `colorScheme.primary` → `LocalProfileAccent.current.accent` at the enumerated call sites. (`LocalProfileAccent.current.accent` is a `@Composable` read — valid at all these sites.)

## Item 3 — Visible model chip in the chat header

`ChatScreen.kt` (~138) currently renders the model control as a `TextButton(Text(currentModel ?: "Model"))` **gated on `providers.isNotEmpty()`** (so it's hidden until providers load, e.g. on cold-open) and reads as flat text. Replace with an always-visible, obviously-tappable chip:

```kotlin
androidx.compose.material3.AssistChip(
    onClick = { modelSheetOpen = true },
    label = { Text(currentModel ?: "Model", maxLines = 1) },
    trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, Modifier.size(18.dp)) },
)
```

- Remove the `if (providers.isNotEmpty())` gate — always show the chip (tapping opens the sheet, which loads providers). Keep `StatusDot(connState)` after it.
- The chip sits in the top-bar `actions` slot; its content inherits the accent-tinted `onBar` content colour as today.

## Item 4 — Cron status indicator in the Cron list

Add a **leading status icon** to each `CronScreen` row so failed/overdue jobs are triage-able from the list (same iconography as Home). Add a pure, unit-tested helper:

`ui/cron/CronRowStatus.kt`:
```kotlin
enum class CronRowStatus { FAILED, OVERDUE, OK, PAUSED }

/** Pure: a cron job's at-a-glance status for the list. Reuses needsAttention for FAILED/OVERDUE. */
fun cronRowStatus(job: CronJobDto, nowMs: Long): CronRowStatus = when {
    needsAttention(listOf(job), nowMs).firstOrNull()?.reason == CronAlertReason.FAILED -> CronRowStatus.FAILED
    needsAttention(listOf(job), nowMs).firstOrNull()?.reason == CronAlertReason.OVERDUE -> CronRowStatus.OVERDUE
    !job.enabled || job.isPaused -> CronRowStatus.PAUSED
    else -> CronRowStatus.OK
}
```
In `CronScreen.kt`'s `ListItem`, add `leadingContent = { Icon(...) }`:
- `FAILED`/`OVERDUE` → `Icons.Rounded.ErrorOutline`, tint `MaterialTheme.colorScheme.error`.
- `PAUSED` → `Icons.Rounded.PauseCircleOutline`, tint `onSurfaceVariant`.
- `OK` → `Icons.Rounded.CheckCircle`, tint `LocalProfileAccent.current.accent`.

Compute `nowMs = remember(state.jobs) { System.currentTimeMillis() }` in the list scope.

## Item 5 — Persistent tenant token near the chat composer

Extract the lettered, accent-coloured avatar (today private `ProfileAvatarRow` in `YouHubScreen.kt:201-236`) into a reusable composable in `ui/components/ProfileAvatar.kt`:

```kotlin
@Composable
fun ProfileAvatar(name: String?, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    val dark = isSystemInDarkTheme()
    val accent = rememberProfileAccent(name, dark)
    Box(modifier.size(size).clip(CircleShape).background(accent.accent), contentAlignment = Alignment.Center) {
        Text((name ?: "·").take(1).uppercase(), color = accent.onAccent, style = MaterialTheme.typography.labelLarge)
    }
}
```
- Place a `ProfileAvatar(activeProfile)` as the **leftmost** element of the Chat composer `Row` (`ChatScreen.kt:146-186`), before the paperclip `IconButton`, with a trailing `Spacer(Modifier.width(4.dp))`. It shows the active client's letter in its accent — always visible while typing.
- Refactor `YouHubScreen`'s `ProfileAvatarRow` to use the new `ProfileAvatar` (drop the duplicated Box/Text; keep its 48 dp size + the name label below).

## Testing

- **Unit (pure), TDD — `CronRowStatusTest`:** failed `lastStatus="error"` → FAILED; overdue → OVERDUE; paused/disabled → PAUSED; healthy future → OK; FAILED priority over OVERDUE.
- **On-device (per item):**
  1. CronDetail with a bad id, Cron list load error, Agents error, Usage error → styled icon + message + **Retry** (not bare text); empty cron/agents → styled empty state.
  2. Switch profiles (acme/globex/initech) → section headers, icons, cron overline, chat command labels, etc. all render in that tenant's accent (both light + dark).
  3. Chat header shows a model **chip** (name + caret), visible on cold-open, taps → the model sheet.
  4. Cron list rows show a leading status icon; a failed job is red, healthy shows a check, paused shows a pause.
  5. The chat composer shows the tenant avatar token (correct letter + colour) next to the paperclip.

## Not doing (YAGNI)

- Any new component beyond `ProfileAvatar` (Item 1 reuses existing `ScreenStates`).
- Chart-colour accenting (Usage `:159`) unless trivially clean.
- Changing `ChatScreen`'s `ConnectionBanner` (already styled).
- The P1/P2 items from the review (this batch is the Top-5 quick wins only).
- Any gateway/API change.

## Open questions (non-blocking; plan may settle)

- Model chip style (`AssistChip` vs a tonal pill) — `AssistChip` proposed; the plan pins it.
- Composer token size (28 dp proposed).
