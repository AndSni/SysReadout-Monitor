package com.asnidev.sysreadoutmonitor.log

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class SunTest {

    private fun near(expected: String, actual: Instant, minutes: Long = 3) =
        assertTrue("expected ~$expected, got $actual", Duration.between(Instant.parse(expected), actual).abs().toMinutes() <= minutes)

    @Test
    fun londonMidsummer() {
        // Published times: sunrise 04:43 BST, sunset 21:21 BST.
        val day = Sun.day(51.5074, -0.1278, LocalDate.of(2024, 6, 21)) as Sun.Day.Normal
        near("2024-06-21T03:43:00Z", day.rise)
        near("2024-06-21T20:21:00Z", day.set)
    }

    @Test
    fun equinoxDayIsAboutTwelveHours() {
        // Near the September equinox every latitude gets ~12h of daylight (a bit more from refraction).
        val day = Sun.day(56.9496, 24.1052, LocalDate.of(2026, 9, 23)) as Sun.Day.Normal
        val length = Duration.between(day.rise, day.set).toMinutes()
        assertTrue("day length $length min", length in 720..750)
    }

    @Test
    fun polarDayAndNight() {
        assertTrue(Sun.day(78.22, 15.65, LocalDate.of(2024, 6, 21)) is Sun.Day.AlwaysUp) // Svalbard
        assertTrue(Sun.day(78.22, 15.65, LocalDate.of(2024, 12, 21)) is Sun.Day.AlwaysDown)
    }
}
