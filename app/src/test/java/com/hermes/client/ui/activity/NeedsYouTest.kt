package com.hermes.client.ui.activity

import com.hermes.client.data.network.CronJobDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOW = 1_700_000_000_000L
private fun iso(ms: Long) = java.time.Instant.ofEpochMilli(ms).atOffset(java.time.ZoneOffset.UTC).toString()
private fun job(
    id: String = "j1", name: String? = null, enabled: Boolean = true, state: String? = null,
    pausedAt: String? = null, nextRunAt: String? = null, lastStatus: String? = null,
) = CronJobDto(
    id = id, name = name, enabled = enabled, state = state, pausedAt = pausedAt,
    nextRunAt = nextRunAt, lastStatus = lastStatus,
)

class NeedsYouTest {
    @Test fun failed_status_makes_a_FAILED_alert() {
        assertEquals(CronAlertReason.FAILED, needsAttention(listOf(job(lastStatus = "error")), NOW).single().reason)
        assertEquals(CronAlertReason.FAILED, needsAttention(listOf(job(lastStatus = "failed")), NOW).single().reason)
    }

    @Test fun healthy_success_has_no_alert() {
        assertTrue(needsAttention(listOf(job(lastStatus = "success", nextRunAt = iso(NOW + 3_600_000))), NOW).isEmpty())
    }

    @Test fun enabled_past_due_beyond_grace_is_OVERDUE() {
        assertEquals(CronAlertReason.OVERDUE, needsAttention(listOf(job(nextRunAt = iso(NOW - 10 * 60_000))), NOW).single().reason)
    }

    @Test fun future_next_run_is_not_overdue() {
        assertTrue(needsAttention(listOf(job(nextRunAt = iso(NOW + 60 * 60_000))), NOW).isEmpty())
    }

    @Test fun within_grace_is_not_overdue() {
        assertTrue(needsAttention(listOf(job(nextRunAt = iso(NOW - 2 * 60_000))), NOW).isEmpty())
    }

    @Test fun paused_or_disabled_not_failed_has_no_alert() {
        assertTrue(needsAttention(listOf(job(enabled = false, nextRunAt = iso(NOW - 10 * 60_000))), NOW).isEmpty())
        assertTrue(needsAttention(listOf(job(state = "paused", nextRunAt = iso(NOW - 10 * 60_000))), NOW).isEmpty())
    }

    @Test fun paused_but_failed_still_shows_FAILED() {
        assertEquals(CronAlertReason.FAILED, needsAttention(listOf(job(state = "paused", lastStatus = "error")), NOW).single().reason)
    }

    @Test fun failed_and_past_due_is_a_single_FAILED() {
        val alerts = needsAttention(listOf(job(lastStatus = "error", nextRunAt = iso(NOW - 10 * 60_000))), NOW)
        assertEquals(1, alerts.size)
        assertEquals(CronAlertReason.FAILED, alerts.single().reason)
    }

    @Test fun name_falls_back_to_id_and_route_is_cron_detail() {
        val a = needsAttention(listOf(job(id = "abc", name = "  ", lastStatus = "error")), NOW).single()
        assertEquals("abc", a.name)
        assertEquals("cron_detail/abc", a.route)
    }
}
