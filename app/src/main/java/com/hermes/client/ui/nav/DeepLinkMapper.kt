package com.hermes.client.ui.nav

private val TAB_ROUTES = setOf("sessions", "activity", "you")

/**
 * Map a `hermes://` URI string to an internal nav route, or null if it isn't a recognised link.
 * Parsed with [java.net.URI] (pure JVM, unit-testable). Strict allowlist — a `hermes://` link is
 * untrusted external input (BROWSABLE), so anything unknown returns null and is ignored.
 */
fun deepLinkRouteFor(raw: String): String? {
    if (raw.isBlank()) return null
    val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return null
    if (!"hermes".equals(uri.scheme, ignoreCase = true)) return null
    val host = uri.host ?: return null
    val segs = uri.path.orEmpty().split('/').filter { it.isNotBlank() }
    return when (host) {
        "tab" -> segs.singleOrNull()?.takeIf { it in TAB_ROUTES }
        "chat" -> segs.singleOrNull()?.takeIf { it.isNotBlank() && '/' !in it }?.let { "chat/$it" }
        else -> null
    }
}
