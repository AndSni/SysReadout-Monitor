package com.asnidev.sysreadoutmonitor.page

import com.asnidev.sysreadoutmonitor.log.ProbeReader.Companion.bytes
import com.asnidev.sysreadoutmonitor.term.Line
import com.asnidev.sysreadoutmonitor.term.Thresholds
import com.asnidev.sysreadoutmonitor.term.comment

/** `free -h`: RAM, swap, where the memory goes and who holds it. */
class MemSampler(private val env: Env) : PageSampler {

    private val procs = Procs(env)

    override suspend fun sample(): List<Line> {
        val r = env.reader
        val values = r.values(listOf("mem", "swap", "vm")).toMap()
        val out = ArrayList<Line>()

        values["mem"]?.let { out += Paint.row("mem", it) }
        val mi = r.memory()
        if (mi.totalMem > 0) {
            val used = mi.totalMem - mi.availMem
            val pct = used * 100.0 / mi.totalMem
            val tone = if (mi.lowMemory) Thresholds.lowMemory else Thresholds.load(pct)
            out += meterLine(used.toDouble() / mi.totalMem, tone, "${bytes(used)}/${bytes(mi.totalMem)}")
        }

        values["swap"]?.let { out += Paint.row("swap", it) }
        r.swapBytes()?.takeIf { it.second > 0 }?.let { (used, total) ->
            val pct = used * 100.0 / total
            out += meterLine(used.toDouble() / total, Thresholds.load(pct), "${bytes(used)}/${bytes(total)}")
        }

        out += comment("detail")
        values["vm"]?.let { out += Paint.row("vm", it) }

        val list = if (env.shellReady) procs.sample() else null
        out += comment("processes by memory" + (list?.let { " · ${it.size}" } ?: ""))
        out += procs.section(list, byCpu = false)
        return out
    }
}
