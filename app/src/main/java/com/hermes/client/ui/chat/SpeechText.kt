package com.hermes.client.ui.chat

/**
 * Strip common markdown so TextToSpeech reads content, not syntax. Best-effort and intentionally
 * simple — fenced code is dropped, inline code/emphasis/heading markers removed, links reduced to
 * their text. Not a full markdown parser.
 */
fun speechText(raw: String): String {
    if (raw.isBlank()) return ""
    var s = raw
    // Fenced code blocks: drop entirely (```lang ... ```).
    s = Regex("```[\\s\\S]*?```").replace(s, " ")
    // Links [text](url) -> text.
    s = Regex("\\[([^\\]]+)]\\([^)]*\\)").replace(s) { it.groupValues[1] }
    // Inline code `code` -> code.
    s = Regex("`([^`]*)`").replace(s) { it.groupValues[1] }
    // Heading markers at line start.
    s = Regex("(?m)^\\s{0,3}#{1,6}\\s*").replace(s, "")
    // Emphasis markers ** * __ _ (leave apostrophes/words intact).
    s = s.replace("**", "").replace("__", "")
    s = Regex("(?<![A-Za-z0-9])[*_](?=\\S)").replace(s, "")
    s = Regex("(?<=\\S)[*_](?![A-Za-z0-9])").replace(s, "")
    // Collapse whitespace runs and trim.
    s = Regex("[ \\t]+").replace(s, " ")
    s = Regex("\\n{2,}").replace(s, "\n").trim()
    return s
}
