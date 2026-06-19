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
