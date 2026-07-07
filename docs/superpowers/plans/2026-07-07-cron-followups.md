# Cron Follow-ups Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cron polish — a legible detail status card + expandable error, a prompt-derived list name (no opaque ids), and empty-state quick-start templates.

**Architecture:** A pure `cronDisplayName` helper and a `CronTemplate` list (unit-tested) back the display fallback and the templates; the templates pre-fill the create form by branching a distinguishable `id` in `CronEditViewModel.load` (no new nav route). All over the existing cron REST.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, JUnit.

## Global Constraints

- **No gateway/bridge API changes.** Compose UI + existing cron REST/DTOs only.
- Multi-tenant isolation: new chrome tints to `LocalProfileAccent.current`, never hardcoded.
- JDK 21: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
- Compile: `./gradlew :app:compileDebugKotlin --console=plain`. Tests: `./gradlew :app:testDebugUnitTest --console=plain`. Beta: `./gradlew :app:assembleBeta`.
- **No AI/assistant attribution** in commits, files, or PRs.

## Grounding

- `ui/cron/Schedule.kt`: `Schedule.Daily(hour,minute)` / `Weekly(days:Set<Weekday>,hour,minute)` / `Hourly(minute)`; `Weekday.MON`; `Schedule.toCron()`; `isValidCron(expr)`.
- `ui/cron/CronScreen.kt`: `CronScreen(onMenu, onOpen, onNew: () -> Unit, vm)`; row `headlineContent = { Text(job.name ?: job.id) }`; empty branch `EmptyState(title="No cron jobs", subtitle=…)`; the New FAB `onClick = onNew`; `CronViewModel` gives `state.jobs`. `LocalProfileAccent.current.accent`.
- `ui/cron/CronEditScreen.kt`: `CronEditState(name, schedule: Schedule = Schedule.Daily(9,0), prompt, isNew, loading, saved, message)`; `CronEditViewModel.load(id)` (`id=="new"` → `CronEditState(isNew=true)`; else fetch via `tools.cronJob`).
- `ui/cron/CronDetailScreen.kt`: private `Field(label,value)` (a `Row` of two `Text`s); Schedule/Status/Next-run/Last-run are 4 `Field`s; `lastError` = `Text(it, color=error, style=bodySmall, maxLines=3, overflow=Ellipsis)`; then the action Row + PROMPT + RUN HISTORY.
- `ui/nav/HermesNav.kt`: `composable("cron") { CronScreen(onMenu=back, onOpen={id->nav.navigate("cron_detail/$id")}, onNew={ nav.navigate("cron_edit/new") }) }`; `composable("cron_edit/{id}")` accepts any `{id}` string.
- `CronJobDto.prompt: String?` (on the list payload). `EmptyState` (`ui/components/ScreenStates.kt`) supports only a single action button.

---

### Task 1: Pure helpers — `cronDisplayName` + `CronTemplates` (TDD)

**Files:**
- Create: `app/src/main/java/com/hermes/client/ui/cron/CronDisplay.kt`, `app/src/main/java/com/hermes/client/ui/cron/CronTemplates.kt`
- Test: `app/src/test/java/com/hermes/client/ui/cron/CronFollowupsTest.kt`

**Interfaces:** Produces `cronDisplayName(name, prompt, id, maxLen=40): String`; `data class CronTemplate(id, label, schedule, prompt)`; `val CRON_TEMPLATES: List<CronTemplate>`; `cronTemplate(id): CronTemplate?`.

- [ ] **Step 1: Write the failing tests** — create `CronFollowupsTest.kt`:

```kotlin
package com.hermes.client.ui.cron

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CronFollowupsTest {
    @Test fun displayName_uses_name_when_present() {
        assertEquals("Nightly summary", cronDisplayName("  Nightly summary  ", "some prompt", "c1"))
    }
    @Test fun displayName_falls_back_to_prompt_snippet() {
        assertEquals("Back up the DB", cronDisplayName("", "Back up the DB", "c1"))
        assertEquals("Back up the DB", cronDisplayName(null, "Back up the DB", "c1"))
    }
    @Test fun displayName_flattens_and_truncates_long_prompt() {
        val long = "Summarize yesterday's deploys\nand open incidents across all of my projects"
        val out = cronDisplayName(null, long, "c1", maxLen = 20)
        assertTrue(out.endsWith("…"))
        assertTrue(out.length <= 21)
        assertTrue(!out.contains("\n"))
    }
    @Test fun displayName_falls_back_to_id() {
        assertEquals("c9", cronDisplayName(null, null, "c9"))
        assertEquals("c9", cronDisplayName("  ", "  ", "c9"))
    }
    @Test fun cronTemplate_lookup() {
        assertEquals(Schedule.Daily(9, 0), cronTemplate("new_daily")?.schedule)
        assertEquals(Schedule.Weekly(setOf(Weekday.MON), 2, 0), cronTemplate("new_weekly")?.schedule)
        assertEquals(Schedule.Hourly(0), cronTemplate("new_hourly")?.schedule)
        assertNull(cronTemplate("new"))
        assertNull(cronTemplate("nope"))
    }
    @Test fun all_templates_emit_valid_cron() {
        CRON_TEMPLATES.forEach { assertTrue(it.id, isValidCron(it.schedule.toCron())) }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*CronFollowupsTest*' --console=plain 2>&1 | tail -6`
Expected: FAIL — unresolved `cronDisplayName`/`cronTemplate`/`CRON_TEMPLATES`.

- [ ] **Step 3: Create `CronDisplay.kt`**

```kotlin
package com.hermes.client.ui.cron

/** List/display name for a cron job: its name, else a one-line prompt snippet, else its id. */
fun cronDisplayName(name: String?, prompt: String?, id: String, maxLen: Int = 40): String {
    name?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    prompt?.replace("\n", " ")?.trim()?.takeIf { it.isNotEmpty() }?.let {
        return if (it.length <= maxLen) it else it.take(maxLen).trimEnd() + "…"
    }
    return id
}
```

- [ ] **Step 4: Create `CronTemplates.kt`**

```kotlin
package com.hermes.client.ui.cron

/** A one-tap starting point for a new cron job (a preset schedule + a starter prompt). */
data class CronTemplate(val id: String, val label: String, val schedule: Schedule, val prompt: String)

val CRON_TEMPLATES = listOf(
    CronTemplate(
        "new_daily", "Daily summary · 9:00", Schedule.Daily(9, 0),
        "Summarize what happened across my projects yesterday — deploys, incidents, and anything that needs my attention.",
    ),
    CronTemplate(
        "new_weekly", "Weekly audit · Mon 2:00", Schedule.Weekly(setOf(Weekday.MON), 2, 0),
        "Run a dependency and security audit and list anything that needs attention.",
    ),
    CronTemplate(
        "new_hourly", "Hourly check", Schedule.Hourly(0),
        "Check for anything urgent that needs my attention and summarize it.",
    ),
)

fun cronTemplate(id: String): CronTemplate? = CRON_TEMPLATES.firstOrNull { it.id == id }
```

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*CronFollowupsTest*' --console=plain 2>&1 | tail -6`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/cron/CronDisplay.kt app/src/main/java/com/hermes/client/ui/cron/CronTemplates.kt app/src/test/java/com/hermes/client/ui/cron/CronFollowupsTest.kt
git commit -m "feat(cron): cronDisplayName + CronTemplates pure helpers"
```

---

### Task 2: List name fallback (`CronScreen`)

**Files:** Modify `app/src/main/java/com/hermes/client/ui/cron/CronScreen.kt`

**Interfaces:** Consumes `cronDisplayName` (Task 1).

- [ ] **Step 1: Use `cronDisplayName` in the row headline**

Change `headlineContent = { Text(job.name ?: job.id) }` to `headlineContent = { Text(cronDisplayName(job.name, job.prompt, job.id)) }`.

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin --console=plain 2>&1 | grep -E "^e: |BUILD" | head`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/cron/CronScreen.kt
git commit -m "feat(cron): list rows show a prompt-derived name instead of the job id"
```

---

### Task 3: Empty-state quick-start templates

**Files:** Modify `app/src/main/java/com/hermes/client/ui/cron/CronScreen.kt`, `app/src/main/java/com/hermes/client/ui/nav/HermesNav.kt`, `app/src/main/java/com/hermes/client/ui/cron/CronEditScreen.kt`

**Interfaces:** Consumes `CRON_TEMPLATES`, `cronTemplate` (Task 1). Produces `onNew: (String) -> Unit`.

- [ ] **Step 1: `CronScreen` — `onNew: (String) -> Unit` + custom empty layout**

Change the `CronScreen` signature `onNew: () -> Unit` → `onNew: (String) -> Unit`. The New FAB's `onClick` → `{ onNew("new") }`. Replace the `state.jobs.isEmpty()` branch's `EmptyState(...)` call with `CronEmpty(onNew = onNew)`, and add:
```kotlin
@Composable
private fun CronEmpty(onNew: (String) -> Unit) {
    val accent = com.hermes.client.ui.theme.LocalProfileAccent.current
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.Schedule, contentDescription = null, tint = accent.accent, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text("No cron jobs", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text("Start from a template:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        CRON_TEMPLATES.forEach { t ->
            OutlinedButton(onClick = { onNew(t.id) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(t.label)
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { onNew("new") }) { Text("New cron job") }
    }
}
```
Add imports as needed (`androidx.compose.foundation.layout.*`, `Arrangement`, `Alignment`, `Icons.Rounded.Schedule`, `androidx.compose.material3.{OutlinedButton,Button,Icon,Text}`, `size`).

- [ ] **Step 2: `HermesNav` — forward the seed**

Change the cron `composable` block: `onNew = { seed -> nav.navigate("cron_edit/$seed") }`.

- [ ] **Step 3: `CronEditViewModel.load` — seed from a template**

At the top of `load(id)`, before the existing `if (id == "new")` handling, replace that guard with:
```kotlin
    fun load(id: String) {
        jobId = id
        val template = cronTemplate(id)
        if (id == "new" || template != null) {
            _state.value = if (template != null) {
                CronEditState(schedule = template.schedule, prompt = template.prompt, isNew = true)
            } else {
                CronEditState(isNew = true)
            }
            return
        }
        // …existing fetch-for-edit path (unchanged)…
    }
```

- [ ] **Step 4: Compile + suite + assemble**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --console=plain 2>&1 | grep -E "^e: |FAILED|BUILD" | head` → `BUILD SUCCESSFUL`, no `FAILED`.
Run: `./gradlew :app:assembleBeta --console=plain 2>&1 | grep -E "^e: |BUILD" | tail -2` → `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/cron/CronScreen.kt app/src/main/java/com/hermes/client/ui/nav/HermesNav.kt app/src/main/java/com/hermes/client/ui/cron/CronEditScreen.kt
git commit -m "feat(cron): empty-state quick-start templates (pre-fill the builder)"
```

---

### Task 4: Detail status card + expandable error

**Files:** Modify `app/src/main/java/com/hermes/client/ui/cron/CronDetailScreen.kt`

- [ ] **Step 1: Group Status/Next-run/Last-run in a tonal card + expandable error**

Keep `Field("Schedule", job.scheduleText)` as-is above. Replace the three `Field("Status"…)`/`Field("Next run"…)`/`Field("Last run"…)` calls and the `lastError` block with:
```kotlin
            Spacer(Modifier.height(8.dp))
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.padding(12.dp)) {
                    Field("Status", if (job.isPaused) "Paused" else if (job.enabled) "Enabled" else "Disabled")
                    Field("Next run", formatIso(job.nextRunAt))
                    Field("Last run", formatIso(job.lastRunAt) + (job.lastStatus?.let { " · $it" } ?: ""))
                    job.lastError?.takeIf { it.isNotBlank() }?.let { err ->
                        var errorExpanded by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            err,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = if (errorExpanded) Int.MAX_VALUE else 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { errorExpanded = !errorExpanded },
                        )
                        Text(
                            if (errorExpanded) "Show less" else "Show more",
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalProfileAccent.current.accent,
                            modifier = Modifier.clickable { errorExpanded = !errorExpanded }.padding(top = 2.dp),
                        )
                    }
                }
            }
```
Add imports if missing: `androidx.compose.material3.Surface`, `androidx.compose.foundation.clickable`, `androidx.compose.foundation.layout.height`. Leave the action `Row`, PROMPT preview, and RUN HISTORY unchanged.

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin --console=plain 2>&1 | grep -E "^e: |BUILD" | head`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/cron/CronDetailScreen.kt
git commit -m "feat(cron): grouped status card + tap-to-expand error on detail"
```

---

### Task 5: Mock unnamed job + on-device verification

**Files:** Modify `$CLAUDE_JOB_DIR/tmp/mockgw.py` (test harness only — not committed).

- [ ] **Step 1: Add an unnamed cron job to the mock**

In `$CLAUDE_JOB_DIR/tmp/mockgw.py`'s `cron_jobs(profile)` list, add one entry with `name=None` and a real `prompt` (e.g. `dict(id="c5", name=None, schedule_display="Every day at 06:00", enabled=True, next_run_at=iso(12*HOUR), last_status="success", profile="acme", model="opus", prompt="Post the daily standup digest to the team channel.")`). Restart the mock.

- [ ] **Step 2: Build, install, verify**

`./gradlew :app:assembleBeta`; `adb -e install -r app/build/outputs/apk/beta/app-beta.apk`; connect to `http://10.0.2.2:8899`.
1. **Detail of a failed cron** (acme → "Nightly DB backup", status error): Status / Next run / Last run render inside one **tonal card**; the error shows **"Show more"** → tap expands the full text → **"Show less"**.
2. **Unnamed job** (c5): the cron list shows a **prompt-derived headline** ("Post the daily standup digest…"), not "c5".
3. **Empty state** (switch to **globex**, which has no crons): the empty layout shows the three template buttons + "New cron job"; tapping **"Daily summary · 9:00"** opens the builder **pre-filled** on Daily 09:00 with the starter prompt; tapping **"New cron job"** opens a blank Daily default.
4. Everything **tenant-accent** tinted (green acme / gold globex).

- [ ] **Step 3: Commit (only if verification-only fixups were needed in app code)**

```bash
git add -A
git commit -m "chore(cron): follow-ups verification fixups"   # only if needed
```

---

## Self-Review

- **Spec coverage:** Item 1 (detail legibility) → Task 4 ✓; Item 2 (name fallback) → Task 1 (`cronDisplayName` + test) + Task 2 (wire) ✓; Item 3 (empty-state templates) → Task 1 (`CRON_TEMPLATES` + test) + Task 3 (empty layout + nav + `load` branch) ✓; on-device (mock unnamed job) → Task 5 ✓. Deferred items (persisted name, error dialog) intentionally absent, per the spec.
- **Placeholder scan:** every code step has full code; the only conditional commit is Task 5 Step 3.
- **Type consistency:** `cronDisplayName(name, prompt, id, maxLen)`, `CronTemplate(id,label,schedule,prompt)`, `CRON_TEMPLATES`, `cronTemplate(id)` (Task 1) consumed by Tasks 2–3; `onNew: (String) -> Unit` consistent across `CronScreen`/`HermesNav`; `CronEditState(schedule, prompt, isNew)` matches the existing state; `Schedule.Daily/Weekly/Hourly`, `Weekday.MON`, `toCron()`, `isValidCron` match `Schedule.kt`.

**Ordering:** Task 1 (pure) first. Tasks 2 and 3 both touch `CronScreen.kt` — run 2 then 3. Task 3 also touches `HermesNav.kt` + `CronEditScreen.kt`. Task 4 is independent (`CronDetailScreen.kt`). Task 5 verifies (mock).
