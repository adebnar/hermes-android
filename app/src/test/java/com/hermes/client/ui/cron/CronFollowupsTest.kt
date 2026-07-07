package com.hermes.client.ui.cron

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CronFollowupsTest {
    @Test fun displayName_uses_name_when_present() {
        assertEquals("Nightly summary", cronDisplayName("  Nightly summary  ", "some prompt", "c1"))
    }
    @Test fun displayName_falls_back_to_prompt_snippet() {
        assertEquals("Back up the DB", cronDisplayName("", "Back up the DB", "c1"))
        assertEquals("Back up the DB", cronDisplayName(null, "Back up the DB", "c1"))
    }
    @Test fun displayName_flattens_and_truncates_long_prompt() {
        val long = "Summarize yesterday's deploys\nand open incidents across all of my projects"
        val out = cronDisplayName(null, long, "c1", maxLen = 20)
        assertTrue(out.endsWith("…"))
        assertTrue(out.length <= 21)
        assertTrue(!out.contains("\n"))
    }
    @Test fun displayName_falls_back_to_id() {
        assertEquals("c9", cronDisplayName(null, null, "c9"))
        assertEquals("c9", cronDisplayName("  ", "  ", "c9"))
    }
    @Test fun cronTemplate_lookup() {
        assertEquals(Schedule.Daily(9, 0), cronTemplate("new_daily")?.schedule)
        assertEquals(Schedule.Weekly(setOf(Weekday.MON), 2, 0), cronTemplate("new_weekly")?.schedule)
        assertEquals(Schedule.Hourly(0), cronTemplate("new_hourly")?.schedule)
        assertNull(cronTemplate("new"))
        assertNull(cronTemplate("nope"))
    }
    @Test fun all_templates_emit_valid_cron() {
        CRON_TEMPLATES.forEach { assertTrue(it.id, isValidCron(it.schedule.toCron())) }
    }
}
