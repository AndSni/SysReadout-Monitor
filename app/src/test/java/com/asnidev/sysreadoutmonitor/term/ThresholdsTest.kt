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

class SignalTest {

    @Test
    fun percentLikeNmcli() {
        assertEquals(100, Signal.percent(-30))
        assertEquals(100, Signal.percent(-40))
        assertEquals(50, Signal.percent(-70))
        assertEquals(0, Signal.percent(-100))
        assertEquals(0, Signal.percent(-120))
    }

    @Test
    fun wordsAndTones() {
        assertEquals("very strong", Signal.words(-50))
        assertEquals("strong", Signal.words(-66))
        assertEquals("medium", Signal.words(-70))
        assertEquals("weak", Signal.words(-80))
        assertEquals("very weak", Signal.words(-95))
        assertEquals(Tone.GOOD, Thresholds.dbm(-60))
        assertEquals(Tone.WARN, Thresholds.dbm(-77))
        assertEquals(Tone.CRIT, Thresholds.dbm(-78))
    }

    @Test
    fun spreadNeedsThreeReadings() {
        assertEquals(null, Signal.spread(listOf(-50, -60)))
        assertEquals(0.0, Signal.spread(listOf(-50, -50, -50))!!, 1e-9)
        assertEquals(4.0, Signal.spread(listOf(-46, -54, -46, -54))!!, 1e-9)
    }

    @Test
    fun bars() {
        assertEquals("          ", Signal.bars(0, 10))
        assertEquals("|||||     ", Signal.bars(50, 10))
        assertEquals("||||||||||", Signal.bars(100, 10))
    }
}
