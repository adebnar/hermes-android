package com.hermes.client.data.diagnostics

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide diagnostic log, toggled by the user in Settings → Diagnostics.
 *
 * It is a plain object (not DI) so it can be called from non-injected code such as the
 * WebSocket listener. When [enabled] is false, [log] is a cheap no-op and nothing is
 * retained, so there is zero overhead in normal use. When enabled, entries are kept in a
 * bounded in-memory ring buffer (cleared on app kill), exposed as [entries] for the live
 * in-app view and mirrored to logcat under [TAG].
 *
 * A registered session token is masked in every message so the log is safe to share.
 */
object DebugLog {
    const val MAX_ENTRIES = 500
    private const val TAG = "HermesDebug"

    data class LogEntry(val timeMillis: Long, val category: String, val message: String)

    @Volatile private var enabled = false
    @Volatile private var tokenToRedact: String? = null

    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>(MAX_ENTRIES)

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries

    fun isEnabled(): Boolean = enabled

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    /** Register the session token so it is never written to the log in plain text. */
    fun setTokenToRedact(token: String?) {
        tokenToRedact = token?.takeIf { it.isNotBlank() }
    }

    fun log(category: String, message: String) {
        if (!enabled) return
        val safe = redact(message)
        val entry = LogEntry(System.currentTimeMillis(), category, safe)
        synchronized(lock) {
            if (buffer.size >= MAX_ENTRIES) buffer.removeFirst()
            buffer.addLast(entry)
            _entries.value = buffer.toList()
        }
        Log.d(TAG, "[$category] $safe")
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _entries.value = emptyList()
        }
    }

    /** Plain-text dump for the Share sheet, oldest first. */
    fun export(): String {
        val snapshot = synchronized(lock) { buffer.toList() }
        if (snapshot.isEmpty()) return "(no diagnostic entries)"
        return buildString {
            append("Hermes diagnostic log — ${snapshot.size} entries\n")
            snapshot.forEach { e -> append("${e.timeMillis} [${e.category}] ${e.message}\n") }
        }
    }

    private fun redact(message: String): String {
        val token = tokenToRedact ?: return message
        return message.replace(token, "***")
    }
}
