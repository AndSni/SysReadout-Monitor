package com.asnidev.sysreadoutmonitor.page

import android.content.Context
import com.asnidev.sysreadoutmonitor.data.MonitorPrefs
import com.asnidev.sysreadoutmonitor.monitor.ShizukuBridge
import com.asnidev.sysreadoutmonitor.term.Col
import com.asnidev.sysreadoutmonitor.term.Line
import com.asnidev.sysreadoutmonitor.term.Meter
import com.asnidev.sysreadoutmonitor.term.Tap
import com.asnidev.sysreadoutmonitor.term.Thresholds
import com.asnidev.sysreadoutmonitor.term.Tone
import com.asnidev.sysreadoutmonitor.term.cell
import com.asnidev.sysreadoutmonitor.term.comment
import com.asnidev.sysreadoutmonitor.term.line
import com.asnidev.sysreadoutmonitor.term.needs
import com.asnidev.sysreadoutmonitor.term.row
import com.asnidev.sysreadoutmonitor.term.table
import com.asnidev.sysreadoutmonitor.term.KEY_WIDTH

object Samplers {
    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context, shizuku: ShizukuBridge, prefs: () -> MonitorPrefs): Map<Page, PageSampler> =
        Page.entries.associateWith { Placeholder(it) }
}

/** Shows every kind of line until the page's real sampler exists. */
private class Placeholder(private val page: Page) : PageSampler {
    private var n = 0

    override suspend fun sample(): List<Line> {
        n = (n + 7) % 100
        return listOf(
            comment("${page.tab}: not built yet, a sample of the terminal's lines follows"),
            row("mem", "3.1G/7.6G avail  59% used  and a long tail that wraps under the value column when narrow"),
            line(KEY_WIDTH, Meter(n / 100f, Thresholds.load(n.toDouble()), "$n.0%")) { key("cores".padEnd(KEY_WIDTH)) },
            row("therm", listOf(com.asnidev.sysreadoutmonitor.term.Span("moderate", Thresholds.thermal("moderate")))),
            needs("needs shizuku, tap to set up", Tap.Goto(Page.CONF)),
            comment("top by cpu"),
        ) + table(
            listOf(Col("PID", right = true), Col("CPU%", right = true), Col("RES", right = true), Col("PROCESS")),
            listOf(
                listOf(cell("552", Tone.DIM), cell("12.3"), cell("341M"), cell("system_server")),
                listOf(cell("10609", Tone.DIM), cell("93.1", Tone.CRIT), cell("156M"), cell("com.google.android.gms.persistent:with_a_long_suffix")),
            ),
        )
    }
}
