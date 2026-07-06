package com.hermes.client.ui.util

import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

private val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
private val isoOut = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.getDefault())

/** Format an epoch-seconds timestamp as "Jun 19, 09:00", or "—" when null. */
fun formatEpoch(seconds: Double?): String =
    seconds?.let { fmt.format(Date((it * 1000).toLong())) } ?: "—"

/** Format an ISO-8601 timestamp ("2026-06-20T09:00:00-05:00") as "Jun 20, 09:00", or "—". */
fun formatIso(iso: String?): String =
    iso?.takeIf { it.isNotBlank() }?.let {
        runCatching { OffsetDateTime.parse(it).format(isoOut) }.getOrDefault(it)
    } ?: "—"

/** Parse an ISO-8601 timestamp to epoch millis, or null if absent/unparseable. */
fun isoToEpochMs(iso: String?): Long? =
    iso?.takeIf { it.isNotBlank() }?.let {
        runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
    }

/** Convert epoch-seconds (as the gateway reports last_active) to epoch millis. */
fun secondsToEpochMs(seconds: Double?): Long? = seconds?.let { (it * 1000).toLong() }

/** Check if two epoch-ms timestamps fall on the same calendar day in a given time zone. */
fun isSameDay(aMs: Long, bMs: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Boolean =
    java.time.Instant.ofEpochMilli(aMs).atZone(zone).toLocalDate() ==
        java.time.Instant.ofEpochMilli(bMs).atZone(zone).toLocalDate()

/**
 * Compact relative time for the activity feed: "just now", "5m ago", "3h ago", "2d ago" for the
 * past; "in 5m", "in 2h" for the future. Pure — [nowMs] is passed in so it is unit-testable.
 */
fun relativeTime(epochMs: Long?, nowMs: Long): String {
    if (epochMs == null) return "—"
    val diff = epochMs - nowMs
    val mins = kotlin.math.abs(diff) / 60_000
    val label = when {
        mins < 1 -> "just now"
        mins < 60 -> "${mins}m"
        mins < 60 * 24 -> "${mins / 60}h"
        else -> "${mins / (60 * 24)}d"
    }
    return when {
        mins < 1 -> "just now"
        diff < 0 -> "$label ago"
        else -> "in $label"
    }
}
