package com.hermes.client.share

import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot, in-process handoff of shared text from the share entry point to the chat that opens for
 * it. Not persisted — it only needs to survive a single navigation. take() is keyed by sessionId so
 * a normal chat open never consumes a share meant for a different (freshly-created) session.
 */
@Singleton
class PendingShareStore @Inject constructor() {
    private var pending: Pair<String, String>? = null

    @Synchronized
    fun put(sessionId: String, text: String) {
        pending = sessionId to text
    }

    @Synchronized
    fun take(sessionId: String): String? {
        val p = pending ?: return null
        if (p.first != sessionId) return null
        pending = null
        return p.second
    }
}
