package com.asnidev.sysreadoutmonitor.monitor

import org.junit.Assert.assertEquals
import org.junit.Test

class ShizukuStateTest {

    private fun state(
        enabled: Boolean = true,
        running: Boolean = true,
        installed: Boolean = true,
        preV11: Boolean = false,
        permitted: Boolean = true,
        connected: Boolean = true,
        failing: Boolean = false,
    ) = ShizukuState.of(enabled, running, installed, preV11, permitted, connected, failing)

    @Test
    fun switchedOffBeatsEverything() {
        assertEquals(ShizukuState.OFF, state(enabled = false))
    }

    @Test
    fun notRunningTellsInstalledFromMissing() {
        assertEquals(ShizukuState.NOT_RUNNING, state(running = false))
        assertEquals(ShizukuState.NOT_INSTALLED, state(running = false, installed = false))
    }

    @Test
    fun suiRunsWithoutTheShizukuApp() {
        // Sui (root) answers without the Shizuku app installed: that must still count as running.
        assertEquals(ShizukuState.READY, state(installed = false))
    }

    @Test
    fun stepsInOrder() {
        assertEquals(ShizukuState.UNSUPPORTED, state(preV11 = true, permitted = false))
        assertEquals(ShizukuState.NO_PERMISSION, state(permitted = false, connected = false))
        assertEquals(ShizukuState.CONNECTING, state(connected = false))
        assertEquals(ShizukuState.FAILING, state(connected = false, failing = true))
        assertEquals(ShizukuState.READY, state())
    }
}
