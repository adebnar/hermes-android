# Approval Context Enrichment — Design

**Wave:** Quick-wins (client-only). **Branch:** `feature/approval-context` (off `dev`).

**Goal:** Surface more of the context the `approval.request` event already carries on the approval sheet, so the user decides without opening the thread. Client-only — reads existing payload keys; no gateway change.

**Constraints:** Kotlin/Compose/Material3. No AI attribution; gitleaks before push; PR into `dev`.

## Reality check (from the gateway audit)
The `approval.request` event carries only: `command`, `description`, `pattern_keys`/`pattern_key`, `allow_permanent`, `smart_denied`, `choices`. There is **no** tool name, args, cwd, or diff in the payload (those would need a gateway change — out of scope). The sheet already shows the (redacted) `command` (monospace) + `description`. So the client-only enrichment is small:
1. Show **all** allowlist keys a permanent approval would grant (today only the first is shown, in the title).
2. Surface the `smart_denied` owner-override signal (currently discarded) as a distinct warning.

## Scope
- **In:** add `smartDenied` to `ApprovalRequest` + extract it in the reducer; render a "Grants:" all-keys row and a `smart_denied` warning caption on `ApprovalSheet`.
- **Out (needs gateway):** tool name, arguments, working directory, diff/preview.

## Architecture

### 1. `ui/chat/ChatUiState.kt` (modify)
Add a field to `ApprovalRequest`:
```kotlin
data class ApprovalRequest(
    val command: String,
    val description: String,
    val patternKeys: List<String>,
    val allowPermanent: Boolean,
    val smartDenied: Boolean = false,
)
```
Extract it in the `"approval.request"` reducer branch:
```kotlin
                allowPermanent = event.bool("allow_permanent") ?: false,
                smartDenied = event.bool("smart_denied") ?: false,
```

### 2. `ui/chat/ApprovalSheet.kt` (modify)
After the `description` block and before the tier buttons, add:
- **Grants row** — when `req.patternKeys.size > 0`, a `bodySmall` labeled line listing every key (so the user sees the full allowlist scope a Session/Always approval covers), styled `onSurfaceVariant`:
  ```kotlin
  if (req.patternKeys.isNotEmpty()) {
      Text(
          "Grants: ${req.patternKeys.joinToString(", ")}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(bottom = 8.dp),
      )
  }
  ```
- **smart_denied warning** — when `req.smartDenied`, an `error`-colored caption above the buttons:
  ```kotlin
  if (req.smartDenied) {
      Text(
          "Owner override — approve only this one operation.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(bottom = 8.dp),
      )
  }
  ```
No change to the tier logic or buttons.

## Data flow
```
approval.request event → reducer extracts command/description/patternKeys/allowPermanent/smartDenied → ApprovalRequest
ApprovalSheet → title (first key) + command (mono) + description + "Grants: <all keys>" + [smart_denied warning] + tier buttons
```

## Error handling
- Missing `smart_denied` → false (no warning). Empty `patternKeys` → no Grants row. All fields default-safe; the reducer already tolerates absent keys.

## Testing
- **`ChatReducerTest`** (extend): `approval.request` with `smart_denied=true` → `pendingApproval.smartDenied == true`; without it → false; with `pattern_keys` → `patternKeys` holds all keys.
- `ApprovalSheet` rendering is Compose glue — verified on-device (best-effort; approvals can't be forced on demand — the render logic is a straightforward conditional over the new fields).

## On-device verification
When a standard approval with multiple allowlist keys arrives, the sheet shows "Grants: k1, k2"; a `smart_denied` (owner-override) approval shows the red warning caption. (Approvals require an agent tool call gated by manual-approval mode; best-effort — covered by the reducer tests + reviewed render.)

## Files
| Action | Path |
|--------|------|
| Modify | `ui/chat/ChatUiState.kt` (`smartDenied` field + reducer) + `ChatReducerTest.kt` |
| Modify | `ui/chat/ApprovalSheet.kt` (Grants row + warning) |

## Build & gates
`JAVA_HOME=…`: `:app:compileDebugKotlin`, `:app:testDebugUnitTest`, `:app:assembleBeta`. gitleaks before push; PR into `dev`.
