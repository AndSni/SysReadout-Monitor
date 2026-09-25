package com.asnidev.sysreadoutmonitor.page

import com.asnidev.sysreadoutmonitor.log.Access
import com.asnidev.sysreadoutmonitor.monitor.NotifLog
import com.asnidev.sysreadoutmonitor.term.Col
import com.asnidev.sysreadoutmonitor.term.Line
import com.asnidev.sysreadoutmonitor.term.Tone
import com.asnidev.sysreadoutmonitor.term.cell
import com.asnidev.sysreadoutmonitor.term.comment
import com.asnidev.sysreadoutmonitor.term.row
import com.asnidev.sysreadoutmonitor.term.table
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** `dumpsys usagestats`: screen time, unlocks, notifications and running services. */
class AppsSampler(private val env: Env) : PageSampler {

    // Usage stats are aggregated by the system; once a minute is as fresh as they get.
    private val usageCadence = Cadence { 60_000L }
    private var today: Pair<Long, Int>? = null
    private var screen: List<Pair<String, Long>>? = null
    private var services: List<Triple<String, String, Long>>? = null

    override fun stop() = usageCadence.reset()

    override suspend fun sample(): List<Line> {
        val usage = env.missing(Access.USAGE) == null
        if (usage && usageCadence.due()) {
            today = env.usage.today()
            screen = env.usage.screenTimeToday()
            services = env.usage.runningServices(System.currentTimeMillis() - 24 * 3_600_000L)
                .filter { it.first != env.context.packageName }
        }
        val out = ArrayList<Line>()
        out += env.probeRows(
            listOf("today", "ntf"),
            own = mapOf("today" to today?.takeIf { usage }?.let { (onMs, unlocks) -> row("today", "screen on ${duration(onMs)}  $unlocks unlocks") }),
        )

        out += comment("screen time today")
        out += env.gate(Access.USAGE)?.let(::listOf) ?: screen?.let(::screenTable) ?: listOf(WAITING)

        out += comment("notifications today" + if (NotifLog.connected) " · ${NotifLog.todayTotal()}" else "")
        out += env.gate(Access.NOTIFICATIONS)?.let(::listOf) ?: notifTable()

        out += comment("foreground services running (started within 24h)")
        out += env.gate(Access.USAGE)?.let(::listOf) ?: services?.let(::serviceTable) ?: listOf(WAITING)
        return out
    }

    private fun screenTable(list: List<Pair<String, Long>>): List<Line> {
        if (list.isEmpty()) return listOf(note("nothing over a minute yet"))
        return table(
            listOf(Col("TIME", right = true), Col("APP")),
            list.take(20).map { (pkg, ms) -> listOf(cell(duration(ms)), cell(env.labels.pkgLabel(pkg))) },
        )
    }

    private fun notifTable(): List<Line> {
        val list = NotifLog.todayByApp()
        if (list.isEmpty()) return listOf(note("none since SR Monitor could listen"))
        return table(
            listOf(Col("COUNT", right = true), Col("APP")),
            list.take(20).map { (pkg, n) -> listOf(cell(n.toString()), cell(env.labels.pkgLabel(pkg))) },
        )
    }

    private fun serviceTable(list: List<Triple<String, String, Long>>): List<Line> {
        if (list.isEmpty()) return listOf(note("none"))
        val since = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
        return table(
            listOf(Col("SINCE"), Col("APP"), Col("SERVICE")),
            list.map { (pkg, cls, at) ->
                listOf(cell(since.format(Instant.ofEpochMilli(at)), Tone.DIM), cell(env.labels.pkgLabel(pkg)), cell(cls.substringAfterLast('.')))
            },
        )
    }
}
