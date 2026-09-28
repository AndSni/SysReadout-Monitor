package com.asnidev.sysreadoutmonitor.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Samples from a Sony Xperia 10 IV on Android 14. */
class BatteryStatsTest {

    private val usage = """
  Estimated power use (mAh):
    Capacity: 4174, Computed drain: 2671, actual drain: 2463-2671
    Global
      screen: 127 apps: 127 duration: 1h 43m 48s 458ms 
      cpu: 39.9 apps: 39.9
      bluetooth: 142 apps: 32.4 duration: 10h 31m 24s 605ms 
      camera: 0.441 apps: 0.441 duration: 2s 725ms 
      mobile_radio: 6386 apps: 2584 duration: 3h 17m 57s 867ms 
      wakelock: 0 apps: 0
    UID u0a195: 10.5 ( cpu=10.5 )
"""

    private val stats = """
Statistics since last charge:
  System starts: 0, currently on battery: false
  Estimated battery capacity: 4173 mAh
  Last learned battery capacity: 4174 mAh
  Min learned battery capacity: 4174 mAh
  Max learned battery capacity: 4174 mAh
  Time on battery: 4d 5h 13m 46s 939ms (98.0%) realtime, 23h 49m 29s 411ms (23.5%) uptime
  Time on battery screen off: 4d 3h 29m 58s 481ms (98.3%) realtime, 22h 5m 40s 952ms (21.8%) uptime
  Time on battery screen doze: 0ms (0.0%)
  Total run time: 4d 7h 18m 12s 799ms realtime, 1d 1h 53m 55s 271ms uptime
  Discharge: 2671 mAh
  Screen off discharge: 2254 mAh
  Screen doze discharge: 0 mAh
  Screen on discharge: 417 mAh
  Device light doze discharge: 960 mAh
  Device deep doze discharge: 835 mAh
  Start clock time: 2026-09-21-06-01-14
  Screen on: 1h 43m 48s 458ms (1.7%) 96x, Interactive: 1h 50m 17s 986ms (1.8%)
"""

    @Test
    fun durations() {
        assertEquals(((4 * 24 + 5) * 3600 + 13 * 60 + 46) * 1000L + 939, Parsers.duration("4d 5h 13m 46s 939ms"))
        assertEquals(2725L, Parsers.duration("2s 725ms"))
        assertEquals(0L, Parsers.duration("0ms"))
        assertNull(Parsers.duration("n/a"))
    }

    @Test
    fun powerUse() {
        val p = Parsers.powerUse(usage)
        assertEquals(4174, p.capacity)
        assertEquals(2671, p.computedDrain)
        assertEquals("2463-2671", p.actualDrain)
        // Biggest first; zero-use parts and the per-app lines are left out.
        assertEquals(listOf("mobile_radio", "bluetooth", "screen", "cpu", "camera"), p.components.map { it.name })
        assertEquals(Component("screen", 127.0, (1 * 3600 + 43 * 60 + 48) * 1000L + 458), p.components[2])
        assertNull(p.components[3].durationMs)
    }

    @Test
    fun chargeStats() {
        val s = Parsers.chargeStats(stats)
        assertEquals(4173, s.estimatedCapacity)
        assertEquals(4174, s.learnedCapacity)
        assertEquals(2671, s.discharge)
        assertEquals(2254, s.screenOffDischarge)
        assertEquals(417, s.screenOnDischarge)
        assertEquals(960, s.lightDozeDischarge)
        assertEquals(835, s.deepDozeDischarge)
        assertEquals(96, s.screenOns)
        assertEquals((1 * 3600 + 43 * 60 + 48) * 1000L + 458, s.screenOnMs)
        assertEquals(((4 * 24 + 5) * 3600 + 13 * 60 + 46) * 1000L + 939, s.onBatteryMs)
        assertEquals(((4 * 24 + 3) * 3600 + 29 * 60 + 58) * 1000L + 481, s.screenOffMs)
        assertEquals("2026-09-21-06-01-14", s.since)
    }

    @Test
    fun missingBlockGivesNulls() {
        assertEquals(ChargeStats(), Parsers.chargeStats("nothing here"))
        assertEquals(PowerUse(null, null, null, emptyList()), Parsers.powerUse(""))
    }

    @Test
    fun workProfileUids() {
        assertEquals(1_010_177, Parsers.batteryUid("u10a177"))
        assertEquals(10, userOf(1_010_177))
        assertEquals(10_177, appIdOf(1_010_177))
        assertEquals(true, Proc(1, 1_010_177, 0f, 0, "com.example").isApp)
        assertEquals(false, Proc(1, 1_001_000, 0f, 0, "system_server").isApp)
    }
}
