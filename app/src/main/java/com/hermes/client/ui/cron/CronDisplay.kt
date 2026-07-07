package com.hermes.client.ui.cron

/** List/display name for a cron job: its name, else a one-line prompt snippet, else its id. */
fun cronDisplayName(name: String?, prompt: String?, id: String, maxLen: Int = 40): String {
    name?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    prompt?.replace("\n", " ")?.trim()?.takeIf { it.isNotEmpty() }?.let {
        return if (it.length <= maxLen) it else it.take(maxLen).trimEnd() + "…"
    }
    return id
}
