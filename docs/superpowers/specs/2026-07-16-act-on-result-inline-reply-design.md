# Act-on-Result: Inline Reply + Broader Approval — Design

**Wave:** Quick-wins wave 3 (from `docs/ideas/2026-07-16-competitive-refresh.md`, "act-on-result"). **Branch:** `feature/act-on-result-inline-reply` (off `dev`).

**Goal:** Let the user act on an agent's result from the notification shade without opening the app — answer a "Needs your input" prompt inline, and approve a tool call *for the session* (not just once). Fully client-only; reuses the existing headless `NotificationActionReceiver` → RPC pattern.

**Positioning:** Most act-on-result actions already ship — approval notifications carry Allow-once/Deny (headless via `approval.respond`), and the activity feed has run-now / retry / open / view-full-chat. This wave adds the two missing high-value **notification** actions.

**Constraints:** Kotlin / Compose / Hilt / Material3, per-tenant accent. **Client-only** — no gateway changes; reuses `prompt.submit` and `approval.respond`. Standing repo constraints (no AI attribution; gitleaks before every push; tenant isolation; `main` only via approved PR).

---

## Correction (post-implementation review)

The final review found the original mechanism below was **wrong**: a `clarify.request` is a *blocking* agent park on the gateway that is answered only by **`clarify.respond` with the event's `request_id`**. `prompt.submit` (originally specified) hits the gateway's busy handler while the turn is `running` — it **abandons the parked question and posts a disconnected new turn**. The review also found the *existing* in-app clarify path (`ChatRepository.respondClarify`) already omits `request_id` and fails with gateway error 4009 — a pre-existing bug.

**Corrected mechanism (client-only; the `clarify.request` event carries `request_id`, added by the gateway's `_block`):** thread `request_id` end-to-end so the inline reply answers via `clarify.respond`, which also repairs the in-app bug:
- `ClarifyRequest` gains `requestId`; the `clarify.request` reducer captures `event.str("request_id")`.
- `ChatRepository.respondClarify(sessionId, requestId, answer)` sends `request_id`; `ChatViewModel.clarify` passes `pendingClarify.requestId`.
- `NotifAction` gains `requestId`; the mapper's clarify action carries it; `HermesNotifier` puts it as a `request_id` intent extra; `NotificationActionReceiver` reads it and calls `chat.respondClarify(sid, requestId, text)` (not `submit`).

The **Approve-for-session** half of this spec is unaffected and correct. Where the sections below say `prompt.submit`/`chat.submit` for the reply, read `clarify.respond`/`respondClarify(sid, requestId, text)`.

---

## Scope (locked)

- **Reply** action on `clarify.request` ("Needs your input") notifications — today they carry no actions, only tap-to-open. Android `RemoteInput` → headless `prompt.submit`.
- **Approve for session** action on **standard-tier** `approval.request` notifications — today only Allow-once/Deny. Reuses `approval.respond` with `choice = "session"`.
- **Out:** "Always" on the notification (Android caps visible actions at 3; `[Allow once, Session, Deny]` is the budget — a permanent grant stays a deliberate in-app choice); reply on `message.complete` (run-finished isn't awaiting input); true "rerun this run" (needs a gateway RPC); cron-completion notifications (gateway doesn't emit them).

---

## Architecture

Four touch points, all in the existing notification pipeline. The event→spec mapping and the action→intent decision are pure and unit-tested; the `RemoteInput`/`PendingIntent`/receiver glue is Android, verified on-device.

### 1. Model — `notifications/NotificationModels.kt`

- `NotifAction` gains a reply flag:
  ```kotlin
  data class NotifAction(
      val label: String,
      val action: String,
      val sessionId: String,
      val reply: Boolean = false, // true → an inline RemoteInput reply action, not a button
  )
  ```
- New constants in the `Notif` object:
  ```kotlin
  const val ACTION_REPLY = "reply"
  const val ACTION_ALLOW_SESSION = "allow_session"
  const val KEY_REPLY_TEXT = "reply_text" // RemoteInput result key
  ```

### 2. Event→spec mapping — `notifications/NotificationMapper.kt`

- `clarify.request` → add a single reply action (keep the existing `chat/$sid` tap route + title "Needs your input"):
  ```kotlin
  actions = listOf(NotifAction("Reply", Notif.ACTION_REPLY, sid, reply = true))
  ```
- `approval.request` **standard tier** → insert "Approve for session" between Allow-once and Deny:
  ```kotlin
  listOf(
      NotifAction("Allow once", Notif.ACTION_ALLOW_ONCE, sid),
      NotifAction("Session", Notif.ACTION_ALLOW_SESSION, sid),
      NotifAction("Deny", Notif.ACTION_DENY, sid),
  )
  ```
  **Elevated tier is unchanged** (Deny-only) — the tiered-approvals safety design stays intact.

### 3. Notifier — `notifications/HermesNotifier.kt`

In `post(spec)`, branch the per-action build:
- `action.reply == false` → the existing plain `addAction(0, label, actionIntent(a, id))` with a `FLAG_IMMUTABLE` broadcast PendingIntent (unchanged).
- `action.reply == true` → build a `NotificationCompat.Action` carrying a `RemoteInput`:
  ```kotlin
  val remoteInput = RemoteInput.Builder(Notif.KEY_REPLY_TEXT).setLabel("Reply…").build()
  val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
  val replyAction = NotificationCompat.Action.Builder(0, a.label, replyPendingIntent(a, spec.id, piFlags))
      .addRemoteInput(remoteInput)
      .setAllowGeneratedReplies(false)
      .build()
  b.addAction(replyAction)
  ```
  The reply PendingIntent targets the **explicit** `NotificationActionReceiver` component (same as `actionIntent`), so `FLAG_MUTABLE` is safe — an explicit intent can't be redirected; mutability only lets the system attach the `RemoteInput` results. (Existing button actions keep `FLAG_IMMUTABLE`.)

### 4. Receiver — `notifications/NotificationActionReceiver.kt`

A pure decision helper makes the dispatch unit-testable:
```kotlin
sealed interface ReceiverAction {
    data class Approval(val choice: ApprovalChoice) : ReceiverAction
    data object Reply : ReceiverAction
    data object Unknown : ReceiverAction
}

fun receiverActionFor(action: String?): ReceiverAction = when (action) {
    Notif.ACTION_ALLOW_ONCE -> ReceiverAction.Approval(ApprovalChoice.ONCE)
    Notif.ACTION_ALLOW_SESSION -> ReceiverAction.Approval(ApprovalChoice.SESSION)
    Notif.ACTION_DENY -> ReceiverAction.Approval(ApprovalChoice.DENY)
    Notif.ACTION_REPLY -> ReceiverAction.Reply
    else -> ReceiverAction.Unknown
}
```
`onReceive` (headless via `goAsync()` + `CoroutineScope(Dispatchers.IO)` + `withTimeout(8_000)`, mirroring the shipped approval handler):
- `Approval(choice)` → `chat.respondApproval(sid, choice)` (covers Allow-once, **Session**, Deny), cancel notif on success.
- `Reply` → `RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notif.KEY_REPLY_TEXT)`; trim; if non-blank → `chat.submit(sid, text.toString())` (the existing `prompt.submit`), cancel notif on success; blank → do nothing (leave the notification).
- `Unknown` → ignore.

`chat.submit(sessionId, text)` and `chat.respondApproval(sessionId, choice)` are existing headless `suspend` funcs on `ChatRepository` over the gateway WebSocket. They require the socket to be connected — the same precondition the shipped approval action already relies on (the foreground `GatewayConnectionService` keeps it alive when notifications are enabled).

---

## Data flow

```
clarify.request event ─ NotificationMapper ─→ spec.actions = [Reply(reply=true)]
approval.request event ─ NotificationMapper ─→ spec.actions = [Allow once, Session, Deny]  (standard tier)
                                   │
                          HermesNotifier.post
              reply? → NotificationCompat.Action + RemoteInput + FLAG_MUTABLE PendingIntent
              button? → addAction + FLAG_IMMUTABLE PendingIntent
                                   │  (user taps / types in the shade)
                          NotificationActionReceiver.onReceive  (headless, goAsync + withTimeout)
                    receiverActionFor(action)
              Reply → chat.submit(sid, RemoteInput text) ── prompt.submit
              Approval(SESSION/ONCE/DENY) → chat.respondApproval(sid, choice) ── approval.respond
                                   │ on success → notifier cancels the notification
```

## Error handling

- Blank/absent reply text → no send, notification left in place (user can retry).
- RPC failure / timeout (`withTimeout(8_000)`) → notification is **not** cancelled (so the action can be retried), mirroring the shipped approval handler's success-gated cancel.
- Socket not connected → the RPC call fails within the timeout; notification stays. (No new failure mode vs. the existing approval action.)
- Unknown action string → ignored.

## Testing

**Pure `NotificationMapperTest` additions** (JUnit, existing pure test for `toNotificationSpec`):
- `clarify.request` → exactly one action, `reply == true`, `label == "Reply"`, `action == ACTION_REPLY`, correct `sessionId`; tap route still `chat/$sid`.
- standard-tier `approval.request` → actions `[Allow once, Session, Deny]` with `ACTION_ALLOW_SESSION` present and `reply == false` on all three.
- elevated-tier `approval.request` → unchanged (Deny-only), no Session action.

**Pure `receiverActionFor` tests** (new small test):
- each action constant → the correct `ReceiverAction` (incl. `ACTION_ALLOW_SESSION → Approval(SESSION)`, `ACTION_REPLY → Reply`); `null`/unknown → `Unknown`.

`RemoteInput` extraction, PendingIntent flags, and the receiver's coroutine glue are Android — no unit tests (repo style); covered by on-device verification.

## On-device verification (best-effort)

- **Approve-for-session:** trigger a standard-tier tool approval; confirm the notification shows `[Allow once, Session, Deny]`; tap **Session** → the tool proceeds and subsequent same-type calls in that session are auto-allowed (session scope), notification clears.
- **Inline reply:** when a `clarify.request` ("Needs your input") notification appears, confirm a **Reply** action with an inline text field; type and send → the text reaches the session (a new user turn) and the notification clears.
- **Caveat:** a `clarify.request` requires an agent to actually ask a clarifying question, which can't be forced on demand — so the reply path is verified opportunistically; the pure tests + review are the primary correctness gate. If no clarify arises during the session, note it and rely on the tests.

## Files

| Action | Path | Responsibility |
|--------|------|----------------|
| Modify | `notifications/NotificationModels.kt` | `NotifAction.reply` flag + `ACTION_REPLY`/`ACTION_ALLOW_SESSION`/`KEY_REPLY_TEXT` |
| Modify | `notifications/NotificationMapper.kt` | reply action on clarify; Session action on standard approval |
| Modify | `notifications/HermesNotifier.kt` | build RemoteInput reply action (MUTABLE explicit PI) vs. plain button |
| Modify | `notifications/NotificationActionReceiver.kt` | `receiverActionFor` helper + Reply→`submit` / Session→`respondApproval` headless handling |
| Modify/Create test | `test/…/notifications/NotificationMapperTest.kt` | mapper action assertions |
| Create test | `test/…/notifications/ReceiverActionTest.kt` | `receiverActionFor` mapping |

## Build & gates

`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`: `:app:compileDebugKotlin`, `:app:testDebugUnitTest`, `:app:assembleBeta`. gitleaks before every push; PR into `dev`.
