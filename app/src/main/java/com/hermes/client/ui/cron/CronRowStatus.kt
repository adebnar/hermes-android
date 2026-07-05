package com.hermes.client.ui.cron

import com.hermes.client.data.network.CronJobDto
import com.hermes.client.ui.activity.CronAlertReason
import com.hermes.client.ui.activity.needsAttention

enum class CronRowStatus { FAILED, OVERDUE, OK, PAUSED }

/** Pure: a cron job's at-a-glance status for the list. FAILED (last run errored) takes priority,
 *  then OVERDUE, then PAUSED (disabled/paused), else OK. Reuses [needsAttention]. */
fun cronRowStatus(job: CronJobDto, nowMs: Long): CronRowStatus {
    val alert = needsAttention(listOf(job), nowMs).firstOrNull()
    return when {
        alert?.reason == CronAlertReason.FAILED -> CronRowStatus.FAILED
        alert?.reason == CronAlertReason.OVERDUE -> CronRowStatus.OVERDUE
        !job.enabled || job.isPaused -> CronRowStatus.PAUSED
        else -> CronRowStatus.OK
    }
}
