package com.hermes.client.share

import android.content.Intent

/**
 * Pure: the shareable text from an ACTION_SEND share, or null if this isn't a usable text share.
 * A shared link commonly puts the page title in EXTRA_SUBJECT and the URL in EXTRA_TEXT, so the two
 * are combined ("subject\ntext") when they differ. Takes primitives (not an Intent) so it stays
 * pure and JVM-unit-testable.
 */
fun sharedText(action: String?, type: String?, subject: String?, text: String?): String? {
    if (action != Intent.ACTION_SEND) return null
    if (type == null || !type.startsWith("text/")) return null
    val s = subject?.trim().orEmpty()
    val t = text?.trim().orEmpty()
    return when {
        s.isNotEmpty() && t.isNotEmpty() && s != t -> "$s\n$t"
        t.isNotEmpty() -> t
        s.isNotEmpty() -> s
        else -> null
    }
}
