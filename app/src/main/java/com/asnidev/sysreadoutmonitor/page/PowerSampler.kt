package com.asnidev.sysreadoutmonitor.page

import com.asnidev.sysreadoutmonitor.log.Access
import com.asnidev.sysreadoutmonitor.monitor.Drain
import com.asnidev.sysreadoutmonitor.monitor.Parsers
import com.asnidev.sysreadoutmonitor.monitor.WakeLock
import com.asnidev.sysreadoutmonitor.term.Col
import com.asnidev.sysreadoutmonitor.term.Line
import com.asnidev.sysreadoutmonitor.term.Span
import com.asnidev.sysreadoutmonitor.term.Thresholds
import com.asnidev.sysreadoutmonitor.term.Tone
import com.asnidev.sysreadoutmonitor.term.cell
import com.asnidev.sysreadoutmonitor.term.comment
import com.asnidev.sysreadoutmonitor.term.table
import java.util.Locale

/** `upower -d`: the battery, what charges it, and what drains it. */
class PowerSampler(private val env: Env) : PageSampler {

    // batterystats only moves slowly; wake locks come and go.
    private val drainCadence = Cadence { 5 * 60_000L }
    private val wakeCadence = Cadence { 2 * env.shellMs() }
    private var drains: List<Drain>? = null
    private var drainsAt: String? = null
    private var wakeLocks: List<WakeLock>? = null

    override suspend fun sample(): List<Line> {
        val r = env.reader
        val values = r.values(listOf("bat", "pwr", "chg", "mode")).toMap()
        val out = ArrayList<Line>()

        values["bat"]?.let { out += Paint.row("bat", it) }
        val level = r.batteryLevel()
        if (level in 0..100) out += meterLine(level / 100.0, Thresholds.battery(level), "$level%")
        listOf("pwr", "chg", "mode").forEach { id -> values[id]?.let { out += Paint.row(id, it) } }

        val gate = env.gate(Access.SHIZUKU)
        if (gate == null) {
            if (drainCadence.due() || drains == null) {
                env.shizuku.exec("dumpsys batterystats --usage")?.let { text ->
                    drains = Parsers.batteryUsage(text).filter { it.mah >= 0.01 }.sortedByDescending { it.mah }
                    drainsAt = clock().take(5)
                }
            }
            if (wakeCadence.due() || wakeLocks == null) {
                env.shizuku.exec("dumpsys power")?.let { wakeLocks = Parsers.wakeLocks(it) }
            }
        }

        out += comment("battery use per app since the last charge" + (drainsAt?.let { " · as of $it" } ?: ""))
        out += gate?.let(::listOf) ?: drains?.let(::drainTable) ?: listOf(WAITING)
        out += comment("wake locks held now" + (wakeLocks?.let { " · ${it.size}" } ?: ""))
        out += gate?.let(::listOf) ?: wakeLocks?.let(::wakeTable) ?: listOf(WAITING)
        return out
    }

    private fun drainTable(list: List<Drain>): List<Line> {
        if (list.isEmpty()) return listOf(Line(listOf(Span("  nothing measured yet", Tone.DIM))))
        return table(
            listOf(Col("mAh", right = true), Col("MOSTLY"), Col("APP")),
            list.take(20).map { d ->
                listOf(cell(String.format(Locale.US, "%.1f", d.mah)), cell(d.mostly.orEmpty(), Tone.DIM), cell(env.labels.uidLabel(d.uid)))
            },
        )
    }

    private fun wakeTable(list: List<WakeLock>): List<Line> {
        if (list.isEmpty()) return listOf(Line(listOf(Span("  none held", Tone.DIM))))
        return table(
            listOf(Col("TYPE"), Col("APP"), Col("TAG")),
            list.take(30).map { w -> listOf(cell(w.level, Tone.DIM), cell(env.labels.uidLabel(w.uid)), cell(w.tag)) },
        )
    }
}
