package com.hermes.client.ui.cron

import com.hermes.client.data.network.CronJobDto
import org.junit.Assert.assertEquals
import org.junit.Test

private const val NOW = 1_700_000_000_000L
private fun iso(ms: Long) = java.time.Instant.ofEpochMilli(ms).atOffset(java.time.ZoneOffset.UTC).toString()
private fun job(
    id: String = "j1", enabled: Boolean = true, state: String? = null,
    pausedAt: String? = null, nextRunAt: String? = null, lastStatus: String? = null,
) = CronJobDto(
    id = id, enabled = enabled, state = state, pausedAt = pausedAt,
    nextRunAt = nextRunAt, lastStatus = lastStatus,
)

class CronRowStatusTest {
    @Test fun failed_last_run_is_FAILED() {
        assertEquals(CronRowStatus.FAILED, cronRowStatus(job(lastStatus = "error"), NOW))
    }
    @Test fun overdue_is_OVERDUE() {
        assertEquals(CronRowStatus.OVERDUE, cronRowStatus(job(nextRunAt = iso(NOW - 10 * 60_000)), NOW))
    }
    @Test fun paused_or_disabled_not_failed_is_PAUSED() {
        assertEquals(CronRowStatus.PAUSED, cronRowStatus(job(state = "paused"), NOW))
        assertEquals(CronRowStatus.PAUSED, cronRowStatus(job(enabled = false), NOW))
    }
    @Test fun healthy_future_is_OK() {
        assertEquals(CronRowStatus.OK, cronRowStatus(job(lastStatus = "success", nextRunAt = iso(NOW + 3_600_000)), NOW))
    }
    @Test fun failed_takes_priority_over_paused() {
        assertEquals(CronRowStatus.FAILED, cronRowStatus(job(state = "paused", lastStatus = "error"), NOW))
    }
}
