package com.asnidev.sysreadoutmonitor.page

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.asnidev.sysreadoutmonitor.log.Access
import com.asnidev.sysreadoutmonitor.monitor.ChargeStats
import com.asnidev.sysreadoutmonitor.monitor.Drain
import com.asnidev.sysreadoutmonitor.monitor.Parsers
import com.asnidev.sysreadoutmonitor.monitor.PowerUse
import com.asnidev.sysreadoutmonitor.monitor.WakeLock
import com.asnidev.sysreadoutmonitor.term.Col
import com.asnidev.sysreadoutmonitor.term.Line
import com.asnidev.sysreadoutmonitor.term.Span
import com.asnidev.sysreadoutmonitor.term.Thresholds
import com.asnidev.sysreadoutmonitor.term.Tone
import com.asnidev.sysreadoutmonitor.term.cell
import com.asnidev.sysreadoutmonitor.term.comment
import com.asnidev.sysreadoutmonitor.term.row
import com.asnidev.sysreadoutmonitor.term.table
import java.util.Locale
import kotlin.math.abs

/**
 * `upower -d`: everything the phone says about its battery. The live rows need
 * nothing; what happened since the last charge (drain by screen state, doze,
 * learned capacity, use per part of the phone and per app) comes from
 * batterystats through Shizuku.
 */
class PowerSampler(private val env: Env) : PageSampler {

    private val bm = env.context.getSystemService(BatteryManager::class.java)

    // batterystats only moves slowly; wake locks come and go.
    private val statsCadence = Cadence { 5 * 60_000L }
    private val wakeCadence = Cadence { 2 * env.shellMs() }
    private var drains: List<Drain>? = null
    private var use: PowerUse? = null
    private var stats: ChargeStats? = null
    private var statsAt: String? = null
    private var wakeLocks: List<WakeLock>? = null

    override suspend fun sample(): List<Line> {
        val r = env.reader
        val values = r.values(listOf("bat", "pwr", "chg", "mode")).toMap()
        val battery = env.context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val out = ArrayList<Line>()

        values["bat"]?.let { out += Paint.row("bat", it) }
        val level = r.batteryLevel()
        if (level in 0..100) out += meterLine(level / 100.0, Thresholds.battery(level), "$level%")
        values["pwr"]?.let { out += Paint.row("pwr", it) }
        average()?.let { out += row("avg", it) }
        values["chg"]?.let { out += Paint.row("chg", it) }
        out += charger(battery)
        cell(battery)?.let { out += it }
        values["mode"]?.let { out += Paint.row("mode", it) }

        // Everything since the last charge comes from batterystats: one line says what's missing.
        env.gate(Access.SHIZUKU)?.let { gate ->
            out += comment("since the last charge, battery use by part and by app, wake locks")
            out += gate
            return out
        }
        if (statsCadence.due() || drains == null) {
            env.shizuku.exec("dumpsys batterystats --usage", 8_000)?.let { text ->
                use = Parsers.powerUse(text)
                drains = Parsers.batteryUsage(text).filter { it.mah >= 0.01 }.sortedByDescending { it.mah }
                statsAt = clock().take(5)
            }
            // The full dump is huge (its history); sed keeps the summary block on the phone's side.
            env.shizuku.exec("dumpsys batterystats | sed -n '/^Statistics since last charge:/,/^  Screen on:/p'", 10_000)
                ?.let { stats = Parsers.chargeStats(it) }
        }
        if (wakeCadence.due() || wakeLocks == null) {
            env.shizuku.exec("dumpsys power")?.let { wakeLocks = Parsers.wakeLocks(it) }
        }

        val s = stats
        out += comment("since the last charge" + (s?.since?.let { " at ${sinceText(it)}" } ?: "") + (statsAt?.let { " · as of $it" } ?: ""))
        out += s?.let { sinceCharge(it, use, level) } ?: listOf(WAITING)
        out += comment("battery use by part of the phone")
        out += use?.let(::partTable) ?: listOf(WAITING)
        out += comment("battery use by app")
        out += drains?.let(::drainTable) ?: listOf(WAITING)
        out += comment("wake locks held now" + (wakeLocks?.let { " · ${it.size}" } ?: ""))
        out += wakeLocks?.let(::wakeTable) ?: listOf(WAITING)
        return out
    }

    /** Average current (over the fuel gauge's window) and the energy counter, where the phone has them. */
    private fun average(): String? {
        val parts = ArrayList<String>()
        val avg = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        if (avg != Int.MIN_VALUE && avg != 0) {
            // Same unit guess as pwr: above 20 000 it can only be µA.
            val ma = if (abs(avg) > 20_000) avg / 1000 else avg
            parts += "${if (ma > 0) "+" else ""}${ma}mA average"
        }
        val nwh = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
        if (nwh != Long.MIN_VALUE && nwh > 0) parts += String.format(Locale.US, "%.2fWh left", nwh / 1e9)
        return parts.joinToString("  ").ifEmpty { null }
    }

    /** What's plugged in and the most it may deliver (the battery broadcast's max_charging_* extras). */
    private fun charger(b: Intent?): Line {
        val plugged = b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val source = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac charger"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            8 -> "dock" // BATTERY_PLUGGED_DOCK, API 33
            else -> return row("charger", listOf(Span("not plugged in", Tone.DIM)))
        }
        val spans = mutableListOf(Span(source))
        val ua = b?.getIntExtra("max_charging_current", 0) ?: 0
        val uv = b?.getIntExtra("max_charging_voltage", 0) ?: 0
        if (ua > 0 && uv > 0) {
            spans += Span(String.format(Locale.US, "  max %.1fV %.2fA = %.1fW", uv / 1e6, ua / 1e6, uv / 1e6 * ua / 1e6))
        }
        if ((b?.getIntExtra("invalid_charger", 0) ?: 0) != 0) spans += Span("  INVALID CHARGER", Tone.CRIT)
        return row("charger", spans)
    }

    private fun cell(b: Intent?): Line? {
        b ?: return null
        val spans = ArrayList<Span>()
        b.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.takeIf { it.isNotBlank() }?.let { spans += Span(it) }
        if (!b.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)) spans += Span("  not present", Tone.CRIT)
        if (b.getBooleanExtra(BATTERY_LOW, false)) spans += Span("  battery low", Tone.CRIT)
        val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (scale != 100 && scale > 0) spans += Span("  scale $scale", Tone.DIM)
        return if (spans.isEmpty()) null else row("cell", spans)
    }

    /** Drain overall and by screen state, doze, capacity, and time left at this charge's pace. */
    private fun sinceCharge(s: ChargeStats, use: PowerUse?, level: Int): List<Line> {
        val out = ArrayList<Line>()
        fun rate(mah: Int?, ms: Long?): String? =
            if (mah == null || ms == null || ms < 60_000) null else String.format(Locale.US, "~%.0fmAh/h", mah / (ms / 3_600_000.0))
        s.discharge?.let { d ->
            out += row("used", listOfNotNull(Span("${d}mAh"), rate(d, s.onBatteryMs)?.let { Span("  $it") }, s.onBatteryMs?.let { Span("  over ${duration(it)} on battery", Tone.DIM) }))
        }
        if (s.screenOnDischarge != null || s.screenOnMs != null) {
            out += row(
                "screen",
                listOfNotNull(
                    Span("on "), Span(s.screenOnMs?.let(::duration) ?: "?"), s.screenOns?.let { Span(" (${it}×)", Tone.DIM) },
                    s.screenOnDischarge?.let { Span("  ${it}mAh") }, rate(s.screenOnDischarge, s.screenOnMs)?.let { Span(" $it") },
                ),
            )
            out += row(
                "",
                listOfNotNull(
                    Span("off "), Span(s.screenOffMs?.let(::duration) ?: "?"),
                    s.screenOffDischarge?.let { Span("  ${it}mAh") }, rate(s.screenOffDischarge, s.screenOffMs)?.let { Span(" $it") },
                ),
            )
        }
        if (s.lightDozeDischarge != null || s.deepDozeDischarge != null) {
            out += row("doze", "light ${s.lightDozeDischarge ?: "?"}mAh  deep ${s.deepDozeDischarge ?: "?"}mAh")
        }
        val caps = listOfNotNull(
            use?.capacity?.let { "rated ${it}mAh" },
            s.estimatedCapacity?.let { "estimated ${it}mAh" },
            s.learnedCapacity?.let { l ->
                "learned ${l}mAh" + if (s.minLearned != null && s.maxLearned != null && s.minLearned != s.maxLearned) " (${s.minLearned}–${s.maxLearned})" else ""
            },
        )
        if (caps.isNotEmpty()) out += row("cap", caps.joinToString("  "))
        // Charge left divided by this charge's average drain.
        val perHour = if (s.discharge != null && s.onBatteryMs != null && s.onBatteryMs > 3_600_000) s.discharge / (s.onBatteryMs / 3_600_000.0) else null
        val capacity = s.estimatedCapacity ?: use?.capacity
        val charging = bm.isCharging
        if (perHour != null && perHour > 0 && capacity != null && level in 0..100 && !charging) {
            out += row("left", listOf(Span("~" + duration((capacity * level / 100.0 / perHour * 3_600_000).toLong())), Span("  at this charge's average use", Tone.DIM)))
        }
        return out.ifEmpty { listOf(note("nothing reported since the last charge")) }
    }

    private fun partTable(u: PowerUse): List<Line> {
        // Parts that round to 0.0 mAh say nothing.
        val parts = u.components.filter { it.mah >= 0.05 }
        if (parts.isEmpty()) return listOf(note("nothing measured yet"))
        val lines = table(
            listOf(Col("mAh", right = true), Col("TIME", right = true), Col("PART")),
            parts.map { c ->
                val time = c.durationMs?.let { if (it < 60_000) "${it / 1000}s" else duration(it) }.orEmpty()
                listOf(cell(String.format(Locale.US, "%.1f", c.mah)), cell(time, Tone.DIM), cell(c.name.replace('_', ' ')))
            },
        )
        val total = listOfNotNull(u.computedDrain?.let { "computed ${it}mAh" }, u.actualDrain?.let { "measured ${it}mAh" })
        return if (total.isEmpty()) lines else lines + note(total.joinToString(" · "))
    }

    private fun drainTable(list: List<Drain>): List<Line> {
        if (list.isEmpty()) return listOf(note("nothing measured yet"))
        return table(
            listOf(Col("mAh", right = true), Col("MOSTLY"), Col("APP")),
            list.take(20).map { d ->
                listOf(cell(String.format(Locale.US, "%.1f", d.mah)), cell(d.mostly.orEmpty(), Tone.DIM), cell(env.labels.uidLabel(d.uid)))
            },
        )
    }

    private fun wakeTable(list: List<WakeLock>): List<Line> {
        if (list.isEmpty()) return listOf(note("none held"))
        return table(
            listOf(Col("TYPE"), Col("APP"), Col("TAG")),
            list.take(30).map { w -> listOf(cell(w.level, Tone.DIM), cell(env.labels.uidLabel(w.uid)), cell(w.tag)) },
        )
    }

    /** "2026-09-21-06-01-14" → "09-21 06:01". */
    private fun sinceText(s: String): String {
        val p = s.split('-')
        return if (p.size >= 5) "${p[1]}-${p[2]} ${p[3]}:${p[4]}" else s
    }

    private companion object {
        /** BatteryManager.EXTRA_BATTERY_LOW, API 28. */
        const val BATTERY_LOW = "battery_low"
    }
}
