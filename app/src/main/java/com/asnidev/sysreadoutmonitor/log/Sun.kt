package com.asnidev.sysreadoutmonitor.log

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/** Sunrise and sunset from the standard sunrise equation; accurate to about a minute. */
object Sun {

    sealed interface Day {
        data class Normal(val rise: Instant, val set: Instant) : Day
        data object AlwaysUp : Day
        data object AlwaysDown : Day
    }

    fun day(latitude: Double, longitude: Double, date: LocalDate = LocalDate.now(ZoneId.systemDefault())): Day {
        // Days since J2000 (2000-01-01 12:00 UTC) at noon of [date].
        val n = date.toEpochDay() + 2_440_588.0 - 2_451_545.0 + 0.0008
        val meanNoon = n - longitude / 360.0
        val m = rad((357.5291 + 0.98560028 * meanNoon) % 360.0)
        val center = 1.9148 * sin(m) + 0.02 * sin(2 * m) + 0.0003 * sin(3 * m)
        val lambda = rad((Math.toDegrees(m) + center + 180.0 + 102.9372) % 360.0)
        val transit = 2_451_545.0 + meanNoon + 0.0053 * sin(m) - 0.0069 * sin(2 * lambda)
        val declination = asin(sin(lambda) * sin(rad(23.4397)))
        val phi = rad(latitude)
        val cosHour = (sin(rad(-0.833)) - sin(phi) * sin(declination)) / (cos(phi) * cos(declination))
        return when {
            cosHour < -1 -> Day.AlwaysUp
            cosHour > 1 -> Day.AlwaysDown
            else -> {
                val hour = Math.toDegrees(acos(cosHour)) / 360.0
                Day.Normal(julian(transit - hour), julian(transit + hour))
            }
        }
    }

    private fun rad(deg: Double) = Math.toRadians(deg)

    private fun julian(jd: Double): Instant = Instant.ofEpochMilli(((jd - 2_440_587.5) * 86_400_000.0).toLong())
}
