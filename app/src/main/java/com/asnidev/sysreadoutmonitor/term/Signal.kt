package com.asnidev.sysreadoutmonitor.term

import kotlin.math.abs
import kotlin.math.sqrt

/** Radio signal numbers for the scan page. */
object Signal {

    /**
     * Signal quality in % from dBm, the way NetworkManager's `nmcli` shows it:
     * -40 dBm or better is 100 %, -100 dBm or worse is 0 %, linear between.
     */
    fun percent(dbm: Int): Int {
        val below = abs(dbm.coerceIn(-100, -40) + 40) // 0..60
        return 100 - 100 * below / 60
    }

    /** Signal strength in words, from dBm, on the same scale as Android's Wi-Fi levels. */
    fun words(dbm: Int): String = when {
        dbm >= -55 -> "very strong"
        dbm >= -66 -> "strong"
        dbm >= -77 -> "medium"
        dbm >= -88 -> "weak"
        else -> "very weak"
    }

    /** How much a signal wanders: the standard deviation of its readings in dB, null with fewer than 3. */
    fun spread(readings: List<Int>): Double? {
        if (readings.size < 3) return null
        val mean = readings.average()
        return sqrt(readings.sumOf { (it - mean) * (it - mean) } / readings.size)
    }

    /** `||||||    ` in [width] cells for a signal of [percent]. */
    fun bars(percent: Int, width: Int): String {
        val n = ((percent.coerceIn(0, 100) * width + 50) / 100)
        return "|".repeat(n) + " ".repeat(width - n)
    }
}
