package com.hermes.client.ui.chat

/** A picked-but-unsent attachment, held locally until Send. Identity is [id] (bytes excluded). */
class PendingAttachment(val id: String, val bytes: ByteArray, val mimeType: String) {
    override fun equals(other: Any?) = other is PendingAttachment && other.id == id
    override fun hashCode() = id.hashCode()
}

const val ATTACH_CAP = 6

/** Add [a] unless already at [cap]; returns the list unchanged when full. */
fun List<PendingAttachment>.plusCapped(a: PendingAttachment, cap: Int = ATTACH_CAP): List<PendingAttachment> =
    if (size >= cap) this else this + a

/** True when a message may be sent: connected, has text or an attachment, and not mid-generation. */
fun canSend(connected: Boolean, hasText: Boolean, hasAttachments: Boolean, isGenerating: Boolean): Boolean =
    connected && (hasText || hasAttachments) && !isGenerating
