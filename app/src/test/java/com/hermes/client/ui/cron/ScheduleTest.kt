package com.hermes.client.ui.cron

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ScheduleTest {
    private val utc = ZoneId.of("UTC")
    // 2026-07-06 10:30:00 UTC (a Monday)
    private val now = Instant.parse("2026-07-06T10:30:00Z").toEpochMilli()
    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test fun toCron_all() {
        assertEquals("5 * * * *", Schedule.Hourly(5).toCron())
        assertEquals("0 9 * * *", Schedule.Daily(9, 0).toCron())
        assertEquals("0 2 * * 1", Schedule.Weekly(setOf(Weekday.MON), 2, 0).toCron())
        assertEquals("0 9 * * 1,3,5", Schedule.Weekly(setOf(Weekday.FRI, Weekday.MON, Weekday.WED), 9, 0).toCron())
        assertEquals("0 3 15 * *", Schedule.Monthly(15, 3, 0).toCron())
        assertEquals("*/15 9-17 * * 1-5", Schedule.Advanced("*/15 9-17 * * 1-5").toCron())
    }

    @Test fun describe_representatives() {
        assertEquals("Every hour", Schedule.Hourly(0).describe())
        assertEquals("Every hour at :05", Schedule.Hourly(5).describe())
        assertEquals("Every day at 09:00", Schedule.Daily(9, 0).describe())
        assertEquals("Mon at 02:00", Schedule.Weekly(setOf(Weekday.MON), 2, 0).describe())
        assertEquals("Mon, Wed, Fri at 09:00", Schedule.Weekly(setOf(Weekday.FRI, Weekday.MON, Weekday.WED), 9, 0).describe())
        assertEquals("Every day at 09:00", Schedule.Weekly(Weekday.values().toSet(), 9, 0).describe())
        assertEquals("Day 15 of each month at 03:00", Schedule.Monthly(15, 3, 0).describe())
        assertEquals("*/15 9-17 * * 1-5", Schedule.Advanced("*/15 9-17 * * 1-5").describe())
    }

    @Test fun nextRun_daily() {
        // now = Mon 10:30. Daily 09:00 already passed today -> tomorrow 09:00.
        assertEquals(at("2026-07-07T09:00:00Z"), Schedule.Daily(9, 0).nextRun(now, utc))
        // Daily 14:00 is later today.
        assertEquals(at("2026-07-06T14:00:00Z"), Schedule.Daily(14, 0).nextRun(now, utc))
    }
    @Test fun nextRun_hourly() {
        // now = 10:30. Hourly :45 -> today 10:45.
        assertEquals(at("2026-07-06T10:45:00Z"), Schedule.Hourly(45).nextRun(now, utc))
        // Hourly :15 already passed this hour -> 11:15.
        assertEquals(at("2026-07-06T11:15:00Z"), Schedule.Hourly(15).nextRun(now, utc))
    }
    @Test fun nextRun_weekly() {
        // now = Mon 10:30. Weekly {WED} at 09:00 -> Wed 2026-07-08 09:00.
        assertEquals(at("2026-07-08T09:00:00Z"), Schedule.Weekly(setOf(Weekday.WED), 9, 0).nextRun(now, utc))
        // Weekly {MON} at 09:00 -> passed today -> next Mon.
        assertEquals(at("2026-07-13T09:00:00Z"), Schedule.Weekly(setOf(Weekday.MON), 9, 0).nextRun(now, utc))
    }
    @Test fun nextRun_monthly() {
        // now = Jul 6. Monthly day 15 09:00 -> Jul 15 this month.
        assertEquals(at("2026-07-15T09:00:00Z"), Schedule.Monthly(15, 9, 0).nextRun(now, utc))
        // Monthly day 3 09:00 -> passed -> Aug 3.
        assertEquals(at("2026-08-03T09:00:00Z"), Schedule.Monthly(3, 9, 0).nextRun(now, utc))
    }
    @Test fun nextRun_advanced_null() {
        assertNull(Schedule.Advanced("*/15 * * * *").nextRun(now, utc))
    }

    @Test fun parseCron_presets_and_fallback() {
        assertEquals(Schedule.Hourly(5), parseCron("5 * * * *"))
        assertEquals(Schedule.Daily(9, 0), parseCron("0 9 * * *"))
        assertEquals(Schedule.Weekly(setOf(Weekday.MON), 2, 0), parseCron("0 2 * * 1"))
        assertEquals(Schedule.Weekly(setOf(Weekday.MON, Weekday.WED, Weekday.FRI), 9, 0), parseCron("0 9 * * 1,3,5"))
        assertEquals(Schedule.Monthly(15, 3, 0), parseCron("0 3 15 * *"))
        assertEquals(Schedule.Advanced("*/15 9-17 * * 1-5"), parseCron("*/15 9-17 * * 1-5"))
        assertEquals(Schedule.Advanced("garbage"), parseCron("garbage"))
    }
    @Test fun parseCron_roundtrips_presets() {
        for (x in listOf("5 * * * *", "0 9 * * *", "0 2 * * 1", "0 9 * * 1,3,5", "0 3 15 * *")) {
            assertEquals(x, parseCron(x).toCron())
        }
    }
    @Test fun isValidCron_cases() {
        assertTrue(isValidCron("0 9 * * *"))
        assertTrue(isValidCron("*/15 9-17 * * 1-5"))
        assertFalse(isValidCron("0 9 * *"))      // 4 fields
        assertFalse(isValidCron("nope"))
        assertFalse(isValidCron("0 9 * * * *"))  // 6 fields
    }
}
