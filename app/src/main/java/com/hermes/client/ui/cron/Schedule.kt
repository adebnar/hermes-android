package com.hermes.client.ui.cron

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

enum class Weekday(val cron: Int, val short: String) {
    SUN(0, "Sun"), MON(1, "Mon"), TUE(2, "Tue"), WED(3, "Wed"), THU(4, "Thu"), FRI(5, "Fri"), SAT(6, "Sat");

    companion object {
        fun fromCron(v: Int): Weekday? = values().firstOrNull { it.cron == v % 7 }
    }
}

sealed interface Schedule {
    data class Hourly(val minute: Int) : Schedule
    data class Daily(val hour: Int, val minute: Int) : Schedule
    data class Weekly(val days: Set<Weekday>, val hour: Int, val minute: Int) : Schedule
    data class Monthly(val dayOfMonth: Int, val hour: Int, val minute: Int) : Schedule
    data class Advanced(val expr: String) : Schedule
}

fun Schedule.toCron(): String = when (this) {
    is Schedule.Hourly -> "$minute * * * *"
    is Schedule.Daily -> "$minute $hour * * *"
    is Schedule.Weekly -> "$minute $hour * * ${days.sortedBy { it.cron }.joinToString(",") { it.cron.toString() }}"
    is Schedule.Monthly -> "$minute $hour $dayOfMonth * *"
    is Schedule.Advanced -> expr
}

private fun hm(h: Int, m: Int) = "%02d:%02d".format(h, m)

fun Schedule.describe(): String = when (this) {
    is Schedule.Hourly -> if (minute == 0) "Every hour" else "Every hour at :%02d".format(minute)
    is Schedule.Daily -> "Every day at ${hm(hour, minute)}"
    is Schedule.Weekly ->
        if (days.size == 7) "Every day at ${hm(hour, minute)}"
        else "${days.sortedBy { it.cron }.joinToString(", ") { it.short }} at ${hm(hour, minute)}"
    is Schedule.Monthly -> "Day $dayOfMonth of each month at ${hm(hour, minute)}"
    is Schedule.Advanced -> expr
}

fun Schedule.nextRun(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
    val now = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDateTime()
    fun ms(dt: LocalDateTime) = dt.atZone(zone).toInstant().toEpochMilli()
    return when (this) {
        is Schedule.Hourly -> {
            var c = now.withMinute(minute).withSecond(0).withNano(0)
            if (!c.isAfter(now)) c = c.plusHours(1)
            ms(c)
        }
        is Schedule.Daily -> {
            var c = now.toLocalDate().atTime(hour, minute)
            if (!c.isAfter(now)) c = c.plusDays(1)
            ms(c)
        }
        is Schedule.Weekly -> {
            if (days.isEmpty()) return null
            val wanted = days.map { it.cron }.toSet() // 0=Sun..6=Sat
            var c = now.toLocalDate().atTime(hour, minute)
            repeat(8) {
                val dow = c.dayOfWeek.value % 7 // java: Mon=1..Sun=7 -> 0=Sun..6=Sat
                if (dow in wanted && c.isAfter(now)) return ms(c)
                c = c.plusDays(1).toLocalDate().atTime(hour, minute)
            }
            null
        }
        is Schedule.Monthly -> {
            var date = now.toLocalDate()
            repeat(13) {
                if (dayOfMonth <= date.lengthOfMonth()) {
                    val c = date.withDayOfMonth(dayOfMonth).atTime(hour, minute)
                    if (c.isAfter(now)) return ms(c)
                }
                date = date.plusMonths(1).withDayOfMonth(1)
            }
            null
        }
        is Schedule.Advanced -> null
    }
}

private fun String.toIntOrStar(): Int? = toIntOrNull()

fun parseCron(expr: String): Schedule {
    val f = expr.trim().split(Regex("\\s+"))
    if (f.size != 5) return Schedule.Advanced(expr.trim())
    val minS = f[0]
    val hourS = f[1]
    val domS = f[2]
    val monS = f[3]
    val dowS = f[4]
    val min = minS.toIntOrStar()
    val hour = hourS.toIntOrStar()
    val dom = domS.toIntOrStar()
    val star = { s: String -> s == "*" }
    return when {
        min != null && star(hourS) && star(domS) && star(monS) && star(dowS) -> Schedule.Hourly(min)
        min != null && hour != null && star(domS) && star(monS) && star(dowS) -> Schedule.Daily(hour, min)
        min != null && hour != null && star(domS) && star(monS) && dowIsList(dowS) ->
            Schedule.Weekly(dowS.split(",").mapNotNull { Weekday.fromCron(it.toInt()) }.toSet(), hour, min)
        min != null && hour != null && dom != null && star(monS) && star(dowS) -> Schedule.Monthly(dom, hour, min)
        else -> Schedule.Advanced(expr.trim())
    }
}

private fun dowIsList(s: String): Boolean =
    s != "*" && s.split(",").all { it.toIntOrNull()?.let { n -> n in 0..7 } == true }

fun isValidCron(expr: String): Boolean {
    val f = expr.trim().split(Regex("\\s+"))
    if (f.size != 5) return false
    // A field is "*" (optionally with a /step) or a comma-separated list of
    // numbers/ranges, each optionally with a /step, e.g. "9-17/2,30".
    val part = "(\\*|\\d+(-\\d+)?)(/\\d+)?"
    val token = Regex("^$part(,$part)*$")
    return f.all { token.matches(it) }
}
