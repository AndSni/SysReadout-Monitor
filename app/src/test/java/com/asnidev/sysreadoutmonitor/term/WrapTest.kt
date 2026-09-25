package com.asnidev.sysreadoutmonitor.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WrapTest {

    private fun texts(line: Line, cols: Int) = Wrap.lines(line, cols).map { it.text }

    @Test
    fun shortLinesStayAsTheyAre() {
        val line = row("mem", "3.1G/7.6G")
        assertEquals(listOf(line), Wrap.lines(line, 40))
    }

    @Test
    fun rowsContinueUnderTheValueColumn() {
        val out = texts(row("mem", "3.1G/7.6G avail  59% used  LOW"), 26)
        assertEquals(listOf("mem     3.1G/7.6G avail", "        59% used  LOW"), out)
        out.forEach { assertTrue(it, it.length <= 26) }
    }

    @Test
    fun prefersFieldBreaksOverSingleSpaces() {
        // "avail  59% used" could break after "59%"; the double space is the better place.
        val out = texts(row("mem", "3.1G/7.6G avail  59% used"), 28)
        assertEquals("mem     3.1G/7.6G avail", out[0])
        assertEquals("        59% used", out[1])
    }

    @Test
    fun neverBreaksInsideTheKeyColumn() {
        // At 14 columns the indent is capped to 7, but the key's own padding is still no place to break.
        val out = texts(row("up", "12d 03:04:05  awake 97%"), 14)
        assertEquals("up      12d", out[0])
        out.forEach { assertTrue(it, it.length <= 14) }
    }

    @Test
    fun hardBreaksWordsLongerThanTheRow() {
        val out = texts(Line(listOf(Span("com.google.android.gms.persistent")), indent = 2), 12)
        assertEquals(listOf("com.google.a", "  ndroid.gms", "  .persisten", "  t"), out)
        out.forEach { assertTrue(it.length <= 12) }
    }

    @Test
    fun wrappingKeepsTonesAndTaps() {
        val tap = Tap.Goto(com.asnidev.sysreadoutmonitor.page.Page.CONF)
        val line = Line(listOf(Span("! needs shizuku, tap to set up", Tone.LINK, tap)), indent = 2)
        val out = Wrap.lines(line, 16)
        assertTrue(out.size > 1)
        out.forEach { l -> l.spans.filter { it.text.isNotBlank() }.forEach { assertEquals(Tone.LINK, it.tone); assertEquals(tap, it.tap) } }
    }

    @Test
    fun wideCharactersCountTwice() {
        assertEquals(4, Wrap.width("微信"))
        val out = texts(Line(listOf(Span("微信 微信 微信"))), 6)
        out.forEach { assertTrue(it, Wrap.width(it) <= 6) }
    }

    @Test
    fun meterFillsTheRestOfItsLine() {
        val line = Line(listOf(Span("cpu0 ", Tone.KEY)), meter = Meter(0.5f, Tone.GOOD, "50.0%"))
        val out = Wrap.lines(line, 30)
        assertEquals(1, out.size)
        assertEquals(30, out[0].text.length)
        assertEquals("cpu0 [||||||||||||      50.0%]", out[0].text)
    }

    @Test
    fun meterMovesToItsOwnRowWhenNarrow() {
        val line = Line(listOf(Span("mem     ", Tone.KEY), Span("3.1G/7.6G")), indent = 8, meter = Meter(0.2f, Tone.GOOD, "20%"))
        val out = texts(line, 22)
        assertEquals(2, out.size)
        assertEquals("mem     3.1G/7.6G", out[0])
        assertEquals(22, out[1].length)
        assertTrue(out[1].startsWith("        ["))
    }

    @Test
    fun meterRendering() {
        fun r(f: Float, label: String, width: Int) = Wrap.render(Meter(f, Tone.GOOD, label), width).joinToString("") { it.text }
        assertEquals("[     0%]", r(0f, "0%", 9))
        assertEquals("[|||||0%]", r(1f, "0%", 9)) // the label wins over the bars
        assertEquals("[||||50%]", r(0.5f, "50%", 9)) // 3.5 of 7 cells rounds up
        assertEquals("[||  50%]", r(0.3f, "50%", 9))
        val spans = Wrap.render(Meter(0.5f, Tone.CRIT, "50%"), 9)
        assertEquals(Tone.DIM, spans.first().tone)
        assertEquals(Tone.DIM, spans.last().tone)
        assertEquals(Tone.CRIT, spans.first { it.text.startsWith("|") }.tone)
    }

    @Test
    fun tablesAlignColumnsAndWrapTheLastUnderItself() {
        val lines = table(
            listOf(Col("PID", right = true), Col("RES", right = true), Col("PROCESS")),
            listOf(
                listOf(cell("552"), cell("341M"), cell("system_server")),
                listOf(cell("10609"), cell("12M"), cell("com.google.android.gms.persistent")),
            ),
        )
        assertEquals("  PID  RES PROCESS", lines[0].text)
        assertEquals("  552 341M system_server", lines[1].text)
        assertEquals("10609  12M com.google.android.gms.persistent", lines[2].text)
        assertTrue(lines[0].spans.filter { it.text.isNotBlank() }.all { it.tone == Tone.KEY })
        val wrapped = Wrap.lines(lines[2], 24).map { it.text }
        assertEquals(listOf("10609  12M com.google.an", "           droid.gms.per", "           sistent"), wrapped)
    }

    @Test
    fun centredLinesArePaddedAndStillWrapWhenTooWide() {
        val line = Line(listOf(Span("SYSTEM READOUT MONITOR", Tone.KEY)), center = true)
        assertEquals("    SYSTEM READOUT MONITOR", Wrap.lines(line, 30).single().text)
        assertEquals(Tone.KEY, Wrap.lines(line, 30).single().spans.last().tone)
        assertEquals(listOf("SYSTEM READOUT", "MONITOR"), texts(line, 16))
    }

    @Test
    fun anchorsStayOnTheFirstRow() {
        val line = Line(listOf(Span("[shizuku] and a tail long enough to wrap")), anchor = "shizuku")
        val out = Wrap.lines(line, 12)
        assertEquals("shizuku", out.first().anchor)
        assertTrue(out.drop(1).all { it.anchor == null })
        assertEquals("shizuku", Wrap.lines(line, 80).single().anchor)
    }

    @Test
    fun promptHostFromDeviceName() {
        assertEquals("xperia-10-iv", Prompt.host("Xperia 10 IV", "XQ-CC54"))
        assertEquals("xq-cc54", Prompt.host(null, "XQ-CC54"))
        assertEquals("sdk_gphone64_x86_64".replace("_", ""), Prompt.host("", "sdk_gphone64_x86_64"))
        assertEquals("annas-phone", Prompt.host("Anna's  Phone!", "x"))
        assertEquals("[user@pixel ~]$ ", Prompt.text("pixel"))
    }
}
