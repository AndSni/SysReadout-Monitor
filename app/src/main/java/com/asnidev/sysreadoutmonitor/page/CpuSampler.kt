package com.asnidev.sysreadoutmonitor.page

import com.asnidev.sysreadoutmonitor.log.Access
import com.asnidev.sysreadoutmonitor.monitor.CoreTicks
import com.asnidev.sysreadoutmonitor.monitor.Parsers
import com.asnidev.sysreadoutmonitor.term.KEY_WIDTH
import com.asnidev.sysreadoutmonitor.term.Line
import com.asnidev.sysreadoutmonitor.term.Meter
import com.asnidev.sysreadoutmonitor.term.Span
import com.asnidev.sysreadoutmonitor.term.Thresholds
import com.asnidev.sysreadoutmonitor.term.Tone
import com.asnidev.sysreadoutmonitor.term.comment
import com.asnidev.sysreadoutmonitor.term.row
import kotlinx.coroutines.delay
import java.util.Locale

/** `top`: cores, load, temperatures and the busiest processes. */
class CpuSampler(private val env: Env) : PageSampler {

    private val procs = Procs(env)
    private var ticks: Map<Int, CoreTicks>? = null
    private val tempsCadence = Cadence { 2 * env.shellMs() }
    private var temps: Map<String, Float>? = null

    override fun stop() {
        ticks = null
        tempsCadence.reset()
    }

    override suspend fun sample(): List<Line> {
        val r = env.reader
        val values = r.values(listOf("cpu", "therm", "self")).toMap()
        val clocks = r.coreClocks()
        val out = ArrayList<Line>()
        values["cpu"]?.let { out += Paint.row("cpu", it) }

        val shellGate = env.gateRow("load", Access.SHIZUKU)
        val load = if (shellGate == null) env.shizuku.readFile("/proc/loadavg")?.trim()?.split(' ') else null
        out += shellGate ?: row("load", if (load != null && load.size >= 4) "${load[0]} ${load[1]} ${load[2]}  run/total ${load[3]}" else "…")

        out += comment("cores")
        val usage = if (shellGate == null) coreUsage() else null
        clocks.forEachIndexed { core, khz ->
            val key = Span("cpu$core".padEnd(KEY_WIDTH), Tone.KEY)
            val clock = khz?.let { Span(String.format(Locale.US, "%.2fGHz ", it / 1_000_000.0)) }
            val use = usage?.getOrNull(core)
            out += when {
                use != null -> Line(listOfNotNull(key, clock), indent = KEY_WIDTH, meter = Meter(use.toFloat(), Thresholds.load(use * 100), percent(use * 100)))
                usage != null && khz == null -> Line(listOf(key, Span("offline", Tone.DIM)))
                else -> Line(listOf(key, clock ?: Span("n/a", Tone.DIM)))
            }
        }

        values["therm"]?.let { out += Paint.row("therm", it) }
        out += env.gateRow("temps", Access.SHIZUKU) ?: tempsRow()
        values["self"]?.let { out += row("self", it) }

        val list = if (shellGate == null) procs.sample() else null
        out += comment("processes by cpu" + (list?.let { " · ${it.size}" } ?: ""))
        out += procs.section(list, byCpu = true)
        return out
    }

    /** Busy share of each core since the last sample; the first sample measures over 300 ms. */
    private suspend fun coreUsage(): List<Double?>? {
        if (ticks == null) {
            ticks = Parsers.cpuTicks(env.shizuku.readFile("/proc/stat") ?: return null)
            delay(300)
        }
        val now = Parsers.cpuTicks(env.shizuku.readFile("/proc/stat") ?: return null)
        val was = ticks ?: return null
        ticks = now
        return (0..(now.keys.maxOrNull() ?: -1)).map { core ->
            val a = was[core]
            val b = now[core]
            if (a == null || b == null || b.total <= a.total) null
            else ((b.busy - a.busy).toDouble() / (b.total - a.total)).coerceIn(0.0, 1.0)
        }
    }

    private suspend fun tempsRow(): Line {
        if (tempsCadence.due()) {
            env.shizuku.exec("dumpsys thermalservice")?.let { temps = Parsers.temperatures(it) }
        }
        val t = temps ?: return row("temps", listOf(Span("…", Tone.DIM)))
        if (t.isEmpty()) return row("temps", "no sensors reported")
        val spans = ArrayList<Span>()
        t.entries.forEachIndexed { i, (name, c) ->
            if (i > 0) spans += Span("  ")
            spans += Span("$name ")
            spans += Span(String.format(Locale.US, "%.1f°C", c), Thresholds.temperature(c.toDouble()))
        }
        return row("temps", spans)
    }
}
