package com.asnidev.sysreadoutmonitor.page

import com.asnidev.sysreadoutmonitor.log.Access
import com.asnidev.sysreadoutmonitor.log.ProbeReader.Companion.bytes
import com.asnidev.sysreadoutmonitor.monitor.DnsLog
import com.asnidev.sysreadoutmonitor.monitor.Parsers
import com.asnidev.sysreadoutmonitor.monitor.Sock
import com.asnidev.sysreadoutmonitor.term.Col
import com.asnidev.sysreadoutmonitor.term.Line
import com.asnidev.sysreadoutmonitor.term.Span
import com.asnidev.sysreadoutmonitor.term.Tap
import com.asnidev.sysreadoutmonitor.term.Tone
import com.asnidev.sysreadoutmonitor.term.cell
import com.asnidev.sysreadoutmonitor.term.comment
import com.asnidev.sysreadoutmonitor.term.needs
import com.asnidev.sysreadoutmonitor.term.row
import com.asnidev.sysreadoutmonitor.term.table
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** `ip addr; ss -tunp`: links, radios, traffic, and who talks to whom. */
class NetSampler(private val env: Env) : PageSampler {

    private val usageCadence = Cadence { 60_000L }
    private var traffic: Map<Int, Pair<Long, Long>>? = null
    private var month: Pair<Long, Long>? = null

    private val connCadence = Cadence { env.shellMs() }
    private var socks: List<Sock>? = null

    // Reverse DNS for connections the DNS monitor didn't see; looked up beside the sampling.
    private val ptr = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 1024
    }
    private val unresolvable = HashSet<String>()
    private var resolving: Job? = null

    override fun start() = env.reader.updateWatchers(WATCHED)

    override fun stop() {
        env.reader.stopWatchers()
        resolving?.cancel()
        usageCadence.reset()
        connCadence.reset()
    }

    override suspend fun sample(): List<Line> {
        // Again each time, so a permission granted while the page is open takes effect.
        env.reader.updateWatchers(WATCHED)
        val usage = env.missing(Access.USAGE) == null
        if (usage && usageCadence.due()) {
            traffic = env.usage.trafficToday()
            month = env.usage.dataThisMonth()
        }
        val out = ArrayList<Line>()
        out += env.probeRows(
            ROWS,
            own = mapOf("month" to month?.takeIf { usage }?.let { (wifi, mobile) -> row("month", "wifi ${bytes(wifi)}  mobile ${bytes(mobile)}") }),
        )

        out += Line(listOf(Span("# every network and bluetooth device with its signal: ", Tone.DIM), Span("scan page", Tone.LINK, Tap.Goto(Page.SCAN))), indent = 2)

        out += comment("traffic today per app")
        out += env.gate(Access.USAGE)?.let(::listOf) ?: traffic?.let(::trafficTable) ?: listOf(WAITING)

        val shell = env.gate(Access.SHIZUKU)
        if (shell == null && connCadence.due()) {
            socks = listOf("tcp", "tcp6", "udp", "udp6").flatMap { proto ->
                Parsers.procNet(env.shizuku.readFile("/proc/net/$proto").orEmpty(), proto)
            }.distinctBy { it.uid to it.remote }
            resolve()
        }
        out += comment("open connections" + (socks?.takeIf { shell == null }?.let { " · ${it.size}" } ?: ""))
        out += shell?.let(::listOf) ?: socks?.let(::connections) ?: listOf(WAITING)

        out += comment("dns lookups in the last 15 minutes")
        out += lookups()
        return out
    }

    private fun trafficTable(t: Map<Int, Pair<Long, Long>>): List<Line> {
        val shown = t.entries.filter { it.value.first + it.value.second > 0 }
            .sortedByDescending { it.value.first + it.value.second }.take(15)
        if (shown.isEmpty()) return listOf(note("nothing yet today"))
        return table(
            listOf(Col("↓RX", right = true), Col("↑TX", right = true), Col("APP")),
            shown.map { (uid, rxTx) -> listOf(cell(bytes(rxTx.first)), cell(bytes(rxTx.second)), cell(env.labels.uidLabel(uid))) },
        )
    }

    /** Names the DNS monitor saw beat reverse DNS; look up only what it didn't, in the background. */
    private fun resolve() {
        if (!env.prefs().resolveHosts || resolving?.isActive == true) return
        val unknown = socks.orEmpty().map { it.remoteIp }.distinct()
            .filter { DnsLog.host(it) == null && it !in ptr && it !in unresolvable }.take(32)
        if (unknown.isEmpty()) return
        resolving = env.scope.launch {
            val found = env.shizuku.resolve(unknown)
            ptr.putAll(found)
            unresolvable.addAll(unknown.filter { it !in found })
            env.poke()
        }
    }

    private fun host(ip: String): String? = DnsLog.host(ip) ?: if (env.prefs().resolveHosts) ptr[ip] else null

    /** Grouped by app, like `ss -p` sorted by process. */
    private fun connections(list: List<Sock>): List<Line> {
        if (list.isEmpty()) return listOf(note("none"))
        val out = ArrayList<Line>()
        list.groupBy { env.labels.uidLabel(it.uid) }.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (app, socks) ->
            out += Line(listOf(Span(app, Tone.KEY)), indent = 2)
            socks.sortedBy { it.remote }.forEach { s ->
                val spans = mutableListOf(Span("  " + s.remote))
                host(s.remoteIp)?.let { spans += Span("  $it") }
                if (s.proto == "udp") spans += Span("  udp", Tone.DIM)
                out += Line(spans, indent = 4)
            }
        }
        return out
    }

    private fun lookups(): List<Line> {
        if (!DnsLog.running.value) return listOf(needs("needs the dns monitor, tap to set it up", Tap.Goto(Page.CONF)))
        val since = System.currentTimeMillis() - 15 * 60_000L
        // Reverse lookups (…in-addr.arpa) are tools resolving IPs, not apps reaching servers.
        val recent = DnsLog.since(since).filter { it.name.isNotEmpty() && !it.name.endsWith(".arpa") }
        if (recent.isEmpty()) return listOf(note("none"))
        val clock = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
        val out = ArrayList<Line>()
        recent.groupBy { it.uid }.entries.sortedByDescending { e -> e.value.maxOf { it.time } }.forEach { (uid, list) ->
            out += Line(listOf(Span(env.labels.uidLabel(uid), Tone.KEY), Span(" · ${list.size}", Tone.DIM)), indent = 2)
            list.groupBy { it.name }.entries.sortedByDescending { e -> e.value.maxOf { it.time } }.take(8).forEach { (name, hits) ->
                val spans = mutableListOf(
                    Span("  " + clock.format(Instant.ofEpochMilli(hits.maxOf { it.time })), Tone.DIM),
                    Span("  $name"),
                )
                if (hits.size > 1) spans += Span("  ×${hits.size}", Tone.DIM)
                out += Line(spans, indent = 14)
            }
        }
        return out
    }

    private companion object {
        val ROWS = listOf("net", "wifi", "ssid", "aps", "ip", "cell", "rf", "link", "tower", "data", "month")
        val WATCHED = listOf("link")
    }
}
