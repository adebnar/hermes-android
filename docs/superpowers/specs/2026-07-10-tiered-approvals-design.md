# Touch-native tiered approvals — design (Phase 3)

**Status:** approved design, ready for implementation plan
**Branch:** `feature/tiered-approvals` → PR into `dev`
**Roadmap:** Phase 3 of `docs/ideas/improvement-roadmap-2026-07-07.md` ("touch-native, tiered approvals")

## Goal

Make agent approvals correct and touch-native on a phone. Today an approval reaches the app when
the gateway flags a **dangerous** command (low-risk commands are auto-approved server-side), but the
app's approval UI is broken and shows nothing actionable. Phase 3 fixes the wire format so approvals
work and show the command, then layers a **two-tier bottom-sheet** interaction on top: a normal
"Standard" tier and an "Elevated" tier (Tirith-flagged) whose *Allow* requires a deliberate
slide-to-confirm. No new gateway endpoints — everything uses fields and scopes the gateway already
sends.

## Current state (why the fix is needed)

- **Request (in):** the app parses `approval.request` by reading a `prompt` payload key
  (`ChatUiState.kt:86`, `ApprovalRequest(val prompt: String)` at `ChatUiState.kt:10`). The gateway
  payload has **no `prompt`** — it sends `command`, `pattern_key`, `pattern_keys`, `description`,
  `allow_permanent` (`tools/approval.py`; emitted via `_emit_approval_request`, `server.py:1150`). So
  the current in-chat `ApprovalDialog` (`ChatComponents.kt:431`, shown from `ChatScreen.kt:490`)
  renders an **empty** body.
- **Response (out):** the app sends `approval.respond {session_id, approved:Boolean}`
  (`ChatRepository.kt:126`). The gateway's `@method("approval.respond")` (`server.py:10186`) reads
  **`choice`** (default `"deny"`) and **`all`** and ignores `approved` — so a tapped "Approve"
  degrades to **deny**.
- **Notification:** `NotificationMapper.kt` builds Approve/Deny actions whose receiver
  (`NotificationActionReceiver.kt:21`) calls the same `respondApproval(sid, approve:Boolean)`.

## Gateway capabilities we build on (already exist)

- **Request payload fields:** `command` (redacted), `pattern_key` / `pattern_keys` (danger-category
  ids, e.g. `"recursive delete"`, `"force push"`), `description` (human danger text),
  `allow_permanent` (Boolean; `false` = Tirith security scanner flagged it → *Always* must not be
  offered).
- **Response scopes:** `approval.respond {choice, all}` with `choice ∈ once | session | always | deny`.
  `once` = allow this time; `session` = allow for the rest of this run; `always` = permanent allowlist
  (`config.yaml`); `deny` = block. `all` resolves every pending approval in the session.

## Design

### A. Wire-format fix (foundation)

- Expand the domain model:
  ```kotlin
  data class ApprovalRequest(
      val command: String,
      val description: String,
      val patternKeys: List<String>,
      val allowPermanent: Boolean,
  )
  ```
  Parse from the `approval.request` payload (`command`, `description`, `pattern_keys`,
  `allow_permanent`; `pattern_key` as a single-element fallback). Missing fields degrade gracefully
  (empty string / empty list / `allowPermanent=false` — the safe default).
- Replace the boolean response with a scoped one:
  ```kotlin
  enum class ApprovalChoice(val wire: String) { ONCE("once"), SESSION("session"), ALWAYS("always"), DENY("deny") }
  suspend fun ChatRepository.respondApproval(sessionId: String, choice: ApprovalChoice)
  // sends {session_id, choice: choice.wire, approved: choice != DENY}
  ```
  **Send both `choice` and `approved`** (the derived boolean) so the response works whether the
  gateway reads the new `choice` field or the old `approved` one — a cheap hedge against the
  gateway-version skew noted below. The gateway ignores whichever field it doesn't use.
  `ChatViewModel` exposes `respondApproval(choice: ApprovalChoice)` (replaces `approve(Boolean)`),
  clearing `pendingApproval` first. `all` is **not** sent in v1 (single approval at a time).

### B. Tier logic (pure, unit-tested)

```kotlin
enum class ApprovalTier { STANDARD, ELEVATED }
fun tierFor(req: ApprovalRequest): ApprovalTier = if (req.allowPermanent) STANDARD else ELEVATED
fun allowedScopes(tier: ApprovalTier): List<ApprovalChoice> = when (tier) {
    STANDARD -> listOf(ONCE, SESSION, ALWAYS)   // + DENY always available
    ELEVATED -> listOf(ONCE, SESSION)           // no ALWAYS; DENY primary
}
```

### C. Bottom sheet (`ApprovalSheet`, per-tenant accent)

A modal bottom sheet (replaces the center `AlertDialog`), shown from `pendingApproval`:
- **Header:** tier badge + primary pattern label — e.g. *"Elevated · recursive delete"* (Standard
  tinted with the profile accent; Elevated tinted with an error/red tone).
- **Body:** `command` in a scrollable monospace block (it's the redacted command); `description`
  below it.
- **Actions (thumb zone):**
  - **Standard:** filled buttons **Allow once** / **Allow this run** / **Always allow**, and a text
    **Deny**. Each maps to the `ApprovalChoice` and calls `respondApproval`.
  - **Elevated:** **Deny** is the prominent (filled) action; *Always* is absent; **Allow** is a
    `SlideToConfirm` track labelled *"→ slide to allow once"* (with a small toggle for *this run* vs
    *once*). Completing the slide fires `respondApproval(ONCE|SESSION)`.
- Dismissing the sheet (scrim/back) does **nothing** to the pending approval (no accidental
  approve/deny); it re-opens from `pendingApproval` until resolved.

### D. Reusable `SlideToConfirm` composable

A horizontal track with a draggable thumb; drag past ~90% triggers `onConfirm()` and animates to
"done"; releasing before that snaps back. Accent-tinted. Used for Elevated *Allow*; independently
unit-testable at the state-holder level (a pure progress→confirmed transition).

### E. Notification path

Notifications can't host a slide, so:
- **Standard:** inline **Allow once** (`choice=once`) + **Deny** actions; tapping the body opens the
  app to the sheet for Session/Always.
- **Elevated:** **Deny** + **Open** only — no inline allow, forcing the deliberate in-app slide. The
  mapper must therefore carry enough to know the tier: include `allow_permanent` in the
  `approval.request` → notification mapping so `NotificationMapper` picks the action set.
- The `NotificationActionReceiver` sends `respondApproval(sid, ONCE|DENY)` (choice-based, not boolean).

## Data flow

```
approval.request {command, description, pattern_keys, allow_permanent, session_id}
  → ServerEvent → ChatUiState.reduce → pendingApproval: ApprovalRequest
  → ChatScreen shows ApprovalSheet (tier = tierFor(req))
  → user picks scope (Standard buttons) or slides (Elevated)
  → ChatViewModel.respondApproval(choice) → ChatRepository.respondApproval → WS approval.respond {session_id, choice}
Notification (bg): approval.request → NotificationMapper (tier-aware actions) → NotificationActionReceiver → respondApproval(choice)
```

## Components / files

- `ui/chat/ChatUiState.kt` — expand `ApprovalRequest`; update the `approval.request` reduce to parse the new fields.
- `ui/chat/ApprovalTier.kt` *(new)* — `ApprovalTier`, `ApprovalChoice`, `tierFor`, `allowedScopes` (pure).
- `data/repository/ChatRepository.kt` — `respondApproval(sessionId, choice: ApprovalChoice)` (replaces the boolean).
- `ui/chat/ChatViewModel.kt` — `respondApproval(choice)` (replaces `approve(Boolean)`).
- `ui/chat/ApprovalSheet.kt` *(new)* — the bottom sheet; replaces `ApprovalDialog` usage in `ChatScreen.kt`.
- `ui/components/SlideToConfirm.kt` *(new)* — reusable slide-to-confirm.
- `notifications/NotificationMapper.kt` + `NotificationModels.kt` — tier-aware approval actions (add an `allow once` action; Elevated → Deny/Open only); carry `allowPermanent`.
- `notifications/NotificationActionReceiver.kt` — send `ApprovalChoice` instead of a boolean.

## Testing

- **Pure unit tests:** `tierFor` / `allowedScopes` (Standard vs Elevated); `ApprovalRequest` parsing from a representative payload (and graceful defaults when fields are missing); `respondApproval` sends `{session_id, choice}` with the right wire value; `NotificationMapper` returns the correct action set per tier; `SlideToConfirm` progress→confirmed transition.
- **On-device:** drive both tiers via the harness mock emitting a Standard (`allow_permanent=true`) and an Elevated (`allow_permanent=false`) `approval.request`; verify the sheet, the three Standard scopes, the Elevated slide-to-allow + Deny-primary, and that the correct `choice` reaches the WS. Verify the notification action sets per tier.

## Out of scope (v1 — YAGNI)

- Resolve-**all**-pending (`all:true`) — the gateway supports it; deferred until multiple concurrent approvals are common.
- Any new gateway field (explicit numeric risk level, reversibility hint, free-text deny reason).
- Client-side severity taxonomy from `pattern_keys` beyond the label — tiering stays on the
  gateway's `allow_permanent` bit (honest, no invented severity).

## Risks / notes

- **Gateway skew:** this design targets the `approval.respond {choice}` + `command/description/allow_permanent`
  contract in the current `~/.hermes/hermes-agent` checkout. Verify against the running gateway before
  promotion (the fix is a no-op improvement if the gateway ever also accepted `approved`, but the
  `choice` form is the authoritative one).
- The `always` scope writes a permanent allowlist entry in the gateway's `config.yaml` — intended, and
  only offered for Standard (`allow_permanent=true`).
