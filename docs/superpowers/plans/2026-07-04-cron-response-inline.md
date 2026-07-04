# Cron Response Inline (Agent Activity) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tapping a cron run in the Agent Activity feed expands its response (the run's final assistant message) inline, with a "View full chat" link to the transcript.

**Architecture:** A pure `cronResponse(messages)` extractor + an `ActivityItem.sessionId` + a lazy per-run loader on `MissionControlViewModel` (`SessionRepository.history()` REST, cached by sessionId) + response-first inline expansion in `MissionControlScreen`. No gateway/bridge changes.

**Tech Stack:** Kotlin, Jetpack Compose, Material3, Hilt, MVVM + StateFlow, JUnit + MockK + coroutines-test.

## Global Constraints

- **No bridge/gateway API changes.** Reuse `SessionRepository.history(sessionId, profile): List<ChatMessage>` (REST `GET /api/sessions/<id>/messages`).
- JDK 21: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`. compileSdk/targetSdk 36, minSdk 26.
- Compile: `./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tail -5`. Tests: `./gradlew :app:testDebugUnitTest --console=plain`. Beta: `./gradlew :app:assembleBeta`.
- Follow patterns: pure unit tests like `ActivityModelsTest`; VM tests with MockK + `runTest`; Material3 `ListItem` rows.
- **No AI/assistant attribution** in commits, files, or PRs.
- Only **cron runs** expand (`kind == CRON && sessionId != null && !upcoming`); conversations + upcoming next-runs navigate as today.

## File Structure

- Create `app/src/main/java/com/hermes/client/ui/activity/CronResponse.kt` — pure `cronResponse` + `CRON_RESPONSE_MAX`.
- Create `app/src/test/java/com/hermes/client/ui/activity/CronResponseTest.kt`.
- Modify `app/src/main/java/com/hermes/client/ui/activity/ActivityModels.kt` — `ActivityItem.sessionId`.
- Modify `app/src/test/java/com/hermes/client/ui/activity/ActivityModelsTest.kt` — assert `sessionId`.
- Modify `app/src/main/java/com/hermes/client/ui/activity/MissionControlViewModel.kt` — `CronResponseUi`, `responses`, `loadResponse`.
- Create `app/src/test/java/com/hermes/client/ui/activity/MissionControlViewModelTest.kt`.
- Modify `app/src/main/java/com/hermes/client/ui/activity/MissionControlScreen.kt` — inline expansion.

---

### Task 1: Pure `cronResponse` (TDD)

**Files:**
- Create: `app/src/main/java/com/hermes/client/ui/activity/CronResponse.kt`
- Test: `app/src/test/java/com/hermes/client/ui/activity/CronResponseTest.kt`

**Interfaces:**
- Produces: `const val CRON_RESPONSE_MAX = 500`; `fun cronResponse(messages: List<ChatMessage>): String`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.hermes.client.ui.activity

import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import com.hermes.client.domain.ToolCall
import com.hermes.client.domain.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun msg(role: Role, text: String = "", tools: List<ToolCall> = emptyList()) =
    ChatMessage(id = "x", role = role, text = text, tools = tools)

class CronResponseTest {
    @Test fun returns_last_assistant_text() {
        val out = cronResponse(
            listOf(msg(Role.USER, "run it"), msg(Role.ASSISTANT, "first"), msg(Role.ASSISTANT, "final answer")),
        )
        assertEquals("final answer", out)
    }

    @Test fun trims_assistant_text() {
        assertEquals("hi", cronResponse(listOf(msg(Role.ASSISTANT, "  hi  "))))
    }

    @Test fun falls_back_to_last_tool_output_when_no_assistant_text() {
        val out = cronResponse(
            listOf(
                msg(Role.USER, "go"),
                msg(Role.ASSISTANT, "", tools = listOf(ToolCall("t1", "search", ToolStatus.DONE, "42 results"))),
            ),
        )
        assertEquals("search: 42 results", out)
    }

    @Test fun truncates_long_tool_output() {
        val long = "y".repeat(CRON_RESPONSE_MAX + 50)
        val out = cronResponse(listOf(msg(Role.ASSISTANT, "", tools = listOf(ToolCall("t", "run", ToolStatus.DONE, long)))))
        assertEquals(CRON_RESPONSE_MAX + 1, out.length)
        assertTrue(out.endsWith("…"))
    }

    @Test fun empty_messages_returns_placeholder() {
        assertEquals("No text output.", cronResponse(emptyList()))
    }

    @Test fun blank_assistant_and_no_tools_returns_placeholder() {
        assertEquals("No text output.", cronResponse(listOf(msg(Role.ASSISTANT, "   "))))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*CronResponseTest*' --console=plain 2>&1 | tail -6`
Expected: FAIL — unresolved `cronResponse`/`CRON_RESPONSE_MAX`.

- [ ] **Step 3: Implement `CronResponse.kt`**

```kotlin
package com.hermes.client.ui.activity

import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role

/** Max length of the tool-result fallback summary before truncation. */
const val CRON_RESPONSE_MAX = 500

/**
 * A cron run's "response" from its session messages: the last assistant message with text; else
 * the last tool result summary; else a placeholder. Pure — unit-tested without network or clock.
 */
fun cronResponse(messages: List<ChatMessage>): String {
    messages.lastOrNull { it.role == Role.ASSISTANT && it.text.isNotBlank() }
        ?.let { return it.text.trim() }
    messages.lastOrNull { m -> m.tools.any { it.output.isNotBlank() } }
        ?.let { m ->
            val tool = m.tools.last { it.output.isNotBlank() }
            val summary = "${tool.name}: ${tool.output.trim()}"
            return if (summary.length > CRON_RESPONSE_MAX) summary.take(CRON_RESPONSE_MAX) + "…" else summary
        }
    return "No text output."
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*CronResponseTest*' --console=plain 2>&1 | tail -6`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/activity/CronResponse.kt app/src/test/java/com/hermes/client/ui/activity/CronResponseTest.kt
git commit -m "feat(activity): pure cronResponse extractor with tests"
```

---

### Task 2: `ActivityItem.sessionId`

**Files:**
- Modify: `app/src/main/java/com/hermes/client/ui/activity/ActivityModels.kt`
- Test: `app/src/test/java/com/hermes/client/ui/activity/ActivityModelsTest.kt`

**Interfaces:**
- Produces: `ActivityItem.sessionId: String?` — the raw session id, set by `sessionsToActivity`, null from `cronsToActivity`.

- [ ] **Step 1: Add the failing test** — append to `ActivityModelsTest`:

```kotlin
    @Test fun sessionsToActivity_sets_sessionId_to_raw_id() {
        val s = com.hermes.client.domain.Session(
            id = "sess-42", title = "Nightly report", model = null, provider = null,
            messageCount = 3, profile = "default", source = "cron", lastActive = 1_000L,
        )
        val item = sessionsToActivity(listOf(s)).single()
        assertEquals("sess-42", item.sessionId)
    }
```

Ensure `import org.junit.Assert.assertEquals` is present in the test file.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*ActivityModelsTest*' --console=plain 2>&1 | tail -6`
Expected: FAIL — `sessionId` unresolved.

- [ ] **Step 3: Add the field + set it**

In `ActivityModels.kt`, add to the `ActivityItem` data class (after `route`):

```kotlin
    // Raw session id for a session-backed row (used to lazily load a cron run's response inline).
    // Null for cron next-run items.
    val sessionId: String? = null,
```

In `sessionsToActivity`, add `sessionId = s.id,` to the `ActivityItem(...)` constructor (alongside `route = "chat/${s.id}"`). Leave `cronsToActivity` unchanged (defaults to null).

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*ActivityModelsTest*' --console=plain 2>&1 | tail -6`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/activity/ActivityModels.kt app/src/test/java/com/hermes/client/ui/activity/ActivityModelsTest.kt
git commit -m "feat(activity): carry sessionId on ActivityItem for inline response loading"
```

---

### Task 3: `MissionControlViewModel` lazy response loader

**Files:**
- Modify: `app/src/main/java/com/hermes/client/ui/activity/MissionControlViewModel.kt`
- Test: `app/src/test/java/com/hermes/client/ui/activity/MissionControlViewModelTest.kt`

**Interfaces:**
- Consumes: `cronResponse` (Task 1); `SessionRepository.history(sessionId, profile)`.
- Produces: `data class CronResponseUi(loading: Boolean = false, text: String? = null, error: Boolean = false)`; `val responses: StateFlow<Map<String, CronResponseUi>>`; `fun loadResponse(sessionId: String)`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.hermes.client.ui.activity

import com.hermes.client.data.repository.ProfileManager
import com.hermes.client.data.repository.SessionRepository
import com.hermes.client.data.repository.ToolsRepository
import com.hermes.client.domain.ChatMessage
import com.hermes.client.domain.Role
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MissionControlViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val sessions = mockk<SessionRepository>()
    private val tools = mockk<ToolsRepository>(relaxed = true)
    private val profileManager = mockk<ProfileManager>(relaxed = true)

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm() = MissionControlViewModel(sessions, tools, profileManager)

    @Test fun loadResponse_sets_text_on_success() = runTest(dispatcher) {
        coEvery { sessions.history("s1", any()) } returns listOf(ChatMessage("m", Role.ASSISTANT, "the answer"))
        val vm = vm()
        vm.loadResponse("s1")
        advanceUntilIdle()
        assertEquals("the answer", vm.responses.value["s1"]?.text)
    }

    @Test fun loadResponse_sets_error_on_failure() = runTest(dispatcher) {
        coEvery { sessions.history("s1", any()) } throws RuntimeException("boom")
        val vm = vm()
        vm.loadResponse("s1")
        advanceUntilIdle()
        assertTrue(vm.responses.value["s1"]?.error == true)
    }

    @Test fun loadResponse_caches_and_does_not_refetch() = runTest(dispatcher) {
        coEvery { sessions.history("s1", any()) } returns listOf(ChatMessage("m", Role.ASSISTANT, "x"))
        val vm = vm()
        vm.loadResponse("s1"); advanceUntilIdle()
        vm.loadResponse("s1"); advanceUntilIdle()
        coVerify(exactly = 1) { sessions.history("s1", any()) }
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*MissionControlViewModelTest*' --console=plain 2>&1 | tail -6`
Expected: FAIL — unresolved `responses`/`loadResponse`/`CronResponseUi`.

- [ ] **Step 3: Add the loader to `MissionControlViewModel`**

Add imports if missing: `kotlinx.coroutines.flow.asStateFlow` (already present), `kotlinx.coroutines.launch` (already present).

Add inside the class body (e.g. after the `state` declaration):

```kotlin
    /** Lazily-loaded cron-run responses, keyed by sessionId. */
    data class CronResponseUi(val loading: Boolean = false, val text: String? = null, val error: Boolean = false)

    private val _responses = MutableStateFlow<Map<String, CronResponseUi>>(emptyMap())
    val responses: StateFlow<Map<String, CronResponseUi>> = _responses.asStateFlow()

    /**
     * Fetch a cron run's response on demand (one REST history call), caching by sessionId. A loaded
     * or in-flight entry is not refetched; a prior error IS retryable (call again).
     */
    fun loadResponse(sessionId: String) {
        val existing = _responses.value[sessionId]
        if (existing != null && (existing.loading || existing.text != null)) return
        _responses.value = _responses.value + (sessionId to CronResponseUi(loading = true))
        viewModelScope.launch {
            val result = runCatching { sessions.history(sessionId, profile) }
            _responses.value = _responses.value + (
                sessionId to result.fold(
                    onSuccess = { CronResponseUi(text = cronResponse(it)) },
                    onFailure = { CronResponseUi(error = true) },
                )
            )
        }
    }
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*MissionControlViewModelTest*' --console=plain 2>&1 | tail -6`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/activity/MissionControlViewModel.kt app/src/test/java/com/hermes/client/ui/activity/MissionControlViewModelTest.kt
git commit -m "feat(activity): lazy per-run cron response loader on MissionControlViewModel"
```

---

### Task 4: Inline expansion in `MissionControlScreen`

**Files:**
- Modify: `app/src/main/java/com/hermes/client/ui/activity/MissionControlScreen.kt`

**Interfaces:**
- Consumes: `ActivityItem.sessionId` (Task 2); `MissionControlViewModel.responses`/`loadResponse`/`CronResponseUi` (Task 3).

- [ ] **Step 1: Thread response state through `MissionControlPage`**

In `MissionControlPage`, after `val state by vm.state.collectAsStateWithLifecycle()` add:

```kotlin
    val responses by vm.responses.collectAsStateWithLifecycle()
    val expanded = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    val onToggle: (ActivityItem) -> Unit = { item ->
        val open = !(expanded[item.id] ?: false)
        expanded[item.id] = open
        if (open) item.sessionId?.let { vm.loadResponse(it) }
    }
    val onRetryResponse: (ActivityItem) -> Unit = { item -> item.sessionId?.let { vm.loadResponse(it) } }
```

Update the `MissionControlContent(...)` call to pass them:

```kotlin
            MissionControlContent(
                state = state, nowMs = now, onRetry = { vm.refresh() },
                responses = responses, expanded = expanded,
                onToggle = onToggle, onRetryResponse = onRetryResponse, onOpen = onOpen,
            )
```

- [ ] **Step 2: Update `MissionControlContent` signature + the item row**

Change the signature to:

```kotlin
@Composable
private fun MissionControlContent(
    state: MissionControlState,
    nowMs: Long,
    onRetry: () -> Unit,
    responses: Map<String, MissionControlViewModel.CronResponseUi>,
    expanded: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean>,
    onToggle: (ActivityItem) -> Unit,
    onRetryResponse: (ActivityItem) -> Unit,
    onOpen: (String) -> Unit,
) {
```

Replace the `items(section.items, …)` block with:

```kotlin
                items(section.items, key = { it.id }) { activity ->
                    val expandable = activity.kind == ActivityKind.CRON &&
                        activity.sessionId != null && !activity.upcoming
                    ActivityRow(
                        item = activity,
                        nowMs = nowMs,
                        expandable = expandable,
                        isExpanded = expanded[activity.id] == true,
                        response = activity.sessionId?.let { responses[it] },
                        onClick = { if (expandable) onToggle(activity) else onOpen(activity.route) },
                        onRetry = { onRetryResponse(activity) },
                        onOpenFull = { onOpen(activity.route) },
                    )
                }
```

- [ ] **Step 3: Rewrite `ActivityRow` + add `CronResponseCard`**

Replace the existing `ActivityRow` composable with:

```kotlin
@Composable
private fun ActivityRow(
    item: ActivityItem,
    nowMs: Long,
    expandable: Boolean = false,
    isExpanded: Boolean = false,
    response: MissionControlViewModel.CronResponseUi? = null,
    onClick: () -> Unit,
    onRetry: () -> Unit = {},
    onOpenFull: () -> Unit = {},
) {
    val icon: ImageVector = when {
        item.kind == ActivityKind.CONVERSATION -> Icons.AutoMirrored.Rounded.Chat
        item.upcoming -> Icons.Rounded.Schedule
        item.status.equals("success", ignoreCase = true) -> Icons.Rounded.CheckCircle
        item.status.equals("error", ignoreCase = true) ||
            item.status.equals("failed", ignoreCase = true) -> Icons.Rounded.ErrorOutline
        else -> Icons.Rounded.History
    }
    val tint = when {
        item.status.equals("error", ignoreCase = true) ||
            item.status.equals("failed", ignoreCase = true) -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    val time = relativeTime(item.timestampMs, nowMs)
    Column {
        ListItem(
            leadingContent = { Icon(icon, contentDescription = null, tint = tint) },
            headlineContent = { Text(item.title) },
            supportingContent = { Text(listOfNotNull(item.subtitle, time).joinToString(" · ")) },
            trailingContent = if (expandable) {
                {
                    Icon(
                        if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = if (isExpanded) "Collapse response" else "Show response",
                    )
                }
            } else {
                null
            },
            modifier = Modifier.clickable(onClick = onClick),
        )
        if (expandable && isExpanded) {
            CronResponseCard(response = response, onRetry = onRetry, onOpenFull = onOpenFull)
        }
    }
}

@Composable
private fun CronResponseCard(
    response: MissionControlViewModel.CronResponseUi?,
    onRetry: () -> Unit,
    onOpenFull: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            when {
                response == null || response.loading ->
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                response.error -> {
                    Text("Couldn't load response", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
                else -> {
                    Text(
                        response.text ?: "No text output.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                    )
                    TextButton(onClick = onOpenFull) { Text("View full chat") }
                }
            }
        }
    }
}
```

- [ ] **Step 4: Add missing imports + build**

Add any imports the compiler reports as missing (candidates, verify against the file's existing imports):
`androidx.compose.foundation.layout.Column`, `androidx.compose.foundation.rememberScrollState`, `androidx.compose.foundation.verticalScroll`, `androidx.compose.foundation.layout.heightIn`, `androidx.compose.foundation.layout.size`, `androidx.compose.material.icons.rounded.ExpandLess`, `androidx.compose.material.icons.rounded.ExpandMore`, `androidx.compose.material3.CircularProgressIndicator`, `androidx.compose.material3.Surface`, `androidx.compose.material3.TextButton`, `androidx.compose.runtime.remember`.

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --console=plain 2>&1 | grep -E "^e: |FAILED|BUILD" | head`
Expected: `BUILD SUCCESSFUL`, no `FAILED`. Iterate on any `^e:` import/type error until clean.
Run: `./gradlew :app:assembleBeta --console=plain 2>&1 | grep -E "^e: |BUILD" | tail -2`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/activity/MissionControlScreen.kt
git commit -m "feat(activity): response-first inline expansion for cron runs"
```

---

### Task 5: On-device verification

**Files:** none (verification only).

- [ ] **Step 1: Build + install the beta**

Run `./gradlew :app:assembleBeta` then `adb -e install -r app/build/outputs/apk/beta/app-beta.apk`. Point it at a gateway that has at least one completed cron run (a `source="cron"` session).

- [ ] **Step 2: Verify**

- Open **Agent Activity**. A completed cron run shows a chevron; tap the row → it expands, shows a spinner, then the run's **response** text; a **"View full chat"** button opens the full transcript.
- Tap again → collapses. Re-expand → no refetch (instant).
- A **conversation** row and an **upcoming** cron next-run still navigate (no expansion).
- Force a history failure (airplane mode mid-expand) → "Couldn't load response" + **Retry**; Retry re-loads.

- [ ] **Step 3: Commit (only if verification-only fixups were needed)**

```bash
git add -A
git commit -m "chore(activity): cron-response verification fixups"   # only if needed
```

---

## Self-Review

- **Spec coverage:** pure `cronResponse` (last assistant text → tool-result summary → placeholder, `MAX=500` truncation), unit-tested (Task 1) ✓; `ActivityItem.sessionId` set by `sessionsToActivity` (Task 2) ✓; lazy `loadResponse` with `CronResponseUi` loading/text/error, cached by sessionId, retry-on-error, one-history-call guard, VM-tested (Task 3) ✓; response-first inline expansion — tap cron run toggles + loads, spinner/error+Retry/text, "View full chat", only `kind==CRON && sessionId!=null && !upcoming` expand, others navigate (Task 4) ✓; states + on-device incl. failure path (Task 5) ✓; no Product-toggle threading (response is text) ✓; no bridge changes (reuses `history`) ✓.
- **Placeholder scan:** every code step has full code; the only conditional is the Task 5 verification-only commit and the "add missing imports" step (candidate list given).
- **Type consistency:** `cronResponse(messages)`/`CRON_RESPONSE_MAX`, `ActivityItem.sessionId`, `MissionControlViewModel.CronResponseUi(loading/text/error)`, `responses`/`loadResponse(sessionId)`, and the `ActivityRow(expandable/isExpanded/response/onClick/onRetry/onOpenFull)` params are consistent across Tasks 1–4. `ToolStatus.DONE`, `Role.ASSISTANT`, `ToolCall(id,name,status,output)`, `Session(...)` match the domain models.

**Ordering note:** Task 1 (pure) and Task 2 (data field) are independent and each green. Task 3 depends on Task 1 (`cronResponse`). Task 4 depends on Tasks 2 + 3. Task 5 verifies on-device.
