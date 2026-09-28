package com.asnidev.sysreadoutmonitor.page

import com.asnidev.sysreadoutmonitor.term.Span
import com.asnidev.sysreadoutmonitor.term.Tone
import org.junit.Assert.assertEquals
import org.junit.Test

class PaintTest {

    private fun coloured(id: String, value: String): Map<String, Tone> {
        val spans = Paint.spans(id, value)
        assertEquals("spans must add up to the value", value, spans.joinToString("") { it.text })
        return spans.filter { it.tone != Tone.FG }.associate { it.text to it.tone }
    }

    @Test
    fun plainRowsStayPlain() {
        assertEquals(listOf(Span("Android 14 · API 34")), Paint.spans("os", "Android 14 · API 34"))
    }

    @Test
    fun signalWords() {
        assertEquals(mapOf("very strong" to Tone.GOOD), coloured("wifi", "-48dBm very strong  866Mbps  5GHz"))
        assertEquals(mapOf("medium" to Tone.WARN), coloured("cell", "Telia LTE  -105dBm medium"))
        assertEquals(mapOf("very weak" to Tone.CRIT), coloured("cell", "Telia LTE  -120dBm very weak"))
        assertEquals(mapOf("no signal" to Tone.CRIT), coloured("cell", "no service  no signal"))
    }

    @Test
    fun battery() {
        // The level's meter shows it's fine, so only trouble is coloured; health and temperature always are.
        assertEquals(
            mapOf("31.2°C" to Tone.GOOD, "good" to Tone.GOOD),
            coloured("bat", "78%  discharging  31.2°C  health good"),
        )
        assertEquals(
            mapOf("12%" to Tone.CRIT, "47.0°C" to Tone.WARN, "OVERHEAT" to Tone.CRIT),
            coloured("bat", "12%  discharging  47.0°C  health OVERHEAT"),
        )
    }

    @Test
    fun thermal() {
        assertEquals(mapOf("none" to Tone.GOOD, "31.0°C" to Tone.GOOD), coloured("therm", "none  headroom 0.42  bat 31.0°C"))
        assertEquals(mapOf("SEVERE" to Tone.CRIT, "56.5°C" to Tone.CRIT), coloured("therm", "SEVERE  headroom n/a  bat 56.5°C"))
    }

    @Test
    fun memory() {
        assertEquals(emptyMap<String, Tone>(), coloured("mem", "3.1G/7.6G avail  59% used"))
        assertEquals(mapOf("93%" to Tone.CRIT, "LOW" to Tone.CRIT), coloured("mem", "0.5G/7.6G avail  93% used  LOW"))
        assertEquals(mapOf("75%" to Tone.WARN), coloured("swap", "1.4G/1.8G used  75%"))
    }

    @Test
    fun buildProperties() {
        assertEquals(mapOf("green" to Tone.GOOD), coloured("props", "slot a · vbs green · locked · treble · first api 31 · user"))
        assertEquals(mapOf("orange" to Tone.CRIT, "UNLOCKED" to Tone.WARN), coloured("props", "vbs orange · UNLOCKED · user"))
    }

    @Test
    fun storage() {
        assertEquals(emptyMap<String, Tone>(), coloured("fs", "41.2G/110.0G free  62% used"))
        assertEquals(mapOf("90%" to Tone.WARN), coloured("fs", "11.0G/110.0G free  90% used"))
        assertEquals(mapOf("97%" to Tone.CRIT), coloured("sd", "1.0G/32.0G free  97% used"))
    }
}
