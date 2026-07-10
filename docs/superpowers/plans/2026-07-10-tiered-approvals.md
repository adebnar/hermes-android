# Touch-native Tiered Approvals — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the broken approval wire format and give agent approvals a touch-native, two-tier bottom-sheet UX on the phone.

**Architecture:** Parse the real `approval.request` fields (`command`, `description`, `pattern_keys`, `allow_permanent`) into an expanded `ApprovalRequest`; respond with the gateway's scoped `approval.respond {choice}` (plus a derived `approved` for version-skew safety). A pure tier layer maps `allow_permanent` → Standard/Elevated and the allowed scopes; a `ModalBottomSheet` renders the tiers, with a reusable `SlideToConfirm` gating the Elevated *Allow*. Notifications become tier-aware. No new gateway endpoints.

**Tech Stack:** Kotlin, Jetpack Compose, Material3, Hilt, kotlinx.serialization, JUnit + MockK.

## Global Constraints

- NO new gateway endpoints — reuse `approval.respond {choice: once|session|always|deny, all}` and the existing request fields.
- Material3; per-tenant accent via `LocalProfileAccent.current` (`ProfileAccentColors(accent, onAccent)`).
- NO AI/assistant attribution in commits, files, or PRs.
- Run `gitleaks git --no-banner --redact` before every push; work lands on `feature/tiered-approvals` → PR into `dev`.
- Build env: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`. Compile `:app:compileDebugKotlin`; unit tests `:app:testDebugUnitTest`; beta `:app:assembleBeta`.
- Spec: `docs/superpowers/specs/2026-07-10-tiered-approvals-design.md`.

## File map

- Create `app/src/main/java/com/hermes/client/ui/chat/ApprovalTier.kt` — `ApprovalChoice`, `ApprovalTier`, `tierFor`, `allowedScopes` (pure).
- Modify `app/src/main/java/com/hermes/client/ui/chat/ChatUiState.kt` — expand `ApprovalRequest`; parse the new fields in the `approval.request` reduce.
- Modify `app/src/main/java/com/hermes/client/data/network/ServerEvent.kt` — add `bool` + `strList` payload helpers.
- Modify `app/src/main/java/com/hermes/client/data/repository/ChatRepository.kt` — `respondApproval(sessionId, choice)`.
- Modify `app/src/main/java/com/hermes/client/ui/chat/ChatViewModel.kt` — `respondApproval(choice)` replaces `approve(Boolean)`.
- Create `app/src/main/java/com/hermes/client/ui/components/SlideToConfirm.kt` — reusable slide-to-confirm + pure state holder.
- Create `app/src/main/java/com/hermes/client/ui/chat/ApprovalSheet.kt` — the bottom sheet.
- Modify `app/src/main/java/com/hermes/client/ui/chat/ChatScreen.kt` — replace `ApprovalDialog` with `ApprovalSheet`.
- Remove `ApprovalDialog` from `app/src/main/java/com/hermes/client/ui/chat/ChatComponents.kt`.
- Modify `notifications/NotificationMapper.kt`, `NotificationModels.kt`, `NotificationActionReceiver.kt` — tier-aware actions + choice-based response.

---

### Task 1: Tier + choice model (pure)

**Files:**
- Create: `app/src/main/java/com/hermes/client/ui/chat/ApprovalTier.kt`
- Test: `app/src/test/java/com/hermes/client/ui/chat/ApprovalTierTest.kt`

**Interfaces:**
- Consumes: `ApprovalRequest` (Task 2 expands it, but Task 1 only needs `allowPermanent: Boolean` — define `tierFor` against that field; both tasks land before compile of consumers).
- Produces: `enum ApprovalChoice(val wire: String)`, `enum ApprovalTier`, `fun tierFor(allowPermanent: Boolean): ApprovalTier`, `fun allowedScopes(tier: ApprovalTier): List<ApprovalChoice>`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.hermes.client.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ApprovalTierTest {
    @Test fun choice_wire_values() {
        assertEquals("once", ApprovalChoice.ONCE.wire)
        assertEquals("session", ApprovalChoice.SESSION.wire)
        assertEquals("always", ApprovalChoice.ALWAYS.wire)
        assertEquals("deny", ApprovalChoice.DENY.wire)
    }

    @Test fun allow_permanent_true_is_standard() {
        assertEquals(ApprovalTier.STANDARD, tierFor(allowPermanent = true))
    }

    @Test fun allow_permanent_false_is_elevated() {
        assertEquals(ApprovalTier.ELEVATED, tierFor(allowPermanent = false))
    }

    @Test fun standard_offers_once_session_always() {
        assertEquals(
            listOf(ApprovalChoice.ONCE, ApprovalChoice.SESSION, ApprovalChoice.ALWAYS),
            allowedScopes(ApprovalTier.STANDARD),
        )
    }

    @Test fun elevated_offers_only_once_and_session() {
        assertEquals(
            listOf(ApprovalChoice.ONCE, ApprovalChoice.SESSION),
            allowedScopes(ApprovalTier.ELEVATED),
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.hermes.client.ui.chat.ApprovalTierTest"`
Expected: FAIL — unresolved reference `ApprovalChoice` / `tierFor`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.hermes.client.ui.chat

/** The scoped decision the gateway understands: approval.respond {choice}. */
enum class ApprovalChoice(val wire: String) {
    ONCE("once"), SESSION("session"), ALWAYS("always"), DENY("deny")
}

/** Risk tier, derived from the gateway's `allow_permanent` bit (false = Tirith-flagged). */
enum class ApprovalTier { STANDARD, ELEVATED }

fun tierFor(allowPermanent: Boolean): ApprovalTier =
    if (allowPermanent) ApprovalTier.STANDARD else ApprovalTier.ELEVATED

/** Allow-scopes offered per tier (DENY is always available separately). */
fun allowedScopes(tier: ApprovalTier): List<ApprovalChoice> = when (tier) {
    ApprovalTier.STANDARD -> listOf(ApprovalChoice.ONCE, ApprovalChoice.SESSION, ApprovalChoice.ALWAYS)
    ApprovalTier.ELEVATED -> listOf(ApprovalChoice.ONCE, ApprovalChoice.SESSION)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.hermes.client.ui.chat.ApprovalTierTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/chat/ApprovalTier.kt app/src/test/java/com/hermes/client/ui/chat/ApprovalTierTest.kt
git commit -m "feat(approvals): tier + choice model (allow_permanent -> Standard/Elevated + scopes)"
```

---

### Task 2: Parse the real approval.request fields

**Files:**
- Modify: `app/src/main/java/com/hermes/client/data/network/ServerEvent.kt` (add `bool`, `strList`)
- Modify: `app/src/main/java/com/hermes/client/ui/chat/ChatUiState.kt` (expand `ApprovalRequest`, line ~10; update the `"approval.request"` reduce, line ~86)
- Test: `app/src/test/java/com/hermes/client/ui/chat/ApprovalParseTest.kt`

**Interfaces:**
- Consumes: `ServerEvent` (`str`, and new `bool`/`strList`), `ChatUiState.reduce`.
- Produces: `data class ApprovalRequest(command, description, patternKeys: List<String>, allowPermanent: Boolean)`; `ServerEvent.bool(key): Boolean?`; `ServerEvent.strList(key): List<String>`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.hermes.client.ui.chat

import com.hermes.client.data.network.ServerEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalParseTest {
    private fun approvalEvent(payload: JsonObject) = ServerEvent("approval.request", "s1", payload)

    @Test fun parses_command_description_patterns_and_allow_permanent() {
        val e = approvalEvent(buildJsonObject {
            put("session_id", "s1")
            put("command", "rm -rf /tmp/x")
            put("description", "recursive delete")
            putJsonArray("pattern_keys") { add("recursive delete"); add("force") }
            put("allow_permanent", true)
        })
        val state = ChatUiState().reduce(e)
        val req = state.pendingApproval!!
        assertEquals("rm -rf /tmp/x", req.command)
        assertEquals("recursive delete", req.description)
        assertEquals(listOf("recursive delete", "force"), req.patternKeys)
        assertTrue(req.allowPermanent)
    }

    @Test fun missing_fields_degrade_safely() {
        val req = ChatUiState().reduce(approvalEvent(buildJsonObject { put("session_id", "s1") })).pendingApproval!!
        assertEquals("", req.command)
        assertEquals("", req.description)
        assertEquals(emptyList<String>(), req.patternKeys)
        assertFalse(req.allowPermanent) // safe default: no "Always"
    }

    @Test fun single_pattern_key_is_used_when_array_absent() {
        val req = ChatUiState().reduce(approvalEvent(buildJsonObject {
            put("session_id", "s1"); put("pattern_key", "force push")
        })).pendingApproval!!
        assertEquals(listOf("force push"), req.patternKeys)
    }
}
```

Note: `buildJsonObject { putJsonArray("pattern_keys") { add("x") } }` requires `import kotlinx.serialization.json.add`.

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.hermes.client.ui.chat.ApprovalParseTest"`
Expected: FAIL — `ApprovalRequest` has no `command` etc.; `reduce` still reads `prompt`.

- [ ] **Step 3a: Add payload helpers to `ServerEvent.kt`**

Append after the existing `objOrEmpty` helper:

```kotlin
internal fun ServerEvent.bool(key: String): Boolean? =
    (payload[key] as? JsonPrimitive)?.booleanOrNull

internal fun ServerEvent.strList(key: String): List<String> =
    (payload[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
```

Add imports at the top of `ServerEvent.kt`:
```kotlin
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
```

- [ ] **Step 3b: Expand `ApprovalRequest` and update the reduce in `ChatUiState.kt`**

Replace the old model (`data class ApprovalRequest(val prompt: String)`) with:
```kotlin
data class ApprovalRequest(
    val command: String,
    val description: String,
    val patternKeys: List<String>,
    val allowPermanent: Boolean,
)
```

Replace the `"approval.request"` branch of `reduce` (was `ApprovalRequest(event.str("prompt") ?: "")`) with:
```kotlin
"approval.request" -> state.copy(
    pendingApproval = ApprovalRequest(
        command = event.str("command") ?: "",
        description = event.str("description") ?: "",
        patternKeys = event.strList("pattern_keys")
            .ifEmpty { event.str("pattern_key")?.let { listOf(it) } ?: emptyList() },
        allowPermanent = event.bool("allow_permanent") ?: false,
    ),
)
```
Add the imports used: `import com.hermes.client.data.network.bool` and `import com.hermes.client.data.network.strList` (alongside the existing `str` import).

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.hermes.client.ui.chat.ApprovalParseTest"`
Expected: PASS (3 tests). (`ChatScreen.kt`/`ChatViewModel.kt` won't compile yet — they still reference the old model/`approve`; Tasks 3 & 5 fix them. Run the targeted test class, not the whole build, until then.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hermes/client/data/network/ServerEvent.kt app/src/main/java/com/hermes/client/ui/chat/ChatUiState.kt app/src/test/java/com/hermes/client/ui/chat/ApprovalParseTest.kt
git commit -m "feat(approvals): parse real approval.request fields (command/description/pattern_keys/allow_permanent)"
```

---

### Task 3: Scoped response (choice, not boolean)

**Files:**
- Modify: `app/src/main/java/com/hermes/client/data/repository/ChatRepository.kt` (`respondApproval`, ~line 126)
- Modify: `app/src/main/java/com/hermes/client/ui/chat/ChatViewModel.kt` (`approve`, ~line 244)
- Test: `app/src/test/java/com/hermes/client/data/repository/RespondApprovalTest.kt`

**Interfaces:**
- Consumes: `ApprovalChoice` (Task 1), `HermesGatewayClient.call(method, params): JsonElement`.
- Produces: `suspend fun ChatRepository.respondApproval(sessionId: String, choice: ApprovalChoice)`; `fun ChatViewModel.respondApproval(choice: ApprovalChoice)`.

- [ ] **Step 1: Write the failing test** (mirror the existing ChatRepository test style — MockK the client, capture the params)

```kotlin
package com.hermes.client.data.repository

import com.hermes.client.data.network.HermesGatewayClient
import com.hermes.client.ui.chat.ApprovalChoice
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RespondApprovalTest {
    private val client = mockk<HermesGatewayClient>(relaxed = true)
    private val repo = ChatRepository(client) // match the real ctor; add other relaxed deps if needed

    @Test fun once_sends_choice_once_and_approved_true() = runTest {
        val params = slot<JsonObject>()
        coEvery { client.call("approval.respond", capture(params)) } returns JsonPrimitive("ok")
        repo.respondApproval("s1", ApprovalChoice.ONCE)
        assertEquals("s1", params.captured["session_id"]!!.jsonPrimitive.content)
        assertEquals("once", params.captured["choice"]!!.jsonPrimitive.content)
        assertTrue(params.captured["approved"]!!.jsonPrimitive.boolean)
    }

    @Test fun deny_sends_choice_deny_and_approved_false() = runTest {
        val params = slot<JsonObject>()
        coEvery { client.call("approval.respond", capture(params)) } returns JsonPrimitive("ok")
        repo.respondApproval("s1", ApprovalChoice.DENY)
        assertEquals("deny", params.captured["choice"]!!.jsonPrimitive.content)
        assertFalse(params.captured["approved"]!!.jsonPrimitive.boolean)
    }
}
```
(If `ChatRepository`'s constructor takes more than `client`, construct it the way the existing repository tests do — check `app/src/test/.../ChatRepositoryTest*.kt` for the real signature and reuse it.)

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.hermes.client.data.repository.RespondApprovalTest"`
Expected: FAIL — `respondApproval(String, ApprovalChoice)` doesn't exist.

- [ ] **Step 3a: Change `ChatRepository.respondApproval`**

```kotlin
suspend fun respondApproval(sessionId: String, choice: com.hermes.client.ui.chat.ApprovalChoice) {
    client.call("approval.respond", buildJsonObject {
        put("session_id", sessionId)
        put("choice", choice.wire)
        put("approved", choice != com.hermes.client.ui.chat.ApprovalChoice.DENY)
    })
}
```

- [ ] **Step 3b: Change `ChatViewModel.approve` → `respondApproval`**

```kotlin
fun respondApproval(choice: com.hermes.client.ui.chat.ApprovalChoice) {
    _state.value = _state.value.copy(pendingApproval = null)
    viewModelScope.launch { runCatching { chat.respondApproval(sessionId, choice) } }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.hermes.client.data.repository.RespondApprovalTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hermes/client/data/repository/ChatRepository.kt app/src/main/java/com/hermes/client/ui/chat/ChatViewModel.kt app/src/test/java/com/hermes/client/data/repository/RespondApprovalTest.kt
git commit -m "feat(approvals): scoped approval.respond {choice} (+ derived approved) replacing the boolean"
```

---

### Task 4: Reusable SlideToConfirm

**Files:**
- Create: `app/src/main/java/com/hermes/client/ui/components/SlideToConfirm.kt`
- Test: `app/src/test/java/com/hermes/client/ui/components/SlideToConfirmStateTest.kt`

**Interfaces:**
- Produces: pure `slideProgress(dragPx: Float, trackPx: Float): Float` (0f..1f) and `isConfirmed(progress: Float): Boolean` (>= 0.9f); `@Composable fun SlideToConfirm(label: String, accent: Color, onConfirm: () -> Unit, modifier: Modifier = Modifier)`.

- [ ] **Step 1: Write the failing test** (pure state logic only — no Compose UI test)

```kotlin
package com.hermes.client.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideToConfirmStateTest {
    @Test fun progress_is_clamped_0_to_1() {
        assertEquals(0f, slideProgress(dragPx = -50f, trackPx = 100f), 0.001f)
        assertEquals(0.5f, slideProgress(dragPx = 50f, trackPx = 100f), 0.001f)
        assertEquals(1f, slideProgress(dragPx = 150f, trackPx = 100f), 0.001f)
    }

    @Test fun confirmed_only_past_threshold() {
        assertFalse(isConfirmed(0.89f))
        assertTrue(isConfirmed(0.90f))
        assertTrue(isConfirmed(1f))
    }

    @Test fun zero_track_is_safe() {
        assertEquals(0f, slideProgress(dragPx = 10f, trackPx = 0f), 0.001f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.hermes.client.ui.components.SlideToConfirmStateTest"`
Expected: FAIL — unresolved `slideProgress`/`isConfirmed`.

- [ ] **Step 3: Implement `SlideToConfirm.kt`**

```kotlin
package com.hermes.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlin.math.max

// Pure, unit-tested. Progress along the track (0f..1f).
fun slideProgress(dragPx: Float, trackPx: Float): Float =
    if (trackPx <= 0f) 0f else dragPx.coerceIn(0f, trackPx) / trackPx

fun isConfirmed(progress: Float): Boolean = progress >= 0.9f

/** Drag the thumb across the track to confirm a deliberate (dangerous) action. */
@Composable
fun SlideToConfirm(label: String, accent: Color, onConfirm: () -> Unit, modifier: Modifier = Modifier) {
    var trackPx by remember { mutableFloatStateOf(0f) }
    var dragPx by remember { mutableFloatStateOf(0f) }
    val progress = slideProgress(dragPx, trackPx)
    Box(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .onSizeChanged { trackPx = max(0f, it.width.toFloat() - 56f * 3) } // minus thumb width (~56dp) in px approx
            .background(accent.copy(alpha = 0.15f), CircleShape),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(label, modifier = Modifier.fillMaxWidth(), color = accent)
        Box(
            Modifier
                .size(56.dp)
                .background(accent, CircleShape)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = { if (isConfirmed(slideProgress(dragPx, trackPx))) onConfirm() else dragPx = 0f },
                        onHorizontalDrag = { _, delta -> dragPx = (dragPx + delta).coerceIn(0f, trackPx) },
                    )
                },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Color.White) }
    }
}
```
(Note: exact thumb-offset math for the visual position can be refined during Task 5 on-device polish — the *behavior* under test is `slideProgress` + `isConfirmed`, which drive confirmation. If the offset expression is awkward, translate the thumb by `dragPx` via `Modifier.offset { IntOffset(dragPx.roundToInt(), 0) }` and set `trackPx` from the box width minus the thumb width.)

- [ ] **Step 4: Run test to verify it passes; then compile**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.hermes.client.ui.components.SlideToConfirmStateTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/components/SlideToConfirm.kt app/src/test/java/com/hermes/client/ui/components/SlideToConfirmStateTest.kt
git commit -m "feat(ui): reusable SlideToConfirm (pure progress/confirm logic + composable)"
```

---

### Task 5: Approval bottom sheet + wire into ChatScreen

**Files:**
- Create: `app/src/main/java/com/hermes/client/ui/chat/ApprovalSheet.kt`
- Modify: `app/src/main/java/com/hermes/client/ui/chat/ChatScreen.kt` (~line 490, `pendingApproval` block)
- Modify: `app/src/main/java/com/hermes/client/ui/chat/ChatComponents.kt` (remove `ApprovalDialog`)

**Interfaces:**
- Consumes: `ApprovalRequest` (Task 2), `ApprovalTier`/`ApprovalChoice`/`tierFor`/`allowedScopes` (Task 1), `SlideToConfirm` (Task 4), `LocalProfileAccent.current` (`ProfileAccentColors(accent, onAccent)`).
- Produces: `@Composable fun ApprovalSheet(req: ApprovalRequest, onRespond: (ApprovalChoice) -> Unit, onDismiss: () -> Unit)`.

- [ ] **Step 1: Implement `ApprovalSheet.kt`** (Compose UI — verified on-device in Task 7, not unit-tested)

```kotlin
package com.hermes.client.ui.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.hermes.client.ui.components.SlideToConfirm
import com.hermes.client.ui.theme.LocalProfileAccent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalSheet(req: ApprovalRequest, onRespond: (ApprovalChoice) -> Unit, onDismiss: () -> Unit) {
    val accent = LocalProfileAccent.current
    val tier = tierFor(req.allowPermanent)
    val error = MaterialTheme.colorScheme.error
    val badge = if (tier == ApprovalTier.ELEVATED) error else accent.accent
    val label = req.patternKeys.firstOrNull()?.let { " · $it" } ?: ""

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(
                (if (tier == ApprovalTier.ELEVATED) "Elevated" else "Approval needed") + label,
                style = MaterialTheme.typography.titleMedium,
                color = badge,
            )
            if (req.command.isNotBlank()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
                    Text(req.command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (req.description.isNotBlank()) {
                Text(req.description, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 16.dp))
            }

            if (tier == ApprovalTier.STANDARD) {
                Button(
                    onClick = { onRespond(ApprovalChoice.ONCE) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent.accent, contentColor = accent.onAccent),
                ) { Text("Allow once") }
                OutlinedButton(onClick = { onRespond(ApprovalChoice.SESSION) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Text("Allow this run") }
                OutlinedButton(onClick = { onRespond(ApprovalChoice.ALWAYS) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Text("Always allow") }
                TextButton(onClick = { onRespond(ApprovalChoice.DENY) }, modifier = Modifier.fillMaxWidth()) { Text("Deny") }
            } else {
                // Elevated: Deny is prominent; Allow requires a deliberate slide.
                var thisRun by remember { mutableStateOf(false) }
                Button(
                    onClick = { onRespond(ApprovalChoice.DENY) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = error),
                ) { Text("Deny") }
                TextButton(onClick = { thisRun = !thisRun }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (thisRun) "Scope: allow for this run" else "Scope: allow once")
                }
                SlideToConfirm(
                    label = if (thisRun) "  → slide to allow this run" else "  → slide to allow once",
                    accent = accent.accent,
                    onConfirm = { onRespond(if (thisRun) ApprovalChoice.SESSION else ApprovalChoice.ONCE) },
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }
}
```

- [ ] **Step 2: Wire into `ChatScreen.kt`** — replace the `ApprovalDialog(...)` block:

```kotlin
state.pendingApproval?.let { req ->
    ApprovalSheet(
        req = req,
        onRespond = { vm.respondApproval(it) },
        onDismiss = { /* keep pending: do nothing until the user chooses */ },
    )
}
```

- [ ] **Step 3: Remove `ApprovalDialog` from `ChatComponents.kt`** (it's now unused; delete the function + any now-unused imports it introduced, e.g. `AlertDialog`, if nothing else uses them).

- [ ] **Step 4: Compile + assembleBeta**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:assembleBeta`
Expected: BUILD SUCCESSFUL; all prior unit tests still pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hermes/client/ui/chat/ApprovalSheet.kt app/src/main/java/com/hermes/client/ui/chat/ChatScreen.kt app/src/main/java/com/hermes/client/ui/chat/ChatComponents.kt
git commit -m "feat(approvals): touch-native tiered approval bottom sheet (Standard scopes + Elevated slide-to-allow)"
```

---

### Task 6: Tier-aware notifications

**Files:**
- Modify: `app/src/main/java/com/hermes/client/notifications/NotificationModels.kt` (add `ACTION_ALLOW_ONCE`; note `allowPermanent` need)
- Modify: `app/src/main/java/com/hermes/client/notifications/NotificationMapper.kt` (approval branch → tier-aware actions)
- Modify: `app/src/main/java/com/hermes/client/notifications/NotificationActionReceiver.kt` (send `ApprovalChoice`)
- Test: `app/src/test/java/com/hermes/client/notifications/NotificationMapperTest.kt` (extend)

**Interfaces:**
- Consumes: `ServerEvent.bool` (Task 2), `ApprovalChoice` (Task 1), `tierFor` (Task 1).
- Produces: approval `NotificationSpec` whose actions depend on `tierFor(event.bool("allow_permanent") ?: false)`: **Standard** → `NotifAction("Allow once", ACTION_ALLOW_ONCE, sid)` + `NotifAction("Deny", ACTION_DENY, sid)`; **Elevated** → `NotifAction("Deny", ACTION_DENY, sid)` only (tap opens the app). Add `const val ACTION_ALLOW_ONCE = "allow_once"`.

- [ ] **Step 1: Write the failing tests** (extend `NotificationMapperTest`)

```kotlin
@Test fun standard_approval_offers_allow_once_and_deny() {
    val e = ServerEvent("approval.request", "s1", buildJsonObject {
        put("session_id", "s1"); put("command", "git push -f"); put("allow_permanent", true)
    })
    val spec = toNotificationSpec(e, on, appInForeground = false)!!
    assertEquals(listOf(Notif.ACTION_ALLOW_ONCE, Notif.ACTION_DENY), spec.actions.map { it.action })
}

@Test fun elevated_approval_offers_deny_only() {
    val e = ServerEvent("approval.request", "s1", buildJsonObject {
        put("session_id", "s1"); put("command", "rm -rf /"); put("allow_permanent", false)
    })
    val spec = toNotificationSpec(e, on, appInForeground = false)!!
    assertEquals(listOf(Notif.ACTION_DENY), spec.actions.map { it.action })
}
```
(Reuse the file's existing `on` prefs + imports; add `com.hermes.client.data.network.ServerEvent`, `buildJsonObject`, `put` if not present.)

- [ ] **Step 2: Run to verify failure**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests "com.hermes.client.notifications.NotificationMapperTest"`
Expected: FAIL — `ACTION_ALLOW_ONCE` missing / actions still `[approve, deny]`.

- [ ] **Step 3a: `NotificationModels.kt`** — add:
```kotlin
const val ACTION_ALLOW_ONCE = "allow_once"
```
(Keep `ACTION_APPROVE`/`ACTION_DENY`; `ACTION_APPROVE` may become unused — remove if nothing references it after Task 6.)

- [ ] **Step 3b: `NotificationMapper.kt`** — replace the `EVENT_APPROVAL` branch's fixed `actions = listOf(Approve, Deny)` with tier-aware actions. The mapper takes the `ServerEvent`, so compute the tier inline:
```kotlin
Notif.EVENT_APPROVAL -> if (!prefs.approvals) null else {
    val elevated = tierFor(event.bool("allow_permanent") ?: false) == ApprovalTier.ELEVATED
    NotificationSpec(
        id = id,
        channelId = Notif.CHANNEL_APPROVALS,
        title = "Approval needed",
        body = event.str("description")?.ifBlank { null }
            ?: event.str("command")?.ifBlank { null }
            ?: "The agent is waiting for your approval.",
        route = "chat/$sid",
        actions = if (elevated) listOf(NotifAction("Deny", Notif.ACTION_DENY, sid))
                  else listOf(NotifAction("Allow once", Notif.ACTION_ALLOW_ONCE, sid), NotifAction("Deny", Notif.ACTION_DENY, sid)),
        groupKey = "approval",
    )
}
```
Add imports: `com.hermes.client.data.network.bool`, `com.hermes.client.ui.chat.tierFor`, `com.hermes.client.ui.chat.ApprovalTier`.

- [ ] **Step 3c: `NotificationActionReceiver.kt`** — map the action to a choice:
```kotlin
val choice = when (intent.action) {
    Notif.ACTION_ALLOW_ONCE -> com.hermes.client.ui.chat.ApprovalChoice.ONCE
    else -> com.hermes.client.ui.chat.ApprovalChoice.DENY
}
...
runCatching { withTimeout(8_000) { chat.respondApproval(sid, choice) } }
```
(Update the `DebugLog.log` line to log `choice` instead of `approve`.)

- [ ] **Step 4: Run tests + full build**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest :app:assembleBeta`
Expected: BUILD SUCCESSFUL; NotificationMapperTest passes including the 2 new tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hermes/client/notifications/ app/src/test/java/com/hermes/client/notifications/NotificationMapperTest.kt
git commit -m "feat(approvals): tier-aware notification actions (Standard: Allow once/Deny; Elevated: Deny/Open)"
```

---

### Task 7: On-device verification

**Files:** none (verification only). Uses the harness mock `$CLAUDE_JOB_DIR/tmp/mockgw.py` (working `/api/ws`).

- [ ] **Step 1: Patch the mock** to emit two approvals after connect — a Standard one and an Elevated one:
```python
# Standard (offers Once/Session/Always; notification Allow once + Deny)
{"method":"event","params":{"type":"approval.request","payload":{"session_id":"titletest",
  "command":"git push --force origin main","description":"force push to a protected branch",
  "pattern_keys":["force push"],"allow_permanent":True}}}
# Elevated (Tirith-flagged; slide-to-allow, Deny primary; notification Deny only)
{"method":"event","params":{"type":"approval.request","payload":{"session_id":"titletest",
  "command":"rm -rf /var/data","description":"recursive delete of a data directory",
  "pattern_keys":["recursive delete"],"allow_permanent":False}}}
```
Log the `approval.respond` params the app sends back (the mock's RPC read loop) so the chosen `choice` is observable.

- [ ] **Step 2: Build + install + drive** (emulator `hermes_test`):
  - Boot emulator; `assembleBeta`; install `app-beta.apk`; open a chat on the `titletest` session.
  - Emit the **Standard** approval → confirm the bottom sheet shows the command (monospace), "Approval needed · force push", and **Allow once / Allow this run / Always allow / Deny**. Tap **Allow this run** → mock log shows `approval.respond {choice:"session", approved:true}`.
  - Emit the **Elevated** approval → confirm the sheet shows "Elevated · recursive delete", **Deny** prominent, **no Always**, and a **slide-to-allow** track. Slide it → mock log shows `{choice:"once", approved:true}`. Re-emit and tap **Deny** → `{choice:"deny", approved:false}`.
  - Background the app + re-emit each; pull the shade: **Standard** notification shows **Allow once + Deny**; **Elevated** shows **Deny** only. Tap Standard **Allow once** → mock log `{choice:"once"}`.
  - Screenshot both sheets + both notifications.

- [ ] **Step 3: Record results** in the PR description (screenshots + the observed `choice` values). No commit (verification only).

---

## Self-Review (completed by plan author)

- **Spec coverage:** wire-format fix (Tasks 2–3), tier model (Task 1), bottom sheet both tiers (Task 5), SlideToConfirm (Task 4), tier-aware notifications (Task 6), send both `choice`+`approved` (Task 3), on-device both tiers (Task 7). Out-of-scope items (`all`, new gateway fields) intentionally excluded. ✓
- **Placeholder scan:** none — every code step has complete code; the two soft spots (SlideToConfirm thumb-offset math, ChatRepository ctor shape) are called out with the exact fallback and where to confirm. ✓
- **Type consistency:** `ApprovalChoice`/`ApprovalTier`/`tierFor(Boolean)`/`allowedScopes` (Task 1) are used unchanged in Tasks 3/5/6; `ApprovalRequest(command, description, patternKeys, allowPermanent)` (Task 2) is consumed unchanged in Task 5; `respondApproval(sessionId, ApprovalChoice)` (Task 3) is called in Task 5 (`vm.respondApproval`) and Task 6 (`chat.respondApproval`). ✓
