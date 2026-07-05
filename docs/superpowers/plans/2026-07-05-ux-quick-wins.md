# UX Quick Wins (batch) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the Top-5 UX quick wins — styled error/empty states, full per-tenant accent wiring, a visible model chip, cron status in the list, and a chat-composer tenant token.

**Architecture:** Five independent Compose-only changes over existing infrastructure (`ScreenStates`, `LocalProfileAccent`, `needsAttention`, `ModelSelectorSheet`). One pure helper (`cronRowStatus`) is unit-tested; the rest is verified on-device.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, JUnit.

## Global Constraints

- **No gateway/bridge API changes.** Compose UI + existing state/data only. Material 3.
- Multi-tenant isolation preserved; no unrelated refactors.
- JDK 21: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`. compileSdk/targetSdk 36.
- Compile: `./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tail -5`. Tests: `./gradlew :app:testDebugUnitTest --console=plain`. Beta: `./gradlew :app:assembleBeta`.
- **No AI/assistant attribution** in commits, files, or PRs.

## File Structure

- Create `app/src/main/java/com/hermes/client/ui/cron/CronRowStatus.kt` + test (Task 1).
- Modify `ui/cron/CronDetailScreen.kt`, `ui/cron/CronScreen.kt`, `ui/tools/AgentsToolsScreen.kt`, `ui/usage/UsageScreen.kt` (Task 2).
- Modify ~8 screens: `colorScheme.primary` → accent (Task 3).
- Modify `ui/chat/ChatScreen.kt` (Task 4).
- Modify `ui/cron/CronScreen.kt` (Task 5).
- Create `app/src/main/java/com/hermes/client/ui/components/ProfileAvatar.kt`; modify `ui/chat/ChatScreen.kt`, `ui/nav/YouHubScreen.kt` (Task 6).

---

### Task 1: Pure `cronRowStatus` (TDD)

**Files:**
- Create: `app/src/main/java/com/hermes/client/ui/cron/CronRowStatus.kt`
- Test: `app/src/test/java/com/hermes/client/ui/cron/CronRowStatusTest.kt`

**Interfaces:**
- Consumes: `needsAttention(listOf(job), now)` / `CronAlertReason` from `com.hermes.client.ui.activity` (`ui/activity/NeedsYou.kt`).
- Produces: `enum class CronRowStatus { FAILED, OVERDUE, OK, PAUSED }`; `fun cronRowStatus(job: CronJobDto, nowMs: Long): CronRowStatus`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.hermes.client.ui.cron

import com.hermes.client.data.network.CronJobDto
import org.junit.Assert.assertEquals
import org.junit.Test

private const val NOW = 1_700_000_000_000L
private fun iso(ms: Long) = java.time.Instant.ofEpochMilli(ms).atOffset(java.time.ZoneOffset.UTC).toString()
private fun job(
    id: String = "j1", enabled: Boolean = true, state: String? = null,
    pausedAt: String? = null, nextRunAt: String? = null, lastStatus: String? = null,
) = CronJobDto(
    id = id, enabled = enabled, state = state, pausedAt = pausedAt,
    nextRunAt = nextRunAt, lastStatus = lastStatus,
)

class CronRowStatusTest {
    @Test fun failed_last_run_is_FAILED() {
        assertEquals(CronRowStatus.FAILED, cronRowStatus(job(lastStatus = "error"), NOW))
    }
    @Test fun overdue_is_OVERDUE() {
        assertEquals(CronRowStatus.OVERDUE, cronRowStatus(job(nextRunAt = iso(NOW - 10 * 60_000)), NOW))
    }
    @Test fun paused_or_disabled_not_failed_is_PAUSED() {
        assertEquals(CronRowStatus.PAUSED, cronRowStatus(job(state = "paused"), NOW))
        assertEquals(CronRowStatus.PAUSED, cronRowStatus(job(enabled = false), NOW))
    }
    @Test fun healthy_future_is_OK() {
        assertEquals(CronRowStatus.OK, cronRowStatus(job(lastStatus = "success", nextRunAt = iso(NOW + 3_600_000)), NOW))
    }
    @Test fun failed_takes_priority_over_paused() {
        assertEquals(CronRowStatus.FAILED, cronRowStatus(job(state = "paused", lastStatus = "error"), NOW))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*CronRowStatusTest*' --console=plain 2>&1 | tail -6`
Expected: FAIL — unresolved `cronRowStatus`/`CronRowStatus`.

- [ ] **Step 3: Implement `CronRowStatus.kt`**

```kotlin
package com.hermes.client.ui.cron

import com.hermes.client.data.network.CronJobDto
import com.hermes.client.ui.activity.CronAlertReason
import com.hermes.client.ui.activity.needsAttention

enum class CronRowStatus { FAILED, OVERDUE, OK, PAUSED }

/** Pure: a cron job's at-a-glance status for the list. FAILED (last run errored) takes priority,
 *  then OVERDUE, then PAUSED (disabled/paused), else OK. Reuses [needsAttention]. */
fun cronRowStatus(job: CronJobDto, nowMs: Long): CronRowStatus {
    val alert = needsAttention(listOf(job), nowMs).firstOrNull()
    return when {
        alert?.reason == CronAlertReason.FAILED -> CronRowStatus.FAILED
        alert?.reason == CronAlertReason.OVERDUE -> CronRowStatus.OVERDUE
        !job.enabled || job.isPaused -> CronRowStatus.PAUSED
        else -> CronRowStatus.OK
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*CronRowStatusTest*' --console=plain 2>&1 | tail -6`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/cron/CronRowStatus.kt app/src/test/java/com/hermes/client/ui/cron/CronRowStatusTest.kt
git commit -m "feat(cron): pure cronRowStatus for list status icons"
```

---

### Task 2: Styled error/empty states (Item 1)

**Files:** Modify `ui/cron/CronDetailScreen.kt`, `ui/cron/CronScreen.kt`, `ui/tools/AgentsToolsScreen.kt`, `ui/usage/UsageScreen.kt`

**Interfaces:** Consumes existing `com.hermes.client.ui.components.{ErrorState, EmptyState}` — `ErrorState(message: String, modifier = Modifier.fillMaxSize(), onRetry: (() -> Unit)? = null)`, `EmptyState(title: String, modifier = …, subtitle: String? = null, …)`.

- [ ] **Step 1: CronDetailScreen** — replace the bare error `Text`

Find `state.job == null -> Text(state.error ?: "Not found", …)` (~line 77) and replace with:
```kotlin
                state.job == null -> com.hermes.client.ui.components.ErrorState(
                    message = state.error ?: "Couldn't load this cron job",
                    onRetry = { vm.load(jobId) },
                )
```
(`jobId` is the composable's nav-arg param, already used in `LaunchedEffect(jobId) { vm.load(jobId) }`.)

- [ ] **Step 2: CronScreen** — error + empty

Replace the `state.error != null -> Text(...)` and `state.jobs.isEmpty() -> Text(...)` branches (~lines 52-59) with:
```kotlin
                state.error != null -> com.hermes.client.ui.components.ErrorState(
                    message = state.error!!, onRetry = { vm.load() },
                )
                state.jobs.isEmpty() -> com.hermes.client.ui.components.EmptyState(
                    title = "No cron jobs",
                    subtitle = "Scheduled jobs for this profile will show up here.",
                )
```

- [ ] **Step 3: AgentsToolsScreen** — error + empty

Replace `state.error != null -> Text(state.error!!, …)` (~line 42) with:
```kotlin
                state.error != null -> com.hermes.client.ui.components.ErrorState(
                    message = state.error!!, onRetry = { vm.load() },
                )
```
If there is a "no skills/toolsets" empty case rendered as blank/nothing, add an `EmptyState(title = "No agents or tools")` branch (read the file to place it in the same `when`); if the screen has no empty branch, add one after the error branch guarded on the loaded-but-empty condition it exposes.

- [ ] **Step 4: UsageScreen** — error

Replace `state.error != null -> Text(state.error!!, …)` (~line 111) with:
```kotlin
                state.error != null -> com.hermes.client.ui.components.ErrorState(
                    message = state.error!!, onRetry = { vm.load() },
                )
```

- [ ] **Step 5: Compile + full suite**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --console=plain 2>&1 | grep -E "^e: |FAILED|BUILD" | head`
Expected: `BUILD SUCCESSFUL`, no `FAILED`. (If a bare `Text` sat inside a centering `Box`, the `ErrorState`/`EmptyState` already fill/center themselves — remove any now-redundant `Modifier.align`.)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/cron/CronDetailScreen.kt app/src/main/java/com/hermes/client/ui/cron/CronScreen.kt app/src/main/java/com/hermes/client/ui/tools/AgentsToolsScreen.kt app/src/main/java/com/hermes/client/ui/usage/UsageScreen.kt
git commit -m "feat(ux): route error/empty screens through shared ScreenStates (icon + Retry)"
```

---

### Task 3: Wire the per-tenant accent into content (Item 2)

**Files:** Modify (grep each; line numbers are guides): `ui/activity/MissionControlScreen.kt`, `ui/cron/CronScreen.kt`, `ui/cron/CronDetailScreen.kt`, `ui/chat/ChatScreen.kt`, `ui/chat/ChatComponents.kt`, `ui/usage/UsageScreen.kt`, `ui/sessions/SessionsScreen.kt`, `ui/models/ModelSelector.kt`, `ui/nav/YouHubScreen.kt`

**Interfaces:** Consumes `com.hermes.client.ui.theme.LocalProfileAccent` (`.current.accent: Color`, a `@Composable` read). `HermesTheme` provides it app-wide for the active profile; MissionControl re-provides per page.

- [ ] **Step 1: Replace in-content accent colour at each site**

For each file, add `import com.hermes.client.ui.theme.LocalProfileAccent` and replace **in-content accent** uses of `MaterialTheme.colorScheme.primary` with `LocalProfileAccent.current.accent`. The sites (find via `grep -n "colorScheme.primary"` in each file and match the description):

- `MissionControlScreen.kt` — the `SectionHeader` label `color`; the `ActivityRow` non-error icon `tint`.
- `CronScreen.kt` — the schedule-overline `color` **only in the `job.enabled && !job.isPaused` branch** (keep the `else -> MaterialTheme.colorScheme.error`).
- `CronDetailScreen.kt` — the "PROMPT" and "RUN HISTORY (…)" label colours.
- `ChatScreen.kt` — the "COMMANDS" and "ATTACH · MENTION" (or similar) section-label colours.
- `ChatComponents.kt` — the accent icon `tint = MaterialTheme.colorScheme.primary`.
- `UsageScreen.kt` — the "DAILY TOKENS" / "TOP MODELS" section-label colours. **Leave the chart `inputColor` as `colorScheme.primary`.**
- `SessionsScreen.kt` — the group-header chevron `tint`, the group label `color`, the `SectionHeader` label `color`, and the other icon `tint`.
- `ModelSelector.kt` — the current-model row label `color` and the favourite-star `tint`.
- `YouHubScreen.kt` — the "PROFILES" section label and the selected-avatar-name label `color`.

Do **NOT** change any `colorScheme.error`, `onSurface`, `onSurfaceVariant`, `surfaceVariant`, etc. — only the enumerated `colorScheme.primary` accent uses.

- [ ] **Step 2: Verify no stray content `colorScheme.primary` remain (beyond the intentionally-left chart)**

Run: `grep -rn "colorScheme.primary" app/src/main/java/com/hermes/client/ui/{activity,cron,chat,usage,sessions,models,nav} 2>/dev/null`
Expected: only the intentional leave-behinds (Usage chart `inputColor`, and any genuinely-semantic primary you deliberately kept). Note them in the report.

- [ ] **Step 3: Compile + full suite + assemble**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --console=plain 2>&1 | grep -E "^e: |FAILED|BUILD" | head`
Expected: `BUILD SUCCESSFUL`, no `FAILED`.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(ux): render in-content accents in the active-tenant colour"
```

---

### Task 4: Visible model chip in the chat header (Item 3)

**Files:** Modify `ui/chat/ChatScreen.kt`

- [ ] **Step 1: Replace the gated TextButton with an always-visible AssistChip**

In the `HermesTopBar(...)` `actions` slot (~line 136-143), replace:
```kotlin
                if (providers.isNotEmpty()) {
                    androidx.compose.material3.TextButton(onClick = { modelSheetOpen = true }) {
                        Text(currentModel ?: "Model", maxLines = 1)
                    }
                }
                StatusDot(connState)
```
with:
```kotlin
                androidx.compose.material3.AssistChip(
                    onClick = { modelSheetOpen = true },
                    label = { Text(currentModel ?: "Model", maxLines = 1) },
                    trailingIcon = {
                        Icon(
                            androidx.compose.material.icons.Icons.Rounded.ArrowDropDown,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
                StatusDot(connState)
```
Add imports if missing (`androidx.compose.material.icons.rounded.ArrowDropDown`, `androidx.compose.foundation.layout.size`, `androidx.compose.ui.unit.dp`). The chip is always shown (tapping opens the sheet, which loads providers).

- [ ] **Step 2: Compile + assemble**

Run: `./gradlew :app:compileDebugKotlin --console=plain 2>&1 | grep -E "^e: |BUILD" | head`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/chat/ChatScreen.kt
git commit -m "feat(chat): visible model chip in the header (always shown, opens the sheet)"
```

---

### Task 5: Cron status icon in the list (Item 4 UI)

**Files:** Modify `ui/cron/CronScreen.kt`

**Interfaces:** Consumes `cronRowStatus(job, nowMs): CronRowStatus` (Task 1); `LocalProfileAccent` (Task 3 import may already be present).

- [ ] **Step 1: Add a leading status icon to the cron row**

In the jobs `LazyColumn` (the `else ->` branch), compute a stable now and add `leadingContent` to the `ListItem`:
```kotlin
            else -> {
                val nowMs = remember(state.jobs) { System.currentTimeMillis() }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.jobs, key = { it.id }) { job ->
                        ListItem(
                            leadingContent = {
                                val (icon, tint) = when (cronRowStatus(job, nowMs)) {
                                    CronRowStatus.FAILED, CronRowStatus.OVERDUE ->
                                        Icons.Rounded.ErrorOutline to MaterialTheme.colorScheme.error
                                    CronRowStatus.PAUSED ->
                                        Icons.Rounded.PauseCircleOutline to MaterialTheme.colorScheme.onSurfaceVariant
                                    CronRowStatus.OK ->
                                        Icons.Rounded.CheckCircle to com.hermes.client.ui.theme.LocalProfileAccent.current.accent
                                }
                                Icon(icon, contentDescription = null, tint = tint)
                            },
                            overlineContent = { /* unchanged */ },
                            headlineContent = { Text(job.name ?: job.id) },
                            supportingContent = { /* unchanged */ },
                            modifier = Modifier.clickable { onOpen(job.id) },
                        )
                        HorizontalDivider()
                    }
                }
            }
```
Keep the existing `overlineContent`/`supportingContent` bodies as they are (Task 3 already handled the overline colour). Add imports: `androidx.compose.material.icons.rounded.{ErrorOutline, PauseCircleOutline, CheckCircle}`, `androidx.compose.material3.Icon`, `androidx.compose.runtime.remember`.

- [ ] **Step 2: Compile + full suite + assemble**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --console=plain 2>&1 | grep -E "^e: |FAILED|BUILD" | head`
Expected: `BUILD SUCCESSFUL`, no `FAILED`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/cron/CronScreen.kt
git commit -m "feat(cron): leading status icon (failed/overdue/paused/ok) in the cron list"
```

---

### Task 6: Tenant token near the composer (Item 5)

**Files:** Create `ui/components/ProfileAvatar.kt`; Modify `ui/chat/ChatScreen.kt`, `ui/nav/YouHubScreen.kt`

**Interfaces:** Produces `@Composable fun ProfileAvatar(name: String?, modifier: Modifier = Modifier, size: Dp = 28.dp)`. Consumes `rememberProfileAccent` (`ui/theme/ProfileAccent.kt`).

- [ ] **Step 1: Create `ProfileAvatar.kt`**

```kotlin
package com.hermes.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hermes.client.ui.theme.rememberProfileAccent

/** A lettered, per-tenant-coloured avatar token (the profile's initial in its accent). */
@Composable
fun ProfileAvatar(name: String?, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    val accent = rememberProfileAccent(name, isSystemInDarkTheme())
    Box(
        modifier.size(size).clip(CircleShape).background(accent.accent),
        contentAlignment = Alignment.Center,
    ) {
        Text((name ?: "·").take(1).uppercase(), color = accent.onAccent, style = MaterialTheme.typography.labelLarge)
    }
}
```

- [ ] **Step 2: Place it in the chat composer Row**

In `ChatScreen.kt`'s `bottomBar` composer `Row` (~line 146-186), add as the **first** child (before the paperclip `IconButton`):
```kotlin
        com.hermes.client.ui.components.ProfileAvatar(activeProfile)
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = { pickImage.launch("image/*") }, enabled = connected) { /* unchanged */ }
```
(`activeProfile` is already collected in `ChatScreen`; `Spacer`/`Modifier.width` already imported.)

- [ ] **Step 3: Refactor `YouHubScreen.ProfileAvatarRow` to reuse `ProfileAvatar`**

In `YouHubScreen.kt`'s private `ProfileAvatarRow` (~lines 201-236), replace the inline `Box(...48dp...) { Text(initial) }` with `com.hermes.client.ui.components.ProfileAvatar(name, size = 48.dp)` wrapped in the existing `clickable { onSwitch(name) }` (keep the name label `Text` below it). Keep behaviour identical (48 dp avatar + name label).

- [ ] **Step 4: Compile + assemble**

Run: `./gradlew :app:compileDebugKotlin --console=plain 2>&1 | grep -E "^e: |BUILD" | head`
Expected: `BUILD SUCCESSFUL`.
Run: `./gradlew :app:assembleBeta --console=plain 2>&1 | grep -E "^e: |BUILD" | tail -2`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/components/ProfileAvatar.kt app/src/main/java/com/hermes/client/ui/chat/ChatScreen.kt app/src/main/java/com/hermes/client/ui/nav/YouHubScreen.kt
git commit -m "feat(ux): reusable ProfileAvatar; tenant token in the chat composer"
```

---

### Task 7: On-device verification

**Files:** none (verification only).

- [ ] **Step 1: Build + install**

`./gradlew :app:assembleBeta` then `adb -e install -r app/build/outputs/apk/beta/app-beta.apk`. Use the mock (`http://10.0.2.2:8899`) which has multiple profiles, a failed + overdue cron (acme), and models.

- [ ] **Step 2: Verify all five**

1. **Error/empty:** open a cron detail with a bad id (or kill the mock mid-load), and the Cron/Agents/Usage screens under error → each shows the **icon + message + Retry** state (not bare text); an empty cron/agents profile shows the styled empty state.
2. **Accent:** switch profiles (acme/globex/initech) → section headers, list icons, cron overline, chat command labels, sessions group headers render in that tenant's colour, in **both light and dark**.
3. **Model chip:** the chat header shows a **chip** (model name + caret), visible even on cold-open; tap → the model sheet opens.
4. **Cron status:** the Cron list shows a leading status icon per row — a failed job red, healthy a check (in the tenant accent), paused a pause glyph.
5. **Composer token:** the chat composer shows the tenant avatar (correct letter + colour) left of the paperclip; it matches the active profile.

- [ ] **Step 3: Commit (only if verification-only fixups were needed)**

```bash
git add -A
git commit -m "chore(ux): quick-wins verification fixups"   # only if needed
```

---

## Self-Review

- **Spec coverage:** Item 1 → Task 2 (route 4 screens through ScreenStates + Retry) ✓; Item 2 → Task 3 (accent at enumerated sites; error/onSurface untouched; chart left) ✓; Item 3 → Task 4 (always-visible AssistChip, un-gated) ✓; Item 4 → Task 1 (pure `cronRowStatus` + test) + Task 5 (leading icon) ✓; Item 5 → Task 6 (`ProfileAvatar` extract + composer token + YouHub reuse) ✓; on-device incl. light+dark, cold-open chip, error/empty, cron icons, composer token → Task 7 ✓; no bridge changes ✓.
- **Placeholder scan:** every code step has concrete code; the conditional bits are the Task 2 AgentsTools empty-branch placement (guarded read-the-file), Task 3's "find via grep" (line numbers are guides, sites enumerated by description), and the Task 7 verification-only commit.
- **Type consistency:** `cronRowStatus(job, nowMs): CronRowStatus{FAILED,OVERDUE,OK,PAUSED}` (Task 1) consumed in Task 5; `ErrorState(message,onRetry)`/`EmptyState(title,subtitle)` (existing) in Task 2; `LocalProfileAccent.current.accent` (Task 3) in Tasks 3+5; `ProfileAvatar(name, modifier, size)` (Task 6) used in chat + YouHub. Consistent.

**Ordering:** Task 1 (pure) stands alone. Tasks 2, 4, 6 are independent UI items. Task 3 (accent) is broad but independent. Task 5 depends on Task 1. Task 7 verifies. (Tasks touch mostly disjoint files; `CronScreen.kt` is touched by Tasks 2, 3, 5 and `ChatScreen.kt` by Tasks 3, 4, 6 — execute in order to avoid conflicts.)
