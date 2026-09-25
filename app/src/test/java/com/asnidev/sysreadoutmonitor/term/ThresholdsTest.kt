package com.asnidev.sysreadoutmonitor.term

import org.junit.Assert.assertEquals
import org.junit.Test

class ThresholdsTest {

    @Test
    fun cpuAndMemoryWarnAt70CriticalAt90() {
        assertEquals(Tone.GOOD, Thresholds.load(0.0))
        assertEquals(Tone.GOOD, Thresholds.load(69.9))
        assertEquals(Tone.WARN, Thresholds.load(70.0))
        assertEquals(Tone.WARN, Thresholds.load(89.9))
        assertEquals(Tone.CRIT, Thresholds.load(90.0))
        assertEquals(Tone.CRIT, Thresholds.load(100.0))
    }

    @Test
    fun batteryWarnBelow30CriticalBelow15() {
        assertEquals(Tone.GOOD, Thresholds.battery(100))
        assertEquals(Tone.GOOD, Thresholds.battery(30))
        assertEquals(Tone.WARN, Thresholds.battery(29))
        assertEquals(Tone.WARN, Thresholds.battery(15))
        assertEquals(Tone.CRIT, Thresholds.battery(14))
        assertEquals(Tone.CRIT, Thresholds.battery(0))
    }

    @Test
    fun temperatureWarnAbove45CriticalAbove55() {
        assertEquals(Tone.GOOD, Thresholds.temperature(20.0))
        assertEquals(Tone.GOOD, Thresholds.temperature(45.0))
        assertEquals(Tone.WARN, Thresholds.temperature(45.1))
        assertEquals(Tone.WARN, Thresholds.temperature(55.0))
        assertEquals(Tone.CRIT, Thresholds.temperature(55.1))
    }

    @Test
    fun storageWarnAbove85CriticalAbove95() {
        assertEquals(Tone.GOOD, Thresholds.storage(85.0))
        assertEquals(Tone.WARN, Thresholds.storage(85.5))
        assertEquals(Tone.WARN, Thresholds.storage(95.0))
        assertEquals(Tone.CRIT, Thresholds.storage(95.5))
    }

    @Test
    fun thermalStatus() {
        assertEquals(Tone.GOOD, Thresholds.thermal("none"))
        assertEquals(Tone.WARN, Thresholds.thermal("light"))
        assertEquals(Tone.WARN, Thresholds.thermal("moderate"))
        // ProbeReader writes the serious ones in capitals.
        listOf("SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN").forEach { assertEquals(it, Tone.CRIT, Thresholds.thermal(it)) }
        assertEquals(Tone.FG, Thresholds.thermal("n/a"))
    }

    @Test
    fun signalWords() {
        assertEquals(Tone.GOOD, Thresholds.signal("very strong"))
        assertEquals(Tone.GOOD, Thresholds.signal("strong"))
        assertEquals(Tone.WARN, Thresholds.signal("medium"))
        assertEquals(Tone.CRIT, Thresholds.signal("weak"))
        assertEquals(Tone.CRIT, Thresholds.signal("very weak"))
        assertEquals(Tone.CRIT, Thresholds.signal("no signal"))
    }

    @Test
    fun lowMemoryIsCritical() = assertEquals(Tone.CRIT, Thresholds.lowMemory)

    @Test
    fun flagKeepsOnlyTrouble() {
        assertEquals(Tone.FG, Thresholds.flag(Tone.GOOD))
        assertEquals(Tone.WARN, Thresholds.flag(Tone.WARN))
        assertEquals(Tone.CRIT, Thresholds.flag(Tone.CRIT))
    }
}
