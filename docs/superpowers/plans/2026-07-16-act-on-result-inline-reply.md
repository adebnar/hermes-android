# Act-on-Result: Inline Reply + Broader Approval — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add two client-only notification actions — an inline **Reply** to "Needs your input" (`clarify.request`) notifications via Android `RemoteInput` → headless `prompt.submit`, and an **Approve for session** action on standard-tier approval notifications via the existing `approval.respond`.

**Architecture:** All changes live in the existing notification pipeline (`NotificationModels` → `NotificationMapper` → `HermesNotifier` → `NotificationActionReceiver`). The event→spec mapping and the action→intent decision are pure and unit-tested; the `RemoteInput`/`PendingIntent` glue is Android, verified on-device.

**Tech Stack:** Kotlin, AndroidX Core (`NotificationCompat`, `RemoteInput`), Hilt, Coroutines.

**Spec:** `docs/superpowers/specs/2026-07-16-act-on-result-inline-reply-design.md`

## Global Constraints

- Client-only: reuse `ChatRepository.submit(sessionId, text)` (→ `prompt.submit`) and `ChatRepository.respondApproval(sessionId, choice)` (→ `approval.respond`, wire `"session"` already accepted). No gateway edits.
- Android caps visible notification actions at 3: standard-tier approval is `[Allow once, Session, Deny]`. **No "Always" on the notification.** **Elevated-tier approval unchanged (Deny-only).**
- Reply is scoped to `clarify.request` only (not `message.complete`).
- The reply PendingIntent must be `FLAG_MUTABLE` (direct-reply requirement) and **explicit** (targets `NotificationActionReceiver`); button PendingIntents stay `FLAG_IMMUTABLE`.
- Tests: pure JUnit (`NotificationMapperTest`, new `ReceiverActionTest`). No Compose/instrumentation tests.
- No AI/assistant attribution in commits or files.
- Build env: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
- Branch: `feature/act-on-result-inline-reply` (off `dev`; spec committed at `a1947b8`). All commits land here.

---

### Task 1: Model constants + mapper actions

**Files:**
- Modify: `app/src/main/java/com/hermes/client/notifications/NotificationModels.kt`
- Modify: `app/src/main/java/com/hermes/client/notifications/NotificationMapper.kt`
- Test: `app/src/test/java/com/hermes/client/notifications/NotificationMapperTest.kt`

**Interfaces:**
- Produces: `NotifAction(label, action, sessionId, reply: Boolean = false)`; constants `Notif.ACTION_REPLY = "reply"`, `Notif.ACTION_ALLOW_SESSION = "allow_session"`, `Notif.KEY_REPLY_TEXT = "reply_text"`. Mapper: `clarify.request` → one reply action; standard-tier `approval.request` → `[Allow once, Session, Deny]`.

- [ ] **Step 1: Update the failing tests**

In `NotificationMapperTest.kt`, make these edits (they will fail until the model/mapper change lands):

Replace the assertion in `approval_makes_high_priority_spec_with_actions` (the `assertEquals(listOf(Notif.ACTION_ALLOW_ONCE, Notif.ACTION_DENY), ...)` line) with:
```kotlin
        assertEquals(
            listOf(Notif.ACTION_ALLOW_ONCE, Notif.ACTION_ALLOW_SESSION, Notif.ACTION_DENY),
            spec.actions.map { it.action },
        )
        assertTrue(spec.actions.none { it.reply })
```

Replace the whole `standard_approval_offers_allow_once_and_deny` test with:
```kotlin
    @Test fun standard_approval_offers_allow_once_session_and_deny() {
        val e = ServerEvent("approval.request", "s1", buildJsonObject {
            put("session_id", "s1"); put("command", "git push -f"); put("allow_permanent", true)
        })
        val spec = toNotificationSpec(e, on, appInForeground = false)!!
        assertEquals(
            listOf(Notif.ACTION_ALLOW_ONCE, Notif.ACTION_ALLOW_SESSION, Notif.ACTION_DENY),
            spec.actions.map { it.action },
        )
    }
```

Replace the body of `clarify_notifies_with_question_regardless_of_foreground` (the `assertTrue(spec.actions.isEmpty())` line) with:
```kotlin
        assertEquals(1, spec.actions.size)
        val reply = spec.actions.single()
        assertEquals(Notif.ACTION_REPLY, reply.action)
        assertEquals("Reply", reply.label)
        assertTrue(reply.reply)
        assertEquals("c1", reply.sessionId)
```

Add a new test:
```kotlin
    @Test fun elevated_approval_has_no_session_action() {
        val e = ServerEvent("approval.request", "s1", buildJsonObject {
            put("session_id", "s1"); put("command", "rm -rf /"); put("allow_permanent", false)
        })
        val spec = toNotificationSpec(e, on, appInForeground = false)!!
        assertEquals(listOf(Notif.ACTION_DENY), spec.actions.map { it.action })
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.hermes.client.notifications.NotificationMapperTest"`
Expected: FAIL — `Notif.ACTION_ALLOW_SESSION`/`ACTION_REPLY` unresolved and/or the action-list assertions mismatch.

- [ ] **Step 3: Update the model**

In `NotificationModels.kt`, change the `NotifAction` data class and add three constants to the `Notif` object.

Replace:
```kotlin
/** An inline notification action (Allow once/Deny) carrying the target session. */
data class NotifAction(val label: String, val action: String, val sessionId: String)
```
with:
```kotlin
/**
 * An inline notification action carrying the target session. [reply] = true marks a direct-reply
 * action (Android RemoteInput text field) rather than a plain button.
 */
data class NotifAction(
    val label: String,
    val action: String,
    val sessionId: String,
    val reply: Boolean = false,
)
```

In the `Notif` object, replace:
```kotlin
    const val ACTION_ALLOW_ONCE = "allow_once"
    const val ACTION_DENY = "deny"
```
with:
```kotlin
    const val ACTION_ALLOW_ONCE = "allow_once"
    const val ACTION_ALLOW_SESSION = "allow_session"
    const val ACTION_DENY = "deny"
    const val ACTION_REPLY = "reply"

    // RemoteInput result key for the inline reply on a clarify ("Needs your input") notification.
    const val KEY_REPLY_TEXT = "reply_text"
```

- [ ] **Step 4: Update the mapper**

In `NotificationMapper.kt`, in the `Notif.EVENT_APPROVAL` branch, replace the `actions = ...` expression with (elevated unchanged; standard now includes Session):
```kotlin
                actions = if (elevated) listOf(NotifAction("Deny", Notif.ACTION_DENY, sid))
                          else listOf(
                              NotifAction("Allow once", Notif.ACTION_ALLOW_ONCE, sid),
                              NotifAction("Session", Notif.ACTION_ALLOW_SESSION, sid),
                              NotifAction("Deny", Notif.ACTION_DENY, sid),
                          ),
```

In the `Notif.EVENT_CLARIFY` branch, replace `actions = emptyList()` with a single reply action:
```kotlin
            route = "chat/$sid",
            actions = listOf(NotifAction("Reply", Notif.ACTION_REPLY, sid, reply = true)),
            groupKey = "approval",
```
(Keep the rest of the CLARIFY branch — title "Needs your input", body, channel — unchanged.)

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.hermes.client.notifications.NotificationMapperTest"`
Expected: PASS (all existing + updated + new cases green).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/hermes/client/notifications/NotificationModels.kt \
        app/src/main/java/com/hermes/client/notifications/NotificationMapper.kt \
        app/src/test/java/com/hermes/client/notifications/NotificationMapperTest.kt
git commit -m "feat: add reply + approve-for-session notification actions to the mapper"
```

---

### Task 2: Receiver action helper + headless handling

**Files:**
- Modify: `app/src/main/java/com/hermes/client/notifications/NotificationActionReceiver.kt`
- Test: `app/src/test/java/com/hermes/client/notifications/ReceiverActionTest.kt`

**Interfaces:**
- Consumes: `Notif.ACTION_*` constants (Task 1); `ChatRepository.submit(sessionId, text)` and `ChatRepository.respondApproval(sessionId, choice)`; `ApprovalChoice` (`ui.chat`, values `ONCE`/`SESSION`/`DENY`).
- Produces: `sealed interface ReceiverAction { Approval(choice); Reply; Unknown }` and `fun receiverActionFor(action: String?): ReceiverAction`.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/hermes/client/notifications/ReceiverActionTest.kt`:
```kotlin
package com.hermes.client.notifications

import com.hermes.client.ui.chat.ApprovalChoice
import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiverActionTest {
    @Test fun allow_once_maps_to_approval_once() {
        assertEquals(ReceiverAction.Approval(ApprovalChoice.ONCE), receiverActionFor(Notif.ACTION_ALLOW_ONCE))
    }

    @Test fun allow_session_maps_to_approval_session() {
        assertEquals(ReceiverAction.Approval(ApprovalChoice.SESSION), receiverActionFor(Notif.ACTION_ALLOW_SESSION))
    }

    @Test fun deny_maps_to_approval_deny() {
        assertEquals(ReceiverAction.Approval(ApprovalChoice.DENY), receiverActionFor(Notif.ACTION_DENY))
    }

    @Test fun reply_maps_to_reply() {
        assertEquals(ReceiverAction.Reply, receiverActionFor(Notif.ACTION_REPLY))
    }

    @Test fun null_and_unknown_map_to_unknown() {
        assertEquals(ReceiverAction.Unknown, receiverActionFor(null))
        assertEquals(ReceiverAction.Unknown, receiverActionFor("something_else"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.hermes.client.notifications.ReceiverActionTest"`
Expected: FAIL — `ReceiverAction`/`receiverActionFor` unresolved.

- [ ] **Step 3: Implement the helper + wire onReceive**

Rewrite `NotificationActionReceiver.kt` to add the sealed type + pure helper and route Reply/Session headlessly:
```kotlin
package com.hermes.client.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.hermes.client.data.diagnostics.DebugLog
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.ui.chat.ApprovalChoice
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

/** What a received notification-action intent should do. Pure/testable, no Android deps. */
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

/**
 * Handles a notification action headlessly: Allow-once/Session/Deny → `approval.respond`; an inline
 * Reply → `prompt.submit`. Runs a single RPC on a background scope; only clears the notification
 * once the RPC succeeds, so a failed action isn't silently lost.
 */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {
    @Inject lateinit var chat: ChatRepository
    @Inject lateinit var notifier: HermesNotifier

    override fun onReceive(context: Context, intent: Intent) {
        val sid = intent.getStringExtra("session_id") ?: return
        val notifId = intent.getIntExtra("notif_id", -1)
        val ra = receiverActionFor(intent.action)
        val replyText = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(Notif.KEY_REPLY_TEXT)?.toString()?.trim()

        // Nothing actionable, or a blank reply → leave the notification up (retryable) and stop.
        if (ra is ReceiverAction.Unknown) return
        if (ra is ReceiverAction.Reply && replyText.isNullOrBlank()) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching {
                    withTimeout(8_000) {
                        when (ra) {
                            is ReceiverAction.Approval -> chat.respondApproval(sid, ra.choice)
                            ReceiverAction.Reply -> chat.submit(sid, replyText!!)
                            ReceiverAction.Unknown -> Unit // unreachable (returned above)
                        }
                    }
                }.onSuccess {
                    if (notifId != -1) notifier.cancel(notifId)
                }.onFailure { e ->
                    DebugLog.log("notif", "action failed session=$sid action=${intent.action}: ${e.message}")
                }
            } finally {
                pending.finish()
            }
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.hermes.client.notifications.ReceiverActionTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Compile the app**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (Confirms `chat.submit`/`chat.respondApproval` signatures and `ApprovalChoice.SESSION` resolve.)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/hermes/client/notifications/NotificationActionReceiver.kt \
        app/src/test/java/com/hermes/client/notifications/ReceiverActionTest.kt
git commit -m "feat: handle reply + approve-for-session notification actions headlessly"
```

---

### Task 3: Notifier builds the RemoteInput reply action

**Files:**
- Modify: `app/src/main/java/com/hermes/client/notifications/HermesNotifier.kt`
- Test: none new (Android glue; covered by compile + assembleBeta + on-device Task 4).

**Interfaces:**
- Consumes: `NotifAction.reply` + `Notif.KEY_REPLY_TEXT` (Task 1). Reply actions are posted for specs whose `NotifAction.reply == true`.

- [ ] **Step 1: Add the RemoteInput import**

In `HermesNotifier.kt`, add to the imports:
```kotlin
import androidx.core.app.RemoteInput
```

- [ ] **Step 2: Branch the action build in `post()`**

Replace the action loop in `post(spec)`:
```kotlin
        spec.actions.forEach { a ->
            b.addAction(0, a.label, actionIntent(a, spec.id))
        }
```
with:
```kotlin
        spec.actions.forEach { a ->
            if (a.reply) {
                val remoteInput = RemoteInput.Builder(Notif.KEY_REPLY_TEXT).setLabel("Reply…").build()
                val action = NotificationCompat.Action.Builder(0, a.label, replyIntent(a, spec.id))
                    .addRemoteInput(remoteInput)
                    .setAllowGeneratedReplies(false)
                    .build()
                b.addAction(action)
            } else {
                b.addAction(0, a.label, actionIntent(a, spec.id))
            }
        }
```

- [ ] **Step 3: Add the mutable reply PendingIntent builder**

Add this private method next to `actionIntent(...)` in `HermesNotifier`:
```kotlin
    private fun replyIntent(a: NotifAction, notifId: Int): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = a.action
            putExtra("session_id", a.sessionId)
            putExtra("notif_id", notifId)
        }
        // Direct-reply requires FLAG_MUTABLE so the system can attach the RemoteInput results.
        // The intent is explicit (our own receiver), so it can't be redirected — mutability is safe.
        return PendingIntent.getBroadcast(
            context,
            (a.action + a.sessionId).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }
```
(Leave `actionIntent`/`pendingFlags` as-is: buttons keep `FLAG_IMMUTABLE`.)

- [ ] **Step 4: Compile**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run the full unit-test suite**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 0 failures (includes the Task 1 + Task 2 suites).

- [ ] **Step 6: Assemble the beta variant**

Run: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleBeta`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/hermes/client/notifications/HermesNotifier.kt
git commit -m "feat: post inline-reply notification action with RemoteInput"
```

---

### Task 4: On-device verification (best-effort)

**Files:** none (manual verification on the emulator or a connected device with notifications enabled + a reachable gateway).

There is no automated Compose/instrumentation test (per Global Constraints); this manual pass is the acceptance gate for the `RemoteInput`/PendingIntent glue. Notifications must be enabled in the app and the foreground service running (so the WS is connected for the headless RPC).

- [ ] **Step 1: Install the beta build**

Run:
```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:installBeta
```
Expected: `Installed on 1 device`.

- [ ] **Step 2: Verify the approve-for-session action**

Trigger (or wait for) a **standard-tier** tool approval so an "Approval needed" notification posts. Confirm it shows three actions: **Allow once · Session · Deny**. Tap **Session** → the tool proceeds and the notification clears; a subsequent same-type tool call in that session is auto-allowed (session scope). Confirm an **elevated**-tier approval still shows **Deny** only.

- [ ] **Step 3: Verify the inline reply action**

When a **"Needs your input"** (`clarify.request`) notification appears, confirm it shows a **Reply** action that expands to an inline text field. Type an answer and send → confirm the text reaches the session as a new user turn (open the chat to verify) and the notification clears. Send a **blank** reply → confirm nothing is sent and the notification remains.

- [ ] **Step 4: Note the caveat**

A `clarify.request` requires an agent to actually ask a clarifying question, which cannot be forced on demand. If none occurs during the session, record that the reply path was verified by the pure tests + code review and the action's *appearance* (if any clarify notification is available), and note the live send was not exercised. Record the outcome in the PR description.

---

## Notes for the executor

- `ChatRepository.submit(sessionId, text)` is the existing headless `prompt.submit` call; `respondApproval(sessionId, choice)` is the existing `approval.respond` call. Both are already injected into the receiver as `chat`. Do not add new RPCs.
- The reply and button actions for the same session never collide on request code because their `a.action` strings differ (`reply` vs `allow_once`/`deny`/`allow_session`).
- Do NOT add "Always" to the notification (would be a 4th action, past Android's 3-action display budget) or a reply action to `message.complete` — both are explicit anti-scope.
