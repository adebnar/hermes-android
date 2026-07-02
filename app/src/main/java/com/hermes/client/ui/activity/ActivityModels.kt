package com.hermes.client.ui.activity

import com.hermes.client.data.network.CronJobDto
import com.hermes.client.domain.Session
import com.hermes.client.ui.util.isoToEpochMs

// Mission Control (Phase 2): a unified, time-grouped activity feed merging conversations and cron
// for the active profile. Sessions and cron jobs are flattened into a common [ActivityItem], then
// bucketed by time. The bucketing is a pure function so it unit-tests without a clock or network.

enum class ActivityKind { CONVERSATION, CRON }

data class ActivityItem(
    val id: String,
    val kind: ActivityKind,
    val title: String,
    val subtitle: String?,
    val timestampMs: Long?,
    // Future scheduled work (a cron next-run) vs something that already happened.
    val upcoming: Boolean,
    // Cron last_status ("success"/"error"/…) for recent runs; null for conversations.
    val status: String?,
    // Navigation route to open the item ("chat/<id>" or "cron_detail/<id>").
    val route: String,
)

data class ActivitySection(val title: String, val items: List<ActivityItem>)

/** Anything more recent than this counts as "Live now" rather than "Recent". */
const val LIVE_WINDOW_MS = 15 * 60_000L
private const val RECENT_CAP = 40

fun sessionsToActivity(sessions: List<Session>): List<ActivityItem> = sessions.map { s ->
    // A cron-produced session IS the run's output — tapping it opens the actual messages, not the
    // job config. Mark it CRON so it reads as automated, but route it to the chat like any session.
    val isCron = s.source == "cron"
    ActivityItem(
        id = "sess-${s.id}",
        kind = if (isCron) ActivityKind.CRON else ActivityKind.CONVERSATION,
        title = s.title,
        subtitle = if (isCron) "Cron run · ${s.messageCount} msg"
        else listOfNotNull(s.model, "${s.messageCount} msg").joinToString(" · "),
        timestampMs = s.lastActive,
        upcoming = false,
        status = null,
        route = "chat/${s.id}",
    )
}

/**
 * Cron jobs contribute only **upcoming** next-runs (there is no session yet, so these route to the
 * job detail). Past runs surface as real cron sessions via [sessionsToActivity] instead, so tapping
 * a completed run opens its output rather than the job's config.
 */
fun cronsToActivity(crons: List<CronJobDto>): List<ActivityItem> = crons.mapNotNull { job ->
    if (!job.enabled || job.isPaused) return@mapNotNull null
    val next = isoToEpochMs(job.nextRunAt) ?: return@mapNotNull null
    ActivityItem(
        id = "cron-next-${job.id}",
        kind = ActivityKind.CRON,
        title = job.name?.ifBlank { null } ?: job.id,
        subtitle = "Scheduled · ${job.scheduleText}",
        timestampMs = next,
        upcoming = true,
        status = null,
        route = "cron_detail/${job.id}",
    )
}

/**
 * Bucket items into Live now / Upcoming / Recent sections (in that display order). Empty sections
 * are omitted. Items without a timestamp are dropped from the time-ordered feed.
 */
fun groupActivity(items: List<ActivityItem>, nowMs: Long): List<ActivitySection> {
    val upcoming = items
        .filter { it.upcoming && it.timestampMs != null }
        .sortedBy { it.timestampMs }
    val past = items.filter { !it.upcoming && it.timestampMs != null }
    val live = past
        .filter { (nowMs - it.timestampMs!!) in 0..LIVE_WINDOW_MS }
        .sortedByDescending { it.timestampMs }
    val liveIds = live.mapTo(HashSet()) { it.id }
    val recent = past
        .filterNot { it.id in liveIds }
        .sortedByDescending { it.timestampMs }
        .take(RECENT_CAP)

    return listOfNotNull(
        live.takeIf { it.isNotEmpty() }?.let { ActivitySection("Live now", it) },
        upcoming.takeIf { it.isNotEmpty() }?.let { ActivitySection("Upcoming", it) },
        recent.takeIf { it.isNotEmpty() }?.let { ActivitySection("Recent", it) },
    )
}
