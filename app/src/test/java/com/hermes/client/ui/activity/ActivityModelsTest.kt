package com.hermes.client.ui.activity

import com.hermes.client.data.network.CronJobDto
import com.hermes.client.domain.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityModelsTest {
    private val now = 1_700_000_000_000L
    private val min = 60_000L

    private fun item(id: String, upcoming: Boolean, offsetMs: Long) = ActivityItem(
        id = id, kind = ActivityKind.CONVERSATION, title = id, subtitle = null,
        timestampMs = now + offsetMs, upcoming = upcoming, status = null, route = "r/$id",
    )

    @Test fun groups_into_live_upcoming_recent_in_order() {
        val sections = groupActivity(
            listOf(
                item("live", upcoming = false, offsetMs = -5 * min),   // 5m ago → Live
                item("recent", upcoming = false, offsetMs = -60 * min), // 1h ago → Recent
                item("up", upcoming = true, offsetMs = 30 * min),       // in 30m → Upcoming
            ),
            now,
        )
        assertEquals(listOf("Live now", "Upcoming", "Recent"), sections.map { it.title })
        assertEquals("live", sections[0].items.single().id)
        assertEquals("up", sections[1].items.single().id)
        assertEquals("recent", sections[2].items.single().id)
    }

    @Test fun empty_sections_are_omitted() {
        val sections = groupActivity(listOf(item("r", upcoming = false, offsetMs = -60 * min)), now)
        assertEquals(listOf("Recent"), sections.map { it.title })
    }

    @Test fun upcoming_sorts_ascending_recent_descending() {
        val sections = groupActivity(
            listOf(
                item("up-late", upcoming = true, offsetMs = 120 * min),
                item("up-soon", upcoming = true, offsetMs = 10 * min),
                item("old", upcoming = false, offsetMs = -300 * min),
                item("newer", upcoming = false, offsetMs = -90 * min),
            ),
            now,
        )
        assertEquals(listOf("up-soon", "up-late"), sections.first { it.title == "Upcoming" }.items.map { it.id })
        assertEquals(listOf("newer", "old"), sections.first { it.title == "Recent" }.items.map { it.id })
    }

    @Test fun sessions_map_to_conversation_items() {
        val s = Session(
            id = "s1", title = "Hello", model = "opus", provider = null,
            messageCount = 3, profile = "personal", lastActive = now,
        )
        val items = sessionsToActivity(listOf(s))
        assertEquals("chat/s1", items.single().route)
        assertEquals(ActivityKind.CONVERSATION, items.single().kind)
        assertEquals(now, items.single().timestampMs)
        assertTrue(items.single().subtitle!!.contains("3 msg"))
    }

    @Test fun active_cron_yields_upcoming_and_recent() {
        val job = CronJobDto(
            id = "c1", name = "Nightly", enabled = true,
            nextRunAt = "2026-06-20T09:00:00Z", lastRunAt = "2026-06-19T09:00:00Z", lastStatus = "success",
        )
        val items = cronsToActivity(listOf(job))
        assertEquals(setOf("cron-next-c1", "cron-last-c1"), items.map { it.id }.toSet())
        assertTrue(items.any { it.upcoming })
        assertTrue(items.all { it.route == "cron_detail/c1" })
    }

    @Test fun paused_cron_has_no_upcoming_item() {
        val job = CronJobDto(
            id = "c2", name = "Paused", enabled = true, pausedAt = "2026-01-01T00:00:00Z",
            nextRunAt = "2026-06-20T09:00:00Z", lastRunAt = "2026-06-19T09:00:00Z", lastStatus = "error",
        )
        val items = cronsToActivity(listOf(job))
        assertEquals(listOf("cron-last-c2"), items.map { it.id })
    }
}
