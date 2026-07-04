package com.hermes.client.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hermes.client.data.diagnostics.DebugLog
import com.hermes.client.data.repository.ChatRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

/** Handles the Approve/Deny actions on an approval notification by sending the approval RPC. */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {
    @Inject lateinit var chat: ChatRepository
    @Inject lateinit var notifier: HermesNotifier

    override fun onReceive(context: Context, intent: Intent) {
        val sid = intent.getStringExtra("session_id") ?: return
        val notifId = intent.getIntExtra("notif_id", -1)
        val approve = intent.action == Notif.ACTION_APPROVE
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching { withTimeout(8_000) { chat.respondApproval(sid, approve) } }
                    .onSuccess {
                        // Only clear the notification once the RPC actually succeeded — on
                        // failure, leave it so the action isn't silently lost.
                        if (notifId != -1) notifier.cancel(notifId)
                    }
                    .onFailure { e ->
                        DebugLog.log("notif", "approval response failed session=$sid approve=$approve: ${e.message}")
                    }
            } finally {
                pending.finish()
            }
        }
    }
}
