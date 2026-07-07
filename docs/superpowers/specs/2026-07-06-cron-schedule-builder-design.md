# Cron Schedule Builder & Management — Design

**Date:** 2026-07-06
**Status:** Approved (brainstorming) → implementation plan next
**Branch:** `feature/cron-schedule-builder`
**Source:** user ask ("cron should be easier to set up and manage; editing the schedule is very technical") + a UX-specialist review of the cron flow.

## Goal

Make cron **setup** easy (a friendly schedule builder replacing the raw cron field), fix a **genuine bug** (edit re-sends the display string as the cron expression), and make **management** faster (per-row pause/resume/run on the list). Compose-only, **no new gateway endpoints** — the builder emits a standard 5-field cron string into the existing `createCron`/`updateCron` write path.

## Hard constraints

- **No gateway/bridge API changes.** Reuse existing cron REST (`createCron`/`updateCron` send a flat `"schedule"` string; `pauseCron`/`resumeCron`/`triggerCron`/`cronJob`). Material 3.
- Multi-tenant isolation: all new chrome tints to `LocalProfileAccent.current` (per-tenant accent).
- No AI/assistant attribution in commits, files, or PRs.

## Grounding (from exploration)

- **Create/Edit form** (`ui/cron/CronEditScreen.kt` — holds `CronEditViewModel` + `CronEditScreen`): 3 fields (Name, **Schedule** = raw single-line `OutlinedTextField` "Schedule (cron, e.g. 0 9 * * *)", Prompt). `CronEditState(name, schedule: String, prompt, isNew, …)`. `load(id)`: `id=="new"`→reset; else `tools.cronJob(id, profile)` and seeds **`schedule = job.scheduleText`** (the display string — the bug). `save()`: `tools.createCron(prompt, schedule, name, profile)` / `updateCron(jobId, …)`; validation = prompt+schedule non-blank.
- **DTO** (`data/network/Dtos.kt`): `CronJobDto.schedule: CronScheduleDto?(kind, expr, display)` — raw expr at **`schedule.expr`**; `scheduleText` getter = `scheduleDisplay ?: schedule.display ?: schedule.expr ?: "—"`. Write path sends a flat `"schedule"` string. Cron is standard 5-field `m h dom mon dow`.
- **List** (`ui/cron/CronScreen.kt` + `CronViewModel`): rows = status icon + `scheduleText` overline + name + next-run; row tap → `cron_detail/$id`; **no per-row actions**. `CronViewModel` has `tools`, `profileManager`, `load()` (no pause/resume/trigger). Detail's VM already has `pause()/resume()/trigger()` via `tools`.
- No blueprints endpoint; no delivery-targets field (both out of scope — don't invent them).

## Element 1 — Structured `Schedule` model (pure, unit-tested) — the core

New `ui/cron/Schedule.kt`. A structured model that builds/describes/previews **without a general cron engine**; only `parseCron` pattern-matches.

```kotlin
enum class Weekday(val cron: Int, val short: String) {
    SUN(0, "Sun"), MON(1, "Mon"), TUE(2, "Tue"), WED(3, "Wed"), THU(4, "Thu"), FRI(5, "Fri"), SAT(6, "Sat")
}

sealed interface Schedule {
    data class Hourly(val minute: Int) : Schedule
    data class Daily(val hour: Int, val minute: Int) : Schedule
    data class Weekly(val days: Set<Weekday>, val hour: Int, val minute: Int) : Schedule
    data class Monthly(val dayOfMonth: Int, val hour: Int, val minute: Int) : Schedule
    data class Advanced(val expr: String) : Schedule
}
```
- **`fun Schedule.toCron(): String`** — the 5-field expr:
  - `Hourly(m)` → `"$m * * * *"`; `Daily(h,m)` → `"$m $h * * *"`; `Weekly(days,h,m)` → `"$m $h * * ${days.sortedBy{it.cron}.joinToString(","){it.cron.toString()}}"`; `Monthly(dom,h,m)` → `"$m $h $dom * *"`; `Advanced(e)` → `e`.
- **`fun Schedule.describe(): String`** — human summary:
  - `Hourly(0)` → "Every hour"; `Hourly(m)` → "Every hour at :%02d".format(m); `Daily(h,m)` → "Every day at %02d:%02d"; `Weekly(days,h,m)` → "%s at %02d:%02d".format(days.sortedBy{it.cron}.joinToString(", "){it.short}, …) (all 7 days → "Every day at HH:MM"); `Monthly(dom,h,m)` → "Day $dom of each month at %02d:%02d"; `Advanced(e)` → e.
- **`fun Schedule.nextRun(nowMs: Long, zone: ZoneId = systemDefault()): Long?`** — next occurrence via `java.time` from the structured state (Advanced → `null`):
  - `Daily(h,m)` → today at h:m if strictly after now, else +1 day. `Hourly(m)` → this hour at :m if after now, else +1 hour. `Weekly` → the soonest of the selected weekdays at h:m that is after now. `Monthly(dom,h,m)` → this month's dom at h:m if after now (and dom ≤ month length), else the next month that has that day. Return epoch millis.
- **`fun parseCron(expr: String): Schedule`** — split into 5 whitespace fields `[min hour dom mon dow]`; match (all numeric fields must be plain integers, `*` literal):
  - `min=int, hour=*, dom=*, mon=*, dow=*` → `Hourly(min)`; `min,hour=int, dom=*, mon=*, dow=*` → `Daily(hour,min)`; `min,hour=int, dom=*, mon=*, dow=<comma-list of ints>` → `Weekly(days,hour,min)`; `min,hour,dom=int, mon=*, dow=*` → `Monthly(dom,hour,min)`; anything else (ranges, steps, lists in other fields, wrong field count) → `Advanced(expr.trim())`.
- **`fun isValidCron(expr: String): Boolean`** — exactly 5 whitespace-separated fields, each matching a basic cron token `^(\*|(\d+([-/,]\d+)*))$` (lenient; guards the Advanced field).

## Element 2 — Schedule builder UI (`CronEditScreen`)

Replace the raw schedule `OutlinedTextField` with a builder driven by `state.schedule: Schedule`:
- **Preset segmented control** (`SingleChoiceSegmentedButtonRow`, accent-tinted like `FeedTabs`): **Hourly · Daily · Weekly · Monthly · Advanced**. Selecting a preset sets `state.schedule` to a sensible default of that kind (Hourly→`Hourly(0)`, Daily→`Daily(9,0)`, Weekly→`Weekly({MON},9,0)`, Monthly→`Monthly(1,9,0)`, Advanced→`Advanced(currentToCron)`), preserving the current time where sensible.
- **Per-kind controls:**
  - time (Daily/Weekly/Monthly): an "At HH:MM" row opening a Material3 `TimePicker` dialog (24h). Hourly: a "minute" stepper/dropdown (0–59) shown as ":MM".
  - Weekly: a row of day `FilterChip`s (Sun…Sat), multi-select (≥1 enforced).
  - Monthly: a day-of-month dropdown (1–28, plus a note that 29–31 may skip short months — cap the picker at 28 for safety, or 1–31 with a helper caption; **use 1–28** to avoid skipped months).
  - Advanced: the existing raw `OutlinedTextField` + "min hour day month weekday" caption, with **inline validation** via `isValidCron` (error text + Save disabled when invalid).
- **Live summary + next-run:** a `Text` under the controls = `state.schedule.describe()` + `state.schedule.nextRun(now)?.let { " · Next: " + formatIso(epochToIso(it)) }` (Advanced → summary only, no next-run). Accent-tinted label.
- **Save:** unchanged flow, but sends `state.schedule.toCron()` as the schedule string.

## Element 3 — Round-trip fix + structured state (`CronEditViewModel`)

- `CronEditState.schedule` becomes `Schedule` (default `Daily(9, 0)` for new). Setter `setSchedule(Schedule)`.
- **`load(id)`** (edit path): seed from the **raw expr** — `val expr = job.schedule?.expr ?: job.scheduleText; state.schedule = parseCron(expr)`. Never seed from the display string. (If the server only supplied a display string with no `expr`, `parseCron` falls back to `Advanced`, surfacing it in the raw field — no worse than today, and visible.)
- **`save()`**: `val schedule = state.schedule.toCron()`; keep the existing `createCron`/`updateCron` calls with that string; validation = prompt non-blank AND (schedule is a preset OR `isValidCron(advancedExpr)`).
- **Round-trip guarantee:** `parseCron("0 9 * * *")` → `Daily(9,0)` → `toCron()` → `"0 9 * * *"` — editing a job's prompt no longer mutates its schedule.

## Element 4 — List row quick actions (`CronViewModel` + `CronScreen`)

- **`CronViewModel`**: add `fun runAction(jobId: String, action: CronAction)` where `CronAction { PAUSE, RESUME, RUN }` → `runCatching { when(action){ PAUSE->tools.pauseCron(jobId,p); RESUME->tools.resumeCron(jobId,p); RUN->tools.triggerCron(jobId,p) } }` then `load()` (reload) on success; expose a one-shot result for a toast (reuse the `message: String?` + snackbar pattern, or a Toast from the screen).
- **`CronScreen` row**: add a `trailingContent` overflow `IconButton(Icons.Rounded.MoreVert)` → `DropdownMenu` with **Pause**/**Resume** (by `job.isPaused`) and **Run now**, each calling `vm.runAction(job.id, …)` + a Toast ("Paused"/"Resumed"/"Triggered <name>"). Row tap still → detail. No Edit/Delete on the row (those stay on detail).

## Testing

- **Unit (pure), TDD — `ScheduleTest`:**
  - `toCron`: each of Hourly/Daily/Weekly/Monthly/Advanced → exact expr.
  - `describe`: representative strings (Hourly :00 vs :05, Daily, Weekly one-day vs multi vs all-7, Monthly, Advanced passthrough).
  - `nextRun` (fixed `now` + fixed `ZoneId`): Daily before/after time (today vs tomorrow); Hourly; Weekly (soonest selected day); Monthly (this month vs next); Advanced → null.
  - `parseCron`: `"0 9 * * *"`→Daily(9,0); `"5 * * * *"`→Hourly(5); `"0 2 * * 1"`→Weekly({MON},2,0); `"0 2 * * 1,3,5"`→Weekly({MON,WED,FRI},2,0); `"0 3 15 * *"`→Monthly(15,3,0); `"*/15 9-17 * * 1-5"`→Advanced (unchanged); round-trip `parseCron(x).toCron() == x` for the preset cases.
  - `isValidCron`: valid 5-field / step / list vs too few fields / garbage.
- **On-device** (mock needs `GET /api/cron/jobs/{id}` + accept `POST`/`PUT /api/cron/jobs` — add to the mock for verification):
  1. New cron → builder defaults to Daily 09:00; switching presets updates controls + the live "Every … · Next: …" summary; Advanced shows the raw field with validation.
  2. Create a Daily 08:30 job → it appears in the list as "Every day at 08:30".
  3. Edit an existing job (e.g. "0 9 * * *") → the builder opens on **Daily 09:00** (not a raw string); changing only the prompt and saving keeps the schedule "Every day at 09:00" (round-trip holds).
  4. On the list, a row's overflow menu → Pause/Resume + Run now work (toast + the row's status updates), without opening detail.
  5. Everything tenant-accent tinted (green acme / gold globex).

## Not doing (YAGNI / deferred)

- **Deferred (P1/P2, follow-up):** expandable full error + grouped status on the detail screen; auto-name-from-prompt when Name is blank; empty-state quick-start templates.
- Interval presets ("every N hours/minutes") — Advanced covers them.
- Day-of-month 29–31 (capped at 28 to avoid skipped-month surprises).
- Any delivery-targets or blueprints UI (no such data/endpoint exists).
- Any gateway/API change.
