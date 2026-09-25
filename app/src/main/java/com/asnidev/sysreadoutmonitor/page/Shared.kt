package com.asnidev.sysreadoutmonitor.page

import android.os.SystemClock
import com.asnidev.sysreadoutmonitor.data.MonitorPrefs
import com.asnidev.sysreadoutmonitor.log.Access
import com.asnidev.sysreadoutmonitor.log.ProbeReader.Companion.bytes
import com.asnidev.sysreadoutmonitor.monitor.Parsers
import com.asnidev.sysreadoutmonitor.monitor.Proc
import com.asnidev.sysreadoutmonitor.term.Col
import com.asnidev.sysreadoutmonitor.term.KEY_WIDTH
import com.asnidev.sysreadoutmonitor.term.Line
import com.asnidev.sysreadoutmonitor.term.Meter
import com.asnidev.sysreadoutmonitor.term.Span
import com.asnidev.sysreadoutmonitor.term.Thresholds
import com.asnidev.sysreadoutmonitor.term.Tone
import com.asnidev.sysreadoutmonitor.term.cell
import com.asnidev.sysreadoutmonitor.term.table
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** True at most once per [ms]; the first call is always due. */
class Cadence(private val ms: () -> Long) {
    private var at = -1L

    fun due(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (at >= 0 && now - at < ms()) return false
        at = now
        return true
    }

    fun reset() {
        at = -1L
    }
}

/** How often shell-based tables refresh: the page interval, but at least [MonitorPrefs.SHELL_MIN_SEC]. */
fun Env.shellMs(): Long = maxOf(prefs().intervalSec, MonitorPrefs.SHELL_MIN_SEC) * 1000L

/** A meter on its own line under a row's value. */
fun meterLine(fraction: Double, tone: Tone, label: String): Line =
    Line(listOf(Span(" ".repeat(KEY_WIDTH))), indent = KEY_WIDTH, meter = Meter(fraction.toFloat(), tone, label))

/** Shown where a value is still being fetched. */
val WAITING = Line(listOf(Span("  …", Tone.DIM)))

fun percent(x: Double): String = String.format(Locale.US, "%.1f%%", x)

fun clock(): String = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))

/** Every process from one `top` run (~0.3 s), refreshed at most every [shellMs]. */
class Procs(private val env: Env) {
    private val cadence = Cadence { env.shellMs() }
    var last: List<Proc>? = null
        private set

    suspend fun sample(): List<Proc>? {
        if (!cadence.due() && last != null) return last
        val out = env.shizuku.exec("top -b -n 1 -q -o PID,UID,%CPU,RES,NAME") ?: return last
        // Hide the sampler itself (sh + top running as shell).
        last = Parsers.top(out).filterNot { it.uid == Labels.SHELL_UID && (it.name == "top" || it.name == "sh") }
        return last
    }

    /** The table, or the line saying what's missing. */
    fun section(list: List<Proc>?, byCpu: Boolean): List<Line> =
        env.gate(Access.SHIZUKU)?.let(::listOf) ?: list?.let { table(it, byCpu) } ?: listOf(WAITING)

    /** The busiest [rows] processes by CPU or by memory, `top`-style. */
    fun table(procs: List<Proc>, byCpu: Boolean, rows: Int = 25): List<Line> {
        val sorted = procs.sortedWith(
            if (byCpu) compareByDescending<Proc> { it.cpu }.thenByDescending { it.resBytes }
            else compareByDescending { it.resBytes },
        ).take(rows)
        val cpu = Col("CPU%", right = true)
        val res = Col("RES", right = true)
        return table(
            listOf(Col("PID", right = true)) + (if (byCpu) listOf(cpu, res) else listOf(res, cpu)) + Col("PROCESS"),
            sorted.map { p ->
                val cpuCell = cell(String.format(Locale.US, "%.1f", p.cpu), Thresholds.flag(Thresholds.load(p.cpu.toDouble())))
                val resCell = cell(bytes(p.resBytes))
                listOf(cell(p.pid.toString(), Tone.DIM)) + (if (byCpu) listOf(cpuCell, resCell) else listOf(resCell, cpuCell)) +
                    cell(env.labels.procLabel(p))
            },
        )
    }
}
