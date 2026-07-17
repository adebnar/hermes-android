# Approval Context Enrichment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Checkbox steps.

**Goal:** Surface the full allowlist keys + the `smart_denied` signal (already in the event) on the approval sheet.

**Spec:** `docs/superpowers/specs/2026-07-17-approval-context-design.md`

## Global Constraints
- Client-only (reads existing payload keys; no gateway change). No AI attribution.
- Build env: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
- Branch `feature/approval-context` (off `dev`).

---

### Task 1: `smartDenied` field + reducer extraction

**Files:** Modify `app/src/main/java/com/hermes/client/ui/chat/ChatUiState.kt`; Test `app/src/test/java/com/hermes/client/ui/chat/ChatReducerTest.kt` (extend)

- [ ] **Step 1: Write the failing test.** Add to `ChatReducerTest.kt` (mirror the existing `approval_request_sets_pending` test's `ev("approval.request") { put(...) }` pattern):
```kotlin
    @Test fun approval_request_extracts_smart_denied_and_all_keys() {
        var s = ChatUiState.empty()
        s = s.reduce(ev("approval.request") {
            put("command", "rm -rf?")
            put("smart_denied", true)
            putJsonArray("pattern_keys") { add("shell.rm"); add("shell.dangerous") }
        })
        assertEquals(true, s.pendingApproval?.smartDenied)
        assertEquals(listOf("shell.rm", "shell.dangerous"), s.pendingApproval?.patternKeys)
    }

    @Test fun approval_request_smart_denied_defaults_false() {
        var s = ChatUiState.empty()
        s = s.reduce(ev("approval.request") { put("command", "ls") })
        assertEquals(false, s.pendingApproval?.smartDenied)
    }
```
(If `putJsonArray`/`add` require imports — `kotlinx.serialization.json.putJsonArray`, `kotlinx.serialization.json.add` — add them, or match however the existing tests build a JSON array. If the existing `ev` helper's builder differs, follow its exact shape; the assertions are the contract.)

- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest --tests "com.hermes.client.ui.chat.ChatReducerTest"` → FAIL (unresolved `smartDenied`).

- [ ] **Step 3: Implement.** In `ChatUiState.kt`, add the field to `ApprovalRequest`:
```kotlin
data class ApprovalRequest(
    val command: String,
    val description: String,
    val patternKeys: List<String>,
    val allowPermanent: Boolean,
    val smartDenied: Boolean = false,
)
```
And extract it in the `"approval.request"` reducer branch (add after the `allowPermanent = …` line):
```kotlin
                allowPermanent = event.bool("allow_permanent") ?: false,
                smartDenied = event.bool("smart_denied") ?: false,
```

- [ ] **Step 4:** Run the test → PASS (existing approval tests + the 2 new ones).

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/hermes/client/ui/chat/ChatUiState.kt \
        app/src/test/java/com/hermes/client/ui/chat/ChatReducerTest.kt
git commit -m "feat: extract smart_denied from approval.request events"
```

---

### Task 2: Approval sheet enrichment

**Files:** Modify `app/src/main/java/com/hermes/client/ui/chat/ApprovalSheet.kt`

**Interfaces:** Consumes `ApprovalRequest.smartDenied` + `patternKeys` (Task 1).

- [ ] **Step 1: Add the Grants row + warning.** In `ApprovalSheet.kt`, after the `description` block (the `if (req.description.isNotBlank()) { … }`) and BEFORE `if (tier == ApprovalTier.STANDARD) { … }`, insert:
```kotlin
            if (req.patternKeys.isNotEmpty()) {
                Text(
                    "Grants: ${req.patternKeys.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            if (req.smartDenied) {
                Text(
                    "Owner override — approve only this one operation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
```
No other change (tier logic + buttons unchanged).

- [ ] **Step 2: Compile + full suite + assembleBeta.** Run each (JAVA_HOME set): `:app:compileDebugKotlin`, `:app:testDebugUnitTest` (0 failures), `:app:assembleBeta` — all BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**
```bash
git add app/src/main/java/com/hermes/client/ui/chat/ApprovalSheet.kt
git commit -m "feat: show full allowlist grants + smart-denied warning on the approval sheet"
```

---

### Task 3: On-device verification (best-effort)

- [ ] **Step 1:** `:app:installBeta`.
- [ ] **Step 2:** If a standard tool approval with multiple allowlist keys can be triggered, confirm the sheet shows "Grants: k1, k2" under the command/description; a `smart_denied` approval shows the red "Owner override…" caption.
- [ ] **Step 3:** Note: approvals require an agent tool call under manual-approval mode and can't be forced on demand — if none arises, record that the render is covered by the reducer tests + code review. Record pass/fail in the PR.

---

## Notes for the executor
- Do NOT try to add tool name / args / cwd / diff — those aren't in the `approval.request` payload (a gateway change, explicit anti-scope).
- Keep the tier logic and buttons untouched; this is purely additive display.
