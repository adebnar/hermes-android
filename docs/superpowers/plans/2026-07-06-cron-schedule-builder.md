# Cron Schedule Builder & Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the raw cron text field with a friendly schedule builder, fix the edit round-trip bug, and add per-row pause/resume/run to the cron list.

**Architecture:** A pure structured `Schedule` model (`toCron`/`describe`/`nextRun`/`parseCron`/`isValidCron`, unit-tested) does the work with no general cron engine; the edit VM holds a `Schedule` and seeds it from the raw expr; the edit UI is a preset builder over it; the list VM/screen gain quick actions. All over the existing cron REST.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, java.time, JUnit.

## Global Constraints

- **No gateway/bridge API changes.** The client builds cron strings and sends them via the existing `createCron`/`updateCron` (`schedule` string) + `pauseCron`/`resumeCron`/`triggerCron`.
- Multi-tenant isolation: new chrome tints to `LocalProfileAccent.current`, never a hardcoded colour.
- JDK 21: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
- Compile: `./gradlew :app:compileDebugKotlin --console=plain`. Tests: `./gradlew :app:testDebugUnitTest --console=plain`. Beta: `./gradlew :app:assembleBeta`.
- **No AI/assistant attribution** in commits, files, or PRs.

## Grounding

- `ui/cron/CronEditScreen.kt`: `CronEditState(name, schedule: String, prompt, isNew, loading, saved, message)`; `CronEditViewModel` with `setName/setSchedule/setPrompt`, `load(id)` (`id=="new"`→reset; else `tools.cronJob(id, profile)` seeding `schedule = job.scheduleText`), `save()` (`tools.createCron(prompt, schedule, name, profile)`/`updateCron(jobId, …)`, validation prompt+schedule non-blank). The `CronEditScreen(onDone, vm)` composable renders 3 `OutlinedTextField`s (schedule = raw, label "Schedule (cron, e.g. 0 9 * * *)" + caption "min hour day month weekday").
- `data/network/Dtos.kt`: `CronJobDto.schedule: CronScheduleDto?(kind, expr, display)`; raw at `job.schedule?.expr`; `scheduleText` getter exists; `isPaused` getter exists.
- `ui/cron/CronViewModel.kt`: `CronUiState(jobs, profile, loading, error)`; injects `tools: ToolsRepository`, `profileManager`; `load()`. `ToolsRepository.pauseCron/resumeCron/triggerCron(jobId, profile)` exist.
- `ui/cron/CronScreen.kt`: rows are `ListItem { leadingContent=status icon; overlineContent=scheduleText; headlineContent=name; supportingContent=next-run; modifier=clickable{onOpen(job.id)} }`; a "New" FAB.
- Patterns: `SingleChoiceSegmentedButtonRow`/`SegmentedButton` (`ui/activity/FeedTabs.kt`, `ui/settings/AppearanceScreen.kt`); `LocalProfileAccent.current.{accent,onAccent,container,onContainer}`; `com.hermes.client.ui.util.formatIso(iso: String)`.

---

### Task 1: Structured `Schedule` model (TDD, pure)

**Files:**
- Create: `app/src/main/java/com/hermes/client/ui/cron/Schedule.kt`
- Test: `app/src/test/java/com/hermes/client/ui/cron/ScheduleTest.kt`

**Interfaces:** Produces `Weekday`, `Schedule` (+ 5 subtypes), `Schedule.toCron()`, `Schedule.describe()`, `Schedule.nextRun(nowMs, zone)`, `parseCron(expr)`, `isValidCron(expr)`.

- [ ] **Step 1: Write the failing tests** — create `ScheduleTest.kt`:

```kotlin
package com.hermes.client.ui.cron

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ScheduleTest {
    private val utc = ZoneId.of("UTC")
    // 2026-07-06 10:30:00 UTC (a Monday)
    private val now = Instant.parse("2026-07-06T10:30:00Z").toEpochMilli()
    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test fun toCron_all() {
        assertEquals("5 * * * *", Schedule.Hourly(5).toCron())
        assertEquals("0 9 * * *", Schedule.Daily(9, 0).toCron())
        assertEquals("0 2 * * 1", Schedule.Weekly(setOf(Weekday.MON), 2, 0).toCron())
        assertEquals("0 9 * * 1,3,5", Schedule.Weekly(setOf(Weekday.FRI, Weekday.MON, Weekday.WED), 9, 0).toCron())
        assertEquals("0 3 15 * *", Schedule.Monthly(15, 3, 0).toCron())
        assertEquals("*/15 9-17 * * 1-5", Schedule.Advanced("*/15 9-17 * * 1-5").toCron())
    }

    @Test fun describe_representatives() {
        assertEquals("Every hour", Schedule.Hourly(0).describe())
        assertEquals("Every hour at :05", Schedule.Hourly(5).describe())
        assertEquals("Every day at 09:00", Schedule.Daily(9, 0).describe())
        assertEquals("Mon at 02:00", Schedule.Weekly(setOf(Weekday.MON), 2, 0).describe())
        assertEquals("Mon, Wed, Fri at 09:00", Schedule.Weekly(setOf(Weekday.FRI, Weekday.MON, Weekday.WED), 9, 0).describe())
        assertEquals("Every day at 09:00", Schedule.Weekly(Weekday.values().toSet(), 9, 0).describe())
        assertEquals("Day 15 of each month at 03:00", Schedule.Monthly(15, 3, 0).describe())
        assertEquals("*/15 9-17 * * 1-5", Schedule.Advanced("*/15 9-17 * * 1-5").describe())
    }

    @Test fun nextRun_daily() {
        // now = Mon 10:30. Daily 09:00 already passed today -> tomorrow 09:00.
        assertEquals(at("2026-07-07T09:00:00Z"), Schedule.Daily(9, 0).nextRun(now, utc))
        // Daily 14:00 is later today.
        assertEquals(at("2026-07-06T14:00:00Z"), Schedule.Daily(14, 0).nextRun(now, utc))
    }
    @Test fun nextRun_hourly() {
        // now = 10:30. Hourly :45 -> today 10:45.
        assertEquals(at("2026-07-06T10:45:00Z"), Schedule.Hourly(45).nextRun(now, utc))
        // Hourly :15 already passed this hour -> 11:15.
        assertEquals(at("2026-07-06T11:15:00Z"), Schedule.Hourly(15).nextRun(now, utc))
    }
    @Test fun nextRun_weekly() {
        // now = Mon 10:30. Weekly {WED} at 09:00 -> Wed 2026-07-08 09:00.
        assertEquals(at("2026-07-08T09:00:00Z"), Schedule.Weekly(setOf(Weekday.WED), 9, 0).nextRun(now, utc))
        // Weekly {MON} at 09:00 -> passed today -> next Mon.
        assertEquals(at("2026-07-13T09:00:00Z"), Schedule.Weekly(setOf(Weekday.MON), 9, 0).nextRun(now, utc))
    }
    @Test fun nextRun_monthly() {
        // now = Jul 6. Monthly day 15 09:00 -> Jul 15 this month.
        assertEquals(at("2026-07-15T09:00:00Z"), Schedule.Monthly(15, 9, 0).nextRun(now, utc))
        // Monthly day 3 09:00 -> passed -> Aug 3.
        assertEquals(at("2026-08-03T09:00:00Z"), Schedule.Monthly(3, 9, 0).nextRun(now, utc))
    }
    @Test fun nextRun_advanced_null() {
        assertNull(Schedule.Advanced("*/15 * * * *").nextRun(now, utc))
    }

    @Test fun parseCron_presets_and_fallback() {
        assertEquals(Schedule.Hourly(5), parseCron("5 * * * *"))
        assertEquals(Schedule.Daily(9, 0), parseCron("0 9 * * *"))
        assertEquals(Schedule.Weekly(setOf(Weekday.MON), 2, 0), parseCron("0 2 * * 1"))
        assertEquals(Schedule.Weekly(setOf(Weekday.MON, Weekday.WED, Weekday.FRI), 9, 0), parseCron("0 9 * * 1,3,5"))
        assertEquals(Schedule.Monthly(15, 3, 0), parseCron("0 3 15 * *"))
        assertEquals(Schedule.Advanced("*/15 9-17 * * 1-5"), parseCron("*/15 9-17 * * 1-5"))
        assertEquals(Schedule.Advanced("garbage"), parseCron("garbage"))
    }
    @Test fun parseCron_roundtrips_presets() {
        for (x in listOf("5 * * * *", "0 9 * * *", "0 2 * * 1", "0 9 * * 1,3,5", "0 3 15 * *")) {
            assertEquals(x, parseCron(x).toCron())
        }
    }
    @Test fun isValidCron_cases() {
        assertTrue(isValidCron("0 9 * * *"))
        assertTrue(isValidCron("*/15 9-17 * * 1-5"))
        assertFalse(isValidCron("0 9 * *"))      // 4 fields
        assertFalse(isValidCron("nope"))
        assertFalse(isValidCron("0 9 * * * *"))  // 6 fields
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*ScheduleTest*' --console=plain 2>&1 | tail -6`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Implement `Schedule.kt`**

```kotlin
package com.hermes.client.ui.cron

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

enum class Weekday(val cron: Int, val short: String) {
    SUN(0, "Sun"), MON(1, "Mon"), TUE(2, "Tue"), WED(3, "Wed"), THU(4, "Thu"), FRI(5, "Fri"), SAT(6, "Sat");

    companion object {
        fun fromCron(v: Int): Weekday? = values().firstOrNull { it.cron == v % 7 }
    }
}

sealed interface Schedule {
    data class Hourly(val minute: Int) : Schedule
    data class Daily(val hour: Int, val minute: Int) : Schedule
    data class Weekly(val days: Set<Weekday>, val hour: Int, val minute: Int) : Schedule
    data class Monthly(val dayOfMonth: Int, val hour: Int, val minute: Int) : Schedule
    data class Advanced(val expr: String) : Schedule
}

fun Schedule.toCron(): String = when (this) {
    is Schedule.Hourly -> "$minute * * * *"
    is Schedule.Daily -> "$minute $hour * * *"
    is Schedule.Weekly -> "$minute $hour * * ${days.sortedBy { it.cron }.joinToString(",") { it.cron.toString() }}"
    is Schedule.Monthly -> "$minute $hour $dayOfMonth * *"
    is Schedule.Advanced -> expr
}

private fun hm(h: Int, m: Int) = "%02d:%02d".format(h, m)

fun Schedule.describe(): String = when (this) {
    is Schedule.Hourly -> if (minute == 0) "Every hour" else "Every hour at :%02d".format(minute)
    is Schedule.Daily -> "Every day at ${hm(hour, minute)}"
    is Schedule.Weekly ->
        if (days.size == 7) "Every day at ${hm(hour, minute)}"
        else "${days.sortedBy { it.cron }.joinToString(", ") { it.short }} at ${hm(hour, minute)}"
    is Schedule.Monthly -> "Day $dayOfMonth of each month at ${hm(hour, minute)}"
    is Schedule.Advanced -> expr
}

fun Schedule.nextRun(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
    val now = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDateTime()
    fun ms(dt: LocalDateTime) = dt.atZone(zone).toInstant().toEpochMilli()
    return when (this) {
        is Schedule.Hourly -> {
            var c = now.withMinute(minute).withSecond(0).withNano(0)
            if (!c.isAfter(now)) c = c.plusHours(1)
            ms(c)
        }
        is Schedule.Daily -> {
            var c = now.toLocalDate().atTime(hour, minute)
            if (!c.isAfter(now)) c = c.plusDays(1)
            ms(c)
        }
        is Schedule.Weekly -> {
            if (days.isEmpty()) return null
            val wanted = days.map { it.cron }.toSet() // 0=Sun..6=Sat
            var c = now.toLocalDate().atTime(hour, minute)
            repeat(8) {
                val dow = c.dayOfWeek.value % 7 // java: Mon=1..Sun=7 -> 0=Sun..6=Sat
                if (dow in wanted && c.isAfter(now)) return ms(c)
                c = c.plusDays(1).toLocalDate().atTime(hour, minute)
            }
            null
        }
        is Schedule.Monthly -> {
            var date = now.toLocalDate()
            repeat(13) {
                if (dayOfMonth <= date.lengthOfMonth()) {
                    val c = date.withDayOfMonth(dayOfMonth).atTime(hour, minute)
                    if (c.isAfter(now)) return ms(c)
                }
                date = date.plusMonths(1).withDayOfMonth(1)
            }
            null
        }
        is Schedule.Advanced -> null
    }
}

private fun String.toIntOrStar(): Int? = toIntOrNull()

fun parseCron(expr: String): Schedule {
    val f = expr.trim().split(Regex("\\s+"))
    if (f.size != 5) return Schedule.Advanced(expr.trim())
    val (minS, hourS, domS, monS, dowS) = f
    val min = minS.toIntOrStar()
    val hour = hourS.toIntOrStar()
    val dom = domS.toIntOrStar()
    val star = { s: String -> s == "*" }
    return when {
        min != null && star(hourS) && star(domS) && star(monS) && star(dowS) -> Schedule.Hourly(min)
        min != null && hour != null && star(domS) && star(monS) && star(dowS) -> Schedule.Daily(hour, min)
        min != null && hour != null && star(domS) && star(monS) && dowIsList(dowS) ->
            Schedule.Weekly(dowS.split(",").mapNotNull { Weekday.fromCron(it.toInt()) }.toSet(), hour, min)
        min != null && hour != null && dom != null && star(monS) && star(dowS) -> Schedule.Monthly(dom, hour, min)
        else -> Schedule.Advanced(expr.trim())
    }
}

private fun dowIsList(s: String): Boolean =
    s != "*" && s.split(",").all { it.toIntOrNull()?.let { n -> n in 0..7 } == true }

fun isValidCron(expr: String): Boolean {
    val f = expr.trim().split(Regex("\\s+"))
    if (f.size != 5) return false
    val token = Regex("^(\\*|(\\d+([-/,]\\d+)*))$")
    return f.all { token.matches(it) }
}
```
(Note: `parseCron`'s destructuring of a 5-element list uses `component1..5`; if the linter dislikes it, index `f[0]..f[4]`.)

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*ScheduleTest*' --console=plain 2>&1 | tail -6`
Expected: PASS (all cases). Fix any off-by-one in `nextRun`/`describe` until green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/cron/Schedule.kt app/src/test/java/com/hermes/client/ui/cron/ScheduleTest.kt
git commit -m "feat(cron): structured Schedule model (toCron/describe/nextRun/parseCron/isValidCron)"
```

---

### Task 2: `CronEditViewModel` — structured state + round-trip fix

**Files:** Modify `app/src/main/java/com/hermes/client/ui/cron/CronEditScreen.kt`

**Interfaces:** Consumes `Schedule`, `parseCron`, `Schedule.toCron`, `isValidCron` (Task 1). Produces `CronEditState.schedule: Schedule`, `setSchedule(Schedule)`.

- [ ] **Step 1: Change the state + VM**

In `CronEditState`, replace `val schedule: String = ""` with `val schedule: Schedule = Schedule.Daily(9, 0)`. In `CronEditViewModel`:
- replace `fun setSchedule(v: String)` with `fun setSchedule(v: Schedule) { _state.value = _state.value.copy(schedule = v) }`.
- `load(id)` edit branch: where it currently sets `schedule = job.scheduleText`, use `schedule = parseCron(job.schedule?.expr ?: job.scheduleText)`.
- `save()`: replace the blank-schedule check + the `createCron/updateCron(... s.schedule ...)` calls:
```kotlin
    fun save() = viewModelScope.launch {
        val s = _state.value
        val advancedInvalid = s.schedule is Schedule.Advanced && !isValidCron((s.schedule as Schedule.Advanced).expr)
        if (s.prompt.isBlank() || advancedInvalid) {
            _state.value = s.copy(message = if (s.prompt.isBlank()) "Prompt is required" else "Schedule is not a valid cron expression")
            return@launch
        }
        val cron = s.schedule.toCron()
        runCatching {
            if (s.isNew) tools.createCron(s.prompt, cron, s.name, profile)
            else tools.updateCron(jobId, s.prompt, cron, s.name, profile)
        }.onSuccess { _state.value = s.copy(saved = true) }
            .onFailure { _state.value = s.copy(message = "Save failed: ${it.message}") }
    }
```

- [ ] **Step 2: Compile (UI still references old `schedule` string — Task 3 fixes the composable; keep this task VM-only by leaving the composable temporarily consuming `.describe()`)**

To keep this task independently compilable, in the `CronEditScreen` composable temporarily bind the existing schedule `OutlinedTextField` to `state.schedule.describe()` (read-only) — Task 3 replaces it with the builder. Concretely, change the schedule field's `value =` to `state.schedule.describe()` and its `onValueChange = {}` (no-op) so it compiles; Task 3 removes it.

Run: `./gradlew :app:compileDebugKotlin --console=plain 2>&1 | grep -E "^e: |BUILD" | head`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/cron/CronEditScreen.kt
git commit -m "fix(cron): edit seeds schedule from raw expr (structured Schedule state); no round-trip corruption"
```

---

### Task 3: Schedule builder UI (`CronEditScreen`)

**Files:** Modify `app/src/main/java/com/hermes/client/ui/cron/CronEditScreen.kt`

**Interfaces:** Consumes `state.schedule`/`vm.setSchedule` (Task 2), `Schedule.describe/nextRun/toCron`, `isValidCron` (Task 1).

- [ ] **Step 1: Replace the schedule field with a builder**

Remove the temporary schedule `OutlinedTextField` (from Task 2 Step 2) and its caption. Insert a `ScheduleBuilder(schedule = state.schedule, onChange = vm::setSchedule, nowMs = System.currentTimeMillis())` composable (define it private in this file):
```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleBuilder(schedule: Schedule, onChange: (Schedule) -> Unit, nowMs: Long) {
    val accent = com.hermes.client.ui.theme.LocalProfileAccent.current
    val kinds = listOf("Hourly", "Daily", "Weekly", "Monthly", "Advanced")
    val current = when (schedule) {
        is Schedule.Hourly -> 0; is Schedule.Daily -> 1; is Schedule.Weekly -> 2
        is Schedule.Monthly -> 3; is Schedule.Advanced -> 4
    }
    // time carried across kind switches
    val (h, m) = when (schedule) {
        is Schedule.Daily -> schedule.hour to schedule.minute
        is Schedule.Weekly -> schedule.hour to schedule.minute
        is Schedule.Monthly -> schedule.hour to schedule.minute
        is Schedule.Hourly -> 9 to schedule.minute
        is Schedule.Advanced -> 9 to 0
    }
    Column(Modifier.fillMaxWidth()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            kinds.forEachIndexed { i, label ->
                SegmentedButton(
                    selected = current == i,
                    onClick = {
                        onChange(when (i) {
                            0 -> Schedule.Hourly(m.coerceIn(0, 59))
                            1 -> Schedule.Daily(h, m)
                            2 -> Schedule.Weekly(setOf(Weekday.MON), h, m)
                            3 -> Schedule.Monthly(1, h, m)
                            else -> Schedule.Advanced(schedule.toCron())
                        })
                    },
                    shape = SegmentedButtonDefaults.itemShape(i, kinds.size),
                    colors = SegmentedButtonDefaults.colors(activeContainerColor = accent.accent, activeContentColor = accent.onAccent),
                ) { Text(label, maxLines = 1) }
            }
        }
        Spacer(Modifier.height(12.dp))
        when (schedule) {
            is Schedule.Hourly -> MinutePicker(schedule.minute) { onChange(schedule.copy(minute = it)) }
            is Schedule.Daily -> TimeRow(schedule.hour, schedule.minute) { hh, mm -> onChange(schedule.copy(hour = hh, minute = mm)) }
            is Schedule.Weekly -> {
                WeekdayChips(schedule.days) { onChange(schedule.copy(days = it)) }
                Spacer(Modifier.height(8.dp))
                TimeRow(schedule.hour, schedule.minute) { hh, mm -> onChange(schedule.copy(hour = hh, minute = mm)) }
            }
            is Schedule.Monthly -> {
                DayOfMonthPicker(schedule.dayOfMonth) { onChange(schedule.copy(dayOfMonth = it)) }
                Spacer(Modifier.height(8.dp))
                TimeRow(schedule.hour, schedule.minute) { hh, mm -> onChange(schedule.copy(hour = hh, minute = mm)) }
            }
            is Schedule.Advanced -> {
                val valid = isValidCron(schedule.expr)
                OutlinedTextField(
                    schedule.expr, { onChange(Schedule.Advanced(it)) },
                    label = { Text("Schedule (cron, e.g. 0 9 * * *)") }, singleLine = true,
                    isError = schedule.expr.isNotBlank() && !valid, modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (schedule.expr.isNotBlank() && !valid) "Not a valid 5-field cron expression" else "min hour day month weekday",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (schedule.expr.isNotBlank() && !valid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        val next = schedule.nextRun(nowMs)?.let { epochMs ->
            " · Next: " + com.hermes.client.ui.util.formatIso(java.time.Instant.ofEpochMilli(epochMs).toString())
        }.orEmpty()
        Text(schedule.describe() + next, style = MaterialTheme.typography.bodyMedium, color = accent.accent)
    }
}
```
Add the helper composables in the same file: `MinutePicker(minute, onPick)` and `DayOfMonthPicker(day, onPick)` as `ExposedDropdownMenuBox`/`DropdownMenu`s (0–59 / 1–28); `WeekdayChips(days, onChange)` as a `FlowRow`/`Row` of `FilterChip`s (enforce ≥1 — ignore a tap that would empty the set); `TimeRow(hour, minute, onPick)` as a clickable "At HH:MM" `OutlinedButton` opening a Material3 `TimePicker` in an `AlertDialog` (`rememberTimePickerState(hour, minute, is24Hour = true)`, apply on confirm). Keep imports tidy.

- [ ] **Step 2: Guard Save on invalid Advanced**

Where the `Button(onClick = { vm.save() }) { Text(if (state.isNew) "Create" else "Save") }` is, set `enabled = state.prompt.isNotBlank() && (state.schedule !is Schedule.Advanced || isValidCron((state.schedule as Schedule.Advanced).expr))`.

- [ ] **Step 3: Compile + assemble**

Run: `./gradlew :app:compileDebugKotlin --console=plain 2>&1 | grep -E "^e: |BUILD" | head` → `BUILD SUCCESSFUL`.
Run: `./gradlew :app:assembleBeta --console=plain 2>&1 | grep -E "^e: |BUILD" | tail -2` → `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/cron/CronEditScreen.kt
git commit -m "feat(cron): friendly schedule builder (presets + pickers + live summary/next-run + advanced)"
```

---

### Task 4: List row quick actions (`CronViewModel` + `CronScreen`)

**Files:** Modify `app/src/main/java/com/hermes/client/ui/cron/CronViewModel.kt`, `app/src/main/java/com/hermes/client/ui/cron/CronScreen.kt`

- [ ] **Step 1: Add actions to `CronViewModel`**

Add `enum class CronAction { PAUSE, RESUME, RUN }`, a `message: String? = null` field to `CronUiState`, `fun clearMessage()`, and:
```kotlin
    fun runAction(jobId: String, name: String, action: CronAction) = viewModelScope.launch {
        val p = _state.value.profile
        val ok = runCatching {
            when (action) {
                CronAction.PAUSE -> tools.pauseCron(jobId, p)
                CronAction.RESUME -> tools.resumeCron(jobId, p)
                CronAction.RUN -> tools.triggerCron(jobId, p)
            }
        }.isSuccess
        val verb = when (action) { CronAction.PAUSE -> "Paused"; CronAction.RESUME -> "Resumed"; CronAction.RUN -> "Triggered" }
        _state.value = _state.value.copy(message = if (ok) "$verb $name" else "Couldn't $verb.lowercase() $name")
        if (ok) load()
    }
    fun clearMessage() { _state.value = _state.value.copy(message = null) }
```

- [ ] **Step 2: Row overflow menu in `CronScreen`**

Add a `trailingContent` to the row `ListItem`: an `IconButton(onClick = { menuFor = job.id })` with `Icon(Icons.Rounded.MoreVert, contentDescription = "Actions")`, and a `DropdownMenu(expanded = menuFor == job.id, onDismissRequest = { menuFor = null })` with items:
- `DropdownMenuItem(text = { Text(if (job.isPaused) "Resume" else "Pause") }, onClick = { vm.runAction(job.id, job.name ?: job.id, if (job.isPaused) CronAction.RESUME else CronAction.PAUSE); menuFor = null })`
- `DropdownMenuItem(text = { Text("Run now") }, onClick = { vm.runAction(job.id, job.name ?: job.id, CronAction.RUN); menuFor = null })`

Hoist `var menuFor by remember { mutableStateOf<String?>(null) }` in the list scope. Show the toast: `val context = LocalContext.current; LaunchedEffect(state.message) { state.message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.clearMessage() } }`. Row `clickable { onOpen(job.id) }` stays.

- [ ] **Step 3: Compile + suite**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --console=plain 2>&1 | grep -E "^e: |FAILED|BUILD" | head`
Expected: `BUILD SUCCESSFUL`, no `FAILED`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/cron/CronViewModel.kt app/src/main/java/com/hermes/client/ui/cron/CronScreen.kt
git commit -m "feat(cron): per-row pause/resume/run overflow menu on the list"
```

---

### Task 5: Mock endpoints + on-device verification

**Files:** Modify `$CLAUDE_JOB_DIR/tmp/mockgw.py` (test harness only — not committed to the repo).

- [ ] **Step 1: Add the missing cron endpoints to the mock**

In `$CLAUDE_JOB_DIR/tmp/mockgw.py`, add handlers so the edit/create/actions flow works against the mock:
- `GET /api/cron/jobs/<id>` → return a single job JSON with a nested `"schedule": {"kind": "cron", "expr": "0 9 * * *", "display": "Every day at 09:00"}`, plus `id`, `name`, `prompt`, `next_run_at`, `last_status`, `enabled`.
- `POST /api/cron/jobs` and `PUT /api/cron/jobs/<id>` → accept the JSON body, return `200` with the created/updated job echoed (include a `schedule.expr` echoing the posted `"schedule"` string).
- `POST /api/cron/jobs/<id>/{pause,resume,trigger}` → return `200 {}`.
Restart the mock.

- [ ] **Step 2: Build, install, verify**

`./gradlew :app:assembleBeta`; `adb -e install -r app/build/outputs/apk/beta/app-beta.apk`; connect to `http://10.0.2.2:8899`.
1. **New cron** (Cron → New FAB): the builder defaults to **Daily 09:00** with a live "Every day at 09:00 · Next: …" summary; switching to Hourly/Weekly/Monthly swaps the controls and updates the summary; Advanced shows the raw field and flags an invalid expression (Save disabled).
2. **Create** a Daily 08:30 job → it appears in the list overline as "Every day at 08:30".
3. **Edit** the seeded job (expr `0 9 * * *`) → the builder opens on **Daily 09:00** (not a raw string); changing only the prompt and saving keeps the schedule (round-trip holds).
4. **List row overflow** (⋮) → **Pause/Resume** and **Run now** show a toast and the row updates — no detail screen needed.
5. Everything tenant-accent tinted (green acme).

- [ ] **Step 3: Commit (only if verification-only fixups were needed in app code)**

```bash
git add -A
git commit -m "chore(cron): schedule-builder verification fixups"   # only if needed
```

---

## Self-Review

- **Spec coverage:** Element 1 (Schedule model) → Task 1 (+ full tests) ✓; Element 2 (builder UI) → Task 3 ✓; Element 3 (round-trip fix + structured state) → Task 2 ✓; Element 4 (list quick actions) → Task 4 ✓; on-device (needs mock endpoints) → Task 5 ✓. Deferred items (detail legibility, auto-name, empty-state templates) intentionally have no task, per the spec.
- **Placeholder scan:** Task 1 has complete code + tests; Tasks 2–4 give concrete code for the VM logic and the builder skeleton (the helper composables `MinutePicker`/`DayOfMonthPicker`/`WeekdayChips`/`TimeRow` are described with their exact controls). No TBD/TODO. The only conditional commit is Task 5 Step 3.
- **Type consistency:** `Schedule`(+subtypes), `Weekday`, `toCron`/`describe`/`nextRun(nowMs, zone)`/`parseCron`/`isValidCron` (Task 1) are used with the same signatures in Tasks 2–3; `CronEditState.schedule: Schedule` + `setSchedule(Schedule)` (Task 2) consumed by Task 3; `CronAction`/`runAction(jobId, name, action)`/`clearMessage` (Task 4) match between VM and screen. `com.hermes.client.ui.util.formatIso` takes an ISO string.

**Ordering:** Task 1 (pure) first. Task 2 (VM state) consumes Task 1 and keeps the file compilable via a temporary read-only field. Task 3 (builder UI) replaces that field and consumes Tasks 1–2. Task 4 is independent (`CronViewModel`/`CronScreen`). Task 5 verifies (mock + device). `CronEditScreen.kt` is touched by Tasks 2 → 3 in order.
