# Cron Follow-ups — Design

**Date:** 2026-07-07
**Status:** Approved (brainstorming) → implementation plan next
**Branch:** `feature/cron-followups`
**Source:** the deferred P1/P2 items from `docs/superpowers/specs/2026-07-06-cron-schedule-builder-design.md` ("Not doing") + the cron UX review.

## Goal

Three follow-up polish items for cron: make the detail screen's status/error legible (P1), stop showing opaque job ids in the list (P2), and lower activation energy with empty-state quick-start templates (P2). Compose-only, **no new gateway endpoints**, reusing the `Schedule` model.

## Hard constraints

- **No gateway/bridge API changes.** Compose UI + existing cron REST/DTOs only. Material 3.
- Multi-tenant isolation: new chrome tints to `LocalProfileAccent.current`, never hardcoded.
- No AI/assistant attribution in commits, files, or PRs.

## Grounding (from exploration)

- `CronDetailScreen.kt`: body is a `LazyColumn`; `Field(label, value)` is a bare 2-`Text` `Row` (96 dp label + value), no grouping. Schedule/Status/Next-run/Last-run are 4 consecutive `Field`s. `lastError` renders as `Text(it, color = error, style = bodySmall, maxLines = 3, overflow = Ellipsis)` — hard-truncated. Then the action `Row`, PROMPT preview, RUN HISTORY list. `CronDetailUiState(job, runs, loading, error, message, deleted)`.
- `CronScreen.kt`: row `headlineContent = Text(job.name ?: job.id)`; `supportingContent` already falls back to a prompt snippet (`job.prompt?.replace("\n"," ")?.trim()?.take(100)`) when no next-run. Empty branch: `EmptyState(title = "No cron jobs", subtitle = …)`. `onNew: () -> Unit` (nav `"cron_edit/new"`). Row `trailingContent` = the overflow menu (Pause/Resume/Run now).
- `CronJobDto.prompt: String?` — same DTO for list + detail (present on the list payload; the client already reads it).
- Nav (`ui/nav/HermesNav.kt`): `composable("cron_edit/{id}")` reads `entry.arguments?.getString("id") ?: "new"` → `CronEditScreen(jobId)` → `LaunchedEffect(jobId){ vm.load(jobId) }`. `CronEditViewModel.load(id)`: `if (id=="new") { CronEditState(isNew=true); return }` else fetch. No `SavedStateHandle`. **Any `{id}` string is accepted — so a template id like `new_daily` needs zero new route registration, only a branch in `load()`.**
- `EmptyState` (`ui/components/ScreenStates.kt`) supports a single `actionLabel`/`onAction` button — not multiple; multiple template affordances need a small custom layout.
- `Schedule` model (`ui/cron/Schedule.kt`): `Schedule.Daily/Weekly/Hourly/...`, `Weekday`, `toCron()`, `describe()`.

## Item 1 — Detail status card + expandable error (P1)

*File: `ui/cron/CronDetailScreen.kt`.*
- **Group the run-status trio.** Keep `Field("Schedule", job.scheduleText)` as a header field. Wrap `Status` / `Next run` / `Last run` in a tonal container: a `Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant)` holding a `Column(padding 12dp)` of the three `Field`s, so the "when/did-it-run" info reads as one scannable block distinct from the schedule.
- **Expandable error.** Replace the hard `maxLines = 3` error with a tap-to-expand block: hoist `var errorExpanded by rememberSaveable { mutableStateOf(false) }`; render the error inside the status card (or directly under it) as `Text(lastError, color = error, style = bodySmall, maxLines = if (errorExpanded) Int.MAX_VALUE else 3, overflow = Ellipsis, modifier = Modifier.clickable { errorExpanded = !errorExpanded })`, with a small trailing hint `Text(if (errorExpanded) "Show less" else "Show more", style = labelSmall, color = accent)` shown only when the text is long enough to truncate (approximate: show the hint whenever `lastError` is non-blank — cheap and harmless). Tapping the error or the hint toggles.

## Item 2 — Display-time name fallback (P2)

*Files: create `ui/cron/CronDisplay.kt` (pure) + test; modify `ui/cron/CronScreen.kt`.*
- **Pure helper (unit-tested):**
```kotlin
/** List/display name for a cron job: its name, else a one-line prompt snippet, else its id. */
fun cronDisplayName(name: String?, prompt: String?, id: String, maxLen: Int = 40): String {
    name?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    prompt?.replace("\n", " ")?.trim()?.takeIf { it.isNotEmpty() }?.let {
        return if (it.length <= maxLen) it else it.take(maxLen).trimEnd() + "…"
    }
    return id
}
```
- **Use it** in `CronScreen`'s row: `headlineContent = { Text(cronDisplayName(job.name, job.prompt, job.id)) }` (replacing `job.name ?: job.id`). The supportingContent stays as-is (it shows next-run, or the prompt snippet only when there's no next-run — so a name derived from the prompt won't duplicate the subtitle in the common next-run case).

## Item 3 — Empty-state quick-start templates (P2)

*Files: create `ui/cron/CronTemplates.kt` (pure) + test; modify `ui/cron/CronScreen.kt`, `ui/cron/CronEditScreen.kt`, `ui/nav/HermesNav.kt`.*
- **Templates model (pure, unit-tested):**
```kotlin
data class CronTemplate(val id: String, val label: String, val schedule: Schedule, val prompt: String)

val CRON_TEMPLATES = listOf(
    CronTemplate("new_daily", "Daily summary · 9:00", Schedule.Daily(9, 0),
        "Summarize what happened across my projects yesterday — deploys, incidents, and anything that needs my attention."),
    CronTemplate("new_weekly", "Weekly audit · Mon 2:00", Schedule.Weekly(setOf(Weekday.MON), 2, 0),
        "Run a dependency and security audit and list anything that needs attention."),
    CronTemplate("new_hourly", "Hourly check", Schedule.Hourly(0),
        "Check for anything urgent that needs my attention and summarize it."),
)

fun cronTemplate(id: String): CronTemplate? = CRON_TEMPLATES.firstOrNull { it.id == id }
```
- **Pre-fill via the existing route.** `onNew` becomes `onNew: (String) -> Unit` (the seed id). In `HermesNav`: `onNew = { seed -> nav.navigate("cron_edit/$seed") }`. In `CronEditViewModel.load(id)`, before the existing logic:
```kotlin
    fun load(id: String) {
        jobId = id
        val template = cronTemplate(id)
        if (id == "new" || template != null) {
            _state.value = if (template != null) CronEditState(schedule = template.schedule, prompt = template.prompt, isNew = true)
                           else CronEditState(isNew = true)
            return
        }
        // …existing fetch-for-edit path…
    }
```
(`isNew = true` for templates, so `save()` calls `createCron` — `jobId = "new_daily"` is never used for an update.)
- **Custom empty layout** (`EmptyState` only has one button slot): a small private `CronEmpty(onNew: (String) -> Unit)` in `CronScreen.kt` — the inbox icon + "No cron jobs" + a "Start from a template:" caption + a column of `OutlinedButton`s (one per `CRON_TEMPLATES`, `onClick = { onNew(it.id) }`, tenant-accent), then a filled `Button("New cron job", onClick = { onNew("new") })`. The `state.jobs.isEmpty()` branch renders `CronEmpty(onNew)` instead of the shared `EmptyState`. The `New` FAB continues to call `onNew("new")`.

## Testing

- **Unit (pure), TDD:**
  - `cronDisplayName`: name present → name (trimmed); blank name → prompt snippet (single line, `take(40)` + "…" when long); blank prompt → id.
  - `cronTemplate` / `CRON_TEMPLATES`: `cronTemplate("new_daily")` → the Daily(9,0) template; unknown id → null; every template's `schedule.toCron()` is a valid 5-field expr (`isValidCron`).
- **On-device:**
  1. Detail of a FAILED cron (mock `c3` "Nightly DB backup") — Status/Next-run/Last-run render as a grouped card; the error is tap-to-expand (Show more → full text → Show less).
  2. A cron with no name (add one to the mock, or verify the fallback) shows a prompt-derived headline, not an opaque id.
  3. A profile with no crons (e.g. globex) shows the empty layout with the three template buttons + "New cron job"; tapping "Daily summary · 9:00" opens the builder pre-filled with Daily 09:00 + the starter prompt; tapping "New cron job" opens a blank Daily default.
  4. Everything tenant-accent tinted.

## Not doing (YAGNI)

- Persisting a derived name at create (display-time fallback covers it without mutation).
- A dialog/bottom-sheet for the error (in-place expand is lighter).
- Editable template management or server-side blueprints (no endpoint).
- Any gateway/API change.
