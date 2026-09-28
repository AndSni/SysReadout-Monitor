package com.asnidev.sysreadoutmonitor.page

import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/** Pure helpers for the software rows of the sys page. */
object Software {

    /** Android's marketing version for an API level ("12" for 31), or null when unknown. */
    fun androidName(api: Int): String? = mapOf(
        26 to "8.0", 27 to "8.1", 28 to "9", 29 to "10", 30 to "11", 31 to "12", 32 to "12L",
        33 to "13", 34 to "14", 35 to "15", 36 to "16", 37 to "17",
    )[api]

    /** Days since a security patch date ("2025-07-01"), or null if it isn't one. */
    fun patchAgeDays(patch: String?, today: LocalDate): Long? = try {
        patch?.let { ChronoUnit.DAYS.between(LocalDate.parse(it.trim()), today) }
    } catch (e: DateTimeParseException) {
        null
    }

    /** "3 months old", "1 year 3 months old", "this month". */
    fun age(days: Long): String {
        val months = days / 30
        return when {
            months < 1 -> "this month"
            months < 12 -> "$months month${if (months == 1L) "" else "s"} old"
            else -> {
                val y = months / 12
                val m = months % 12
                "$y year${if (y == 1L) "" else "s"}" + (if (m > 0) " $m month${if (m == 1L) "" else "s"}" else "") + " old"
            }
        }
    }

    /**
     * How the kernel was built, from /proc/version: its build number, flags and date
     * ("#1 SMP PREEMPT Tue Jun 17 13:59:20 JST 2025") and the compiler ("clang 11.0.2").
     */
    fun kernelBuild(procVersion: String?): String? {
        val text = procVersion?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val build = Regex("#\\d+.*$").find(text)?.value?.trim()
        val compiler = Regex("(clang|gcc) version ([\\d.]+)").find(text)?.let { "${it.groupValues[1]} ${it.groupValues[2]}" }
        return listOfNotNull(build, compiler).joinToString(" · ").ifEmpty { null }
    }

    /** The Google Play system update's month from its module metadata version ("2025-01-01S+" → "2025-01-01"). */
    fun playUpdate(versionName: String?): String? =
        versionName?.let { Regex("\\d{4}-\\d{2}-\\d{2}").find(it)?.value ?: it.takeIf { v -> v.isNotBlank() } }

    /** Baseband as the radio reports it, once ("a,a" → "a"). */
    fun baseband(raw: String?): String? =
        raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() && it != "unknown" }?.distinct()?.joinToString(", ")?.ifEmpty { null }
}
