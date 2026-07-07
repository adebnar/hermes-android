package com.hermes.client.ui.chat

/** Append dictated [spoken] to the current [current] draft (single-space-joined; blank-safe). */
fun appendDictation(current: String, spoken: String): String {
    val s = spoken.trim()
    if (s.isEmpty()) return current
    return if (current.isBlank()) s else current.trimEnd() + " " + s
}
