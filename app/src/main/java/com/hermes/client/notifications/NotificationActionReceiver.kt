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
 * Reply → `clarify.respond` (answers the pending clarify request, doesn't start a new turn). Runs a
 * single RPC on a background scope; only clears the notification once the RPC succeeds, so a failed
 * action isn't silently lost.
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
                            ReceiverAction.Reply -> {
                                val requestId = intent.getStringExtra("request_id").orEmpty()
                                chat.respondClarify(sid, requestId, replyText!!)
                            }
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
