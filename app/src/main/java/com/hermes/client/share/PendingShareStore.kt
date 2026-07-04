package com.hermes.client.share

import javax.inject.Inject
import javax.inject.Singleton

/** A pending share (text and/or an image) handed to the chat that opens for it. */
data class PendingShare(
    val text: String? = null,
    val imageBase64: String? = null,
    val imageMime: String? = null,
)

/**
 * One-shot, in-process handoff from the share entry point to the chat that opens for it. Not
 * persisted — survives a single navigation. take() is keyed by sessionId so a normal chat open
 * never consumes a share meant for a different (freshly-created) session.
 */
@Singleton
class PendingShareStore @Inject constructor() {
    private var pending: Pair<String, PendingShare>? = null

    @Synchronized
    fun put(sessionId: String, share: PendingShare) {
        pending = sessionId to share
    }

    @Synchronized
    fun take(sessionId: String): PendingShare? {
        val p = pending ?: return null
        if (p.first != sessionId) return null
        pending = null
        return p.second
    }
}
