# Home Feed Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add segmented time-filter tabs, collapsible counted sections, and status pills to the Home / Mission Control feed — over the existing feed data, per-tenant-accent tinted.

**Architecture:** Pure filter/label helpers (`feedView`, `isSameDay`, `statusPill`) are unit-tested; the UI (a new `FeedTabs`, a collapsible `SectionHeader`, a `StatusPill`) renders from a filtered `FeedView` and per-page `rememberSaveable` state. The feed's data assembly (`groupActivity`, the VM) is untouched.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, JUnit.

## Global Constraints

- **No gateway/bridge API changes.** Compose UI over existing `MissionControlState` only.
- Multi-tenant isolation: all new chrome tints to `LocalProfileAccent.current` (per-tenant accent), never a hardcoded colour.
- JDK 21: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
- Compile: `./gradlew :app:compileDebugKotlin --console=plain`. Tests: `./gradlew :app:testDebugUnitTest --console=plain`. Beta: `./gradlew :app:assembleBeta`.
- **No AI/assistant attribution** in commits, files, or PRs.

## Grounding

- `ActivityModels.kt`: `enum ActivityKind { CONVERSATION, CRON }`; `ActivityItem(id, kind, title, subtitle, timestampMs: Long?, upcoming: Boolean, status: String?, route, sessionId)`; `ActivitySection(title, items)`; `const val LIVE_WINDOW_MS = 15*60_000L`; `groupActivity` yields titles "Live now"/"Upcoming"/"Recent".
- `NeedsYou.kt`: `data class CronAlert(jobId, name, reason, route, lastRunAtMs)`.
- `MissionControlScreen.kt`: `MissionControlScreen` (Scaffold topBar = `Column { HermesTopBar("Home"); ProfileSwitcher }`, `HorizontalPager` of `MissionControlPage`); `MissionControlPage` hoists `expandedIds` + `now` and calls `MissionControlContent`; `MissionControlContent(state, nowMs, …, onOpen, onRunNow, expandedIds, onToggle, responses, onRetryResponse)` is a `LazyColumn`; `SectionHeader(label, count, onClick)`; `ActivityRow(item, nowMs, expandable, isExpanded, response, onClick, onRetry, onOpenFull)`; `NeedsYouRow(...)`.
- `ui/util/Time.kt`: `isoToEpochMs`, `relativeTime`.
- `SingleChoiceSegmentedButtonRow`/`SegmentedButton` precedent: `ui/settings/AppearanceScreen.kt:45-53`.
- `LocalProfileAccent.current.{accent, onAccent, container, onContainer}` (`ui/theme/ProfileAccent.kt`).

---

### Task 1: Pure helpers — `isSameDay`, `FeedFilter`/`feedView`, `statusPill` (TDD)

**Files:**
- Modify: `app/src/main/java/com/hermes/client/ui/util/Time.kt`, `app/src/main/java/com/hermes/client/ui/activity/ActivityModels.kt`
- Test: `app/src/test/java/com/hermes/client/ui/activity/FeedViewTest.kt` (new)

**Interfaces:** Produces `isSameDay(aMs, bMs, zone): Boolean`; `enum FeedFilter { ALL, TODAY, UPCOMING, RECENT }`; `data class FeedView(needsYou, sections) { val count: Int }`; `feedView(sections, needsYou, nowMs, filter): FeedView`; `statusPill(item, nowMs): String?`.

- [ ] **Step 1: Write the failing tests** — create `FeedViewTest.kt`:

```kotlin
package com.hermes.client.ui.activity

import com.hermes.client.ui.util.isSameDay
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class FeedViewTest {
    private val utc = ZoneId.of("UTC")
    private val day = 24 * 60 * 60_000L
    private val t0 = 1_720_000_000_000L // fixed "now"

    private fun item(id: String, ts: Long?, upcoming: Boolean) =
        ActivityItem(id, ActivityKind.CRON, id, null, ts, upcoming, null, "r/$id")
    private fun alert(id: String) = CronAlert(id, id, CronAlertReason.FAILED, "cron_detail/$id")

    @Test fun isSameDay_true_within_day() {
        assertEquals(true, isSameDay(t0, t0 + 60_000, utc))
    }
    @Test fun isSameDay_false_next_day() {
        assertEquals(false, isSameDay(t0, t0 + day, utc))
    }

    private val sections = listOf(
        ActivitySection("Live now", listOf(item("live", t0 - 60_000, false))),
        ActivitySection("Upcoming", listOf(item("up-today", t0 + 60_000, true), item("up-future", t0 + 3 * day, true))),
        ActivitySection("Recent", listOf(item("old", t0 - 3 * day, false))),
    )
    private val needs = listOf(alert("a1"))

    @Test fun feedView_all_is_everything() {
        val v = feedView(sections, needs, t0, FeedFilter.ALL)
        assertEquals(1, v.needsYou.size)
        assertEquals(3, v.sections.size)
        assertEquals(1 + 4, v.count)
    }
    @Test fun feedView_today_keeps_needs_and_today_items() {
        val v = feedView(sections, needs, t0, FeedFilter.TODAY)
        assertEquals(1, v.needsYou.size)
        // today items: live (t0-60s), up-today (t0+60s) -> 2, in their sections; old & up-future dropped
        assertEquals(2, v.sections.sumOf { it.items.size })
        assertEquals(1 + 2, v.count)
    }
    @Test fun feedView_upcoming_only_upcoming_section_no_needs() {
        val v = feedView(sections, needs, t0, FeedFilter.UPCOMING)
        assertEquals(0, v.needsYou.size)
        assertEquals(listOf("Upcoming"), v.sections.map { it.title })
        assertEquals(2, v.count)
    }
    @Test fun feedView_recent_excludes_upcoming_and_needs() {
        val v = feedView(sections, needs, t0, FeedFilter.RECENT)
        assertEquals(0, v.needsYou.size)
        assertEquals(listOf("Live now", "Recent"), v.sections.map { it.title })
        assertEquals(2, v.count)
    }

    @Test fun statusPill_scheduled_live_none() {
        assertEquals("Scheduled", statusPill(item("s", t0 + day, true), t0))
        assertEquals("Live", statusPill(item("l", t0 - 60_000, false), t0))
        assertEquals(null, statusPill(item("o", t0 - 3 * day, false), t0))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*FeedViewTest*' --console=plain 2>&1 | tail -6`
Expected: FAIL — unresolved `isSameDay`/`FeedFilter`/`feedView`/`statusPill`.

- [ ] **Step 3: Add `isSameDay` to `Time.kt`**

```kotlin
fun isSameDay(aMs: Long, bMs: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Boolean =
    java.time.Instant.ofEpochMilli(aMs).atZone(zone).toLocalDate() ==
        java.time.Instant.ofEpochMilli(bMs).atZone(zone).toLocalDate()
```

- [ ] **Step 4: Add `FeedFilter`/`FeedView`/`feedView`/`statusPill` to `ActivityModels.kt`**

```kotlin
enum class FeedFilter { ALL, TODAY, UPCOMING, RECENT }

data class FeedView(val needsYou: List<CronAlert>, val sections: List<ActivitySection>) {
    val count: Int get() = needsYou.size + sections.sumOf { it.items.size }
}

/** Filter the assembled feed for the selected tab (grouping headers still apply within a filter). */
fun feedView(
    sections: List<ActivitySection>,
    needsYou: List<CronAlert>,
    nowMs: Long,
    filter: FeedFilter,
): FeedView = when (filter) {
    FeedFilter.ALL -> FeedView(needsYou, sections)
    FeedFilter.TODAY -> FeedView(
        needsYou,
        sections
            .map { s -> s.copy(items = s.items.filter { it.timestampMs != null && com.hermes.client.ui.util.isSameDay(it.timestampMs, nowMs) }) }
            .filter { it.items.isNotEmpty() },
    )
    FeedFilter.UPCOMING -> FeedView(emptyList(), sections.filter { it.title.equals("Upcoming", ignoreCase = true) })
    FeedFilter.RECENT -> FeedView(emptyList(), sections.filterNot { it.title.equals("Upcoming", ignoreCase = true) })
}

/** Short state label for an activity row, or null (plain recent items get no pill). */
fun statusPill(item: ActivityItem, nowMs: Long): String? = when {
    item.upcoming -> "Scheduled"
    item.timestampMs != null && (nowMs - item.timestampMs) in 0..LIVE_WINDOW_MS -> "Live"
    else -> null
}
```
(`import com.hermes.client.ui.util.isSameDay` at the top of `ActivityModels.kt`, or use the fully-qualified call shown.)

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*FeedViewTest*' --console=plain 2>&1 | tail -6`
Expected: PASS (all 8 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/util/Time.kt app/src/main/java/com/hermes/client/ui/activity/ActivityModels.kt app/src/test/java/com/hermes/client/ui/activity/FeedViewTest.kt
git commit -m "feat(activity): feed filter + status-pill pure helpers (isSameDay/feedView/statusPill)"
```

---

### Task 2: `FeedTabs` composable

**Files:** Create `app/src/main/java/com/hermes/client/ui/activity/FeedTabs.kt`

**Interfaces:** Consumes `FeedFilter`, `feedView(...).count`, `ActivitySection`, `CronAlert`, `LocalProfileAccent`. Produces `@Composable fun FeedTabs(sections, needsYou, nowMs, selected, onSelect, modifier)`.

- [ ] **Step 1: Create `FeedTabs.kt`**

```kotlin
package com.hermes.client.ui.activity

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermes.client.ui.theme.LocalProfileAccent

private val FILTERS = listOf(
    FeedFilter.ALL to "All",
    FeedFilter.TODAY to "Today",
    FeedFilter.UPCOMING to "Upcoming",
    FeedFilter.RECENT to "Recent",
)

/** Per-tenant-accent segmented time filter for the Home feed; counts reflect the current page. */
@Composable
fun FeedTabs(
    sections: List<ActivitySection>,
    needsYou: List<CronAlert>,
    nowMs: Long,
    selected: FeedFilter,
    onSelect: (FeedFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalProfileAccent.current
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        FILTERS.forEachIndexed { i, (filter, label) ->
            val count = feedView(sections, needsYou, nowMs, filter).count
            SegmentedButton(
                selected = selected == filter,
                onClick = { onSelect(filter) },
                shape = SegmentedButtonDefaults.itemShape(i, FILTERS.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = accent.accent,
                    activeContentColor = accent.onAccent,
                ),
            ) { Text("$label $count") }
        }
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin --console=plain 2>&1 | grep -E "^e: |BUILD" | head`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/activity/FeedTabs.kt
git commit -m "feat(activity): FeedTabs segmented time filter (accent-tinted, counts)"
```

---

### Task 3: Wire the tabs into `MissionControlPage`

**Files:** Modify `app/src/main/java/com/hermes/client/ui/activity/MissionControlScreen.kt`

**Interfaces:** Consumes `FeedTabs`, `FeedFilter`, `feedView`, `FeedView`.

- [ ] **Step 1: Add per-page filter state + render the tabs above the content**

In `MissionControlPage`, add near the other hoisted state: `var filter by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(FeedFilter.ALL) }`. Replace the single `MissionControlContent(...)` call with a `Column`:
```kotlin
        Column {
            FeedTabs(
                sections = state.sections,
                needsYou = state.needsYou,
                nowMs = now,
                selected = filter,
                onSelect = { filter = it },
            )
            MissionControlContent(
                state = state,
                view = feedView(state.sections, state.needsYou, now, filter),
                nowMs = now,
                // …forward the remaining existing args unchanged (onRetry, responses, expandedIds, onToggle, onOpen, onRunNow, onRetryResponse)…
            )
        }
```
(Keep every existing `MissionControlContent` argument; only add `view`.)

- [ ] **Step 2: Make `MissionControlContent` render from the `FeedView`**

Add a `view: FeedView` parameter to `MissionControlContent`. In its `LazyColumn`, change the feed body to read `view.needsYou` and `view.sections` instead of `state.needsYou`/`state.sections` (the needs-you header+items block and the `else -> view.sections.forEach { … }`). Keep the `when` guards reading `state` for `loading`/`error`, but base the empty check on the filtered view:
```kotlin
        if (view.needsYou.isNotEmpty()) {
            item(key = "needs-header") { /* SectionHeader — updated in Task 4 */ }
            items(view.needsYou, key = { "needs-${it.jobId}" }) { alert -> NeedsYouRow(alert, nowMs = nowMs, onClick = { onOpen(alert.route) }, onRunNow = { onRunNow(alert) }) }
        }
        when {
            state.loading && state.sections.isEmpty() -> item { LoadingState() }
            state.error != null -> item { ErrorState(message = state.error!!, onRetry = onRetry) }
            view.needsYou.isEmpty() && view.sections.isEmpty() -> item {
                EmptyState(title = "Nothing here", subtitle = "Nothing matches this filter for this profile yet.")
            }
            else -> view.sections.forEach { section ->
                item(key = "h-${section.title}") { /* SectionHeader — updated in Task 4 */ }
                items(section.items, key = { it.id }) { activity -> /* ActivityRow — unchanged this task */ }
            }
        }
```
(This task keeps `SectionHeader`/`ActivityRow` call sites as-is except for swapping the data source to `view`; Tasks 4 and 5 change those composables.)

- [ ] **Step 3: Compile**

Run: `./gradlew :app:compileDebugKotlin --console=plain 2>&1 | grep -E "^e: |BUILD" | head`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/activity/MissionControlScreen.kt
git commit -m "feat(home): wire FeedTabs — filter the feed per page"
```

---

### Task 4: Collapsible sections

**Files:** Modify `app/src/main/java/com/hermes/client/ui/activity/MissionControlScreen.kt`

- [ ] **Step 1: Hoist collapse state in `MissionControlPage`**

```kotlin
    var collapsed by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(emptySet<String>()) }
    val onToggleSection: (String) -> Unit = { title -> collapsed = if (title in collapsed) collapsed - title else collapsed + title }
```
Pass `collapsed = collapsed` and `onToggleSection = onToggleSection` into `MissionControlContent` (add both params).

- [ ] **Step 2: Make `SectionHeader` a collapse toggle**

```kotlin
@Composable
private fun SectionHeader(label: String, count: Int, collapsed: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label.uppercase(), style = MaterialTheme.typography.titleSmall, color = LocalProfileAccent.current.accent, modifier = Modifier.weight(1f))
        Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(
            if (collapsed) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
            contentDescription = if (collapsed) "Expand $label" else "Collapse $label",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
```
Add imports `androidx.compose.material.icons.rounded.ExpandMore` / `ExpandLess` (the per-row expander already uses these — likely already imported).

- [ ] **Step 3: Gate the items on collapse state in `MissionControlContent`**

Needs-you: header passes `collapsed = "Needs you" in collapsed, onToggle = { onToggleSection("Needs you") }`; render `items(view.needsYou, …)` only when `"Needs you" !in collapsed`. Each section: header passes `collapsed = section.title in collapsed, onToggle = { onToggleSection(section.title) }`; render `items(section.items, …)` only when `section.title !in collapsed`.

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileDebugKotlin --console=plain 2>&1 | grep -E "^e: |BUILD" | head`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/activity/MissionControlScreen.kt
git commit -m "feat(home): collapsible section headers (persisted)"
```

---

### Task 5: Status pills on rows

**Files:** Modify `app/src/main/java/com/hermes/client/ui/activity/MissionControlScreen.kt`

- [ ] **Step 1: Add a `StatusPill` composable + use it in `ActivityRow`**

Add:
```kotlin
@Composable
private fun StatusPill(label: String) {
    val accent = LocalProfileAccent.current
    androidx.compose.material3.Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
        color = accent.container,
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = accent.onContainer,
        )
    }
}
```
In `ActivityRow`, compute `val pill = statusPill(item, nowMs)` and replace `trailingContent`:
```kotlin
            trailingContent = if (pill != null || expandable) {
                {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        pill?.let { StatusPill(it); Spacer(Modifier.width(6.dp)) }
                        if (expandable) {
                            Icon(
                                if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = if (isExpanded) "Collapse response" else "Show response",
                            )
                        }
                    }
                }
            } else {
                null
            },
```
Add `androidx.compose.foundation.layout.Row`/`Spacer`/`width` imports if missing.

- [ ] **Step 2: Compile + full suite + assemble**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --console=plain 2>&1 | grep -E "^e: |FAILED|BUILD" | head`
Expected: `BUILD SUCCESSFUL`, no `FAILED`.
Run: `./gradlew :app:assembleBeta --console=plain 2>&1 | grep -E "^e: |BUILD" | tail -2`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/activity/MissionControlScreen.kt
git commit -m "feat(home): status pills on activity rows (Scheduled/Live, accent-tinted)"
```

---

### Task 6: On-device verification

**Files:** none (verification only).

- [ ] **Step 1: Build + install**

`./gradlew :app:assembleBeta` then `adb -e install -r app/build/outputs/apk/beta/app-beta.apk`; point at the mock (`http://10.0.2.2:8899`), which has cron (upcoming/failed) + sessions under acme.

- [ ] **Step 2: Verify**

1. Home shows `All / Today / Upcoming / Recent` tabs (accent-tinted) under the tenant switcher, each with a count; tapping a tab filters the feed; counts reflect the active profile.
2. Tapping a section header collapses/expands it (⌃/⌄); state survives scroll and a rotation.
3. Rows show a "Scheduled" pill (upcoming cron) and "Live" (recent activity within 15 min); plain recent chats show none; Needs-you rows still show Run-now.
4. Swiping to another profile (globex) keeps its own tab + collapse state and re-tints everything to that tenant's accent.

- [ ] **Step 3: Commit (only if verification-only fixups were needed)**

```bash
git add -A
git commit -m "chore(home): feed-refresh verification fixups"   # only if needed
```

---

## Self-Review

- **Spec coverage:** Element 1 (tabs) → Task 1 (`FeedFilter`/`feedView`/`isSameDay` + tests) + Task 2 (`FeedTabs`) + Task 3 (wiring) ✓; Element 2 (collapsible) → Task 4 ✓; Element 3 (pills) → Task 1 (`statusPill` + test) + Task 5 (`StatusPill` + `ActivityRow`) ✓; on-device → Task 6 ✓. Deferred items (time-rail, avatar-dropdown, real status threading) intentionally have no task, per the spec.
- **Placeholder scan:** every code step shows full code; the Task-3 "…forward the remaining existing args…" refers to concrete existing parameters named in the Grounding block; the only conditional is the Task-6 verification-only commit.
- **Type consistency:** `FeedFilter`, `FeedView(needsYou, sections).count`, `feedView(sections, needsYou, nowMs, filter)`, `isSameDay(aMs, bMs, zone)`, `statusPill(item, nowMs)`, `SectionHeader(label, count, collapsed, onToggle)`, `FeedTabs(sections, needsYou, nowMs, selected, onSelect, modifier)`, `StatusPill(label)` are consistent across tasks; `LocalProfileAccent.current.{accent,onAccent,container,onContainer}` matches the theme.

**Ordering:** Task 1 (pure) first. `MissionControlScreen.kt` is touched by Tasks 3 → 4 → 5 — run strictly in order (each builds on the prior's `MissionControlContent`/`SectionHeader`/`ActivityRow` shape). Task 2 (new file) can precede Task 3. Task 6 verifies.
