package com.hermes.client.ui.cron

/** A one-tap starting point for a new cron job (a preset schedule + a starter prompt). */
data class CronTemplate(val id: String, val label: String, val schedule: Schedule, val prompt: String)

// Template ids (new_daily, new_weekly, etc.) are a distinct namespace from server job ids (c1, etc.),
// so cronTemplate(id) matching in the edit VM can't collide with a real job.
val CRON_TEMPLATES = listOf(
    CronTemplate(
        "new_daily", "Daily summary · 9:00", Schedule.Daily(9, 0),
        "Summarize what happened across my projects yesterday — deploys, incidents, and anything that needs my attention.",
    ),
    CronTemplate(
        "new_weekly", "Weekly audit · Mon 2:00", Schedule.Weekly(setOf(Weekday.MON), 2, 0),
        "Run a dependency and security audit and list anything that needs attention.",
    ),
    CronTemplate(
        "new_hourly", "Hourly check", Schedule.Hourly(0),
        "Check for anything urgent that needs my attention and summarize it.",
    ),
)

fun cronTemplate(id: String): CronTemplate? = CRON_TEMPLATES.firstOrNull { it.id == id }
