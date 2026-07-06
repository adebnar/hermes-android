package com.hermes.client.ui.activity

import com.hermes.client.ui.util.isSameDay
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class FeedViewTest {
    private val utc = ZoneId.of("UTC")
    private val day = 24 * 60 * 60_000L
    private val t0 = 1_720_000_000_000L // fixed "now"

    private fun item(id: String, ts: Long?, upcoming: Boolean) =
        ActivityItem(id, ActivityKind.CRON, id, null, ts, upcoming, null, "r/$id")
    private fun alert(id: String) = CronAlert(id, id, CronAlertReason.FAILED, "cron_detail/$id")

    @Test fun isSameDay_true_within_day() {
        assertEquals(true, isSameDay(t0, t0 + 60_000, utc))
    }
    @Test fun isSameDay_false_next_day() {
        assertEquals(false, isSameDay(t0, t0 + day, utc))
    }

    private val sections = listOf(
        ActivitySection("Live now", listOf(item("live", t0 - 60_000, false))),
        ActivitySection("Upcoming", listOf(item("up-today", t0 + 60_000, true), item("up-future", t0 + 3 * day, true))),
        ActivitySection("Recent", listOf(item("old", t0 - 3 * day, false))),
    )
    private val needs = listOf(alert("a1"))

    @Test fun feedView_all_is_everything() {
        val v = feedView(sections, needs, t0, FeedFilter.ALL)
        assertEquals(1, v.needsYou.size)
        assertEquals(3, v.sections.size)
        assertEquals(1 + 4, v.count)
    }
    @Test fun feedView_today_keeps_needs_and_today_items() {
        val v = feedView(sections, needs, t0, FeedFilter.TODAY)
        assertEquals(1, v.needsYou.size)
        // today items: live (t0-60s), up-today (t0+60s) -> 2, in their sections; old & up-future dropped
        assertEquals(2, v.sections.sumOf { it.items.size })
        assertEquals(1 + 2, v.count)
    }
    @Test fun feedView_upcoming_only_upcoming_section_no_needs() {
        val v = feedView(sections, needs, t0, FeedFilter.UPCOMING)
        assertEquals(0, v.needsYou.size)
        assertEquals(listOf("Upcoming"), v.sections.map { it.title })
        assertEquals(2, v.count)
    }
    @Test fun feedView_recent_excludes_upcoming_and_needs() {
        val v = feedView(sections, needs, t0, FeedFilter.RECENT)
        assertEquals(0, v.needsYou.size)
        assertEquals(listOf("Live now", "Recent"), v.sections.map { it.title })
        assertEquals(2, v.count)
    }

    @Test fun statusPill_scheduled_live_none() {
        assertEquals("Scheduled", statusPill(item("s", t0 + day, true), t0))
        assertEquals("Live", statusPill(item("l", t0 - 60_000, false), t0))
        assertEquals(null, statusPill(item("o", t0 - 3 * day, false), t0))
    }
}
