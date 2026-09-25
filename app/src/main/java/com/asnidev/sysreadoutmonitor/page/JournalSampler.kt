package com.asnidev.sysreadoutmonitor.page

import com.asnidev.sysreadoutmonitor.data.MonitorPrefs
import com.asnidev.sysreadoutmonitor.log.Access
import com.asnidev.sysreadoutmonitor.log.SysState
import com.asnidev.sysreadoutmonitor.monitor.DnsLog
import com.asnidev.sysreadoutmonitor.monitor.NotifLog
import com.asnidev.sysreadoutmonitor.monitor.Parsers
import com.asnidev.sysreadoutmonitor.monitor.Proc
import com.asnidev.sysreadoutmonitor.monitor.UsageEvent
import com.asnidev.sysreadoutmonitor.term.Line
import com.asnidev.sysreadoutmonitor.term.Span
import com.asnidev.sysreadoutmonitor.term.Tap
import com.asnidev.sysreadoutmonitor.term.Thresholds
import com.asnidev.sysreadoutmonitor.term.Tone
import com.asnidev.sysreadoutmonitor.term.needs
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * `journalctl -f`: the launcher's event stream. It samples only while
 * visible; on each visit it catches up on what the system kept meanwhile
 * (usage events, DNS and notification logs, logcat) and on state that
 * changed (network, power, installs). Process and connection events are
 * seen only while the page is open.
 */
class JournalSampler(private val env: Env) : PageSampler {

    private class Event(val time: Long, val key: String, val tone: Tone, val text: List<Span>, val seq: Long)

    private val events = ArrayDeque<Event>()
    private var seq = 0L

    private var lastSys: SysState? = null
    private var packageSeq: Int? = null
    private var usageSince: Long? = null
    private var lastForeground: String? = null
    private var dnsSeen = 0L
    private val dnsEmitted = HashMap<String, Long>()
    private var notifSeen = 0L
    private var logcatLast: Double? = null
    private var lastApps: Map<Int, Proc>? = null
    private var lastConns: Set<Pair<Int, String>>? = null
    private val shellCadence = Cadence { env.shellMs() }

    override fun stop() {
        // Processes and connections can't be caught up on; start their comparison afresh next time.
        lastApps = null
        lastConns = null
        shellCadence.reset()
    }

    override suspend fun sample(): List<Line> {
        val m = env.prefs()
        val now = System.currentTimeMillis()
        if (m.evSystem) {
            system(env.reader.state())
            packages()
        }
        if (env.missing(Access.USAGE) == null && (m.evApps || m.evServices || m.evScreen)) {
            // First visit: the last hour, which Android keeps anyway.
            env.usage.events(usageSince ?: (now - 60 * 60_000L), now).forEach { usage(it, m) }
        }
        usageSince = now
        if (m.evDns) dns() else dnsSeen = now
        if (m.evNotif) notifications(m) else notifSeen = now
        if (env.missing(Access.SHIZUKU) == null && shellCadence.due()) {
            if (m.evProcs) procs() else lastApps = null
            if (m.evConns) conns() else lastConns = null
            if (m.evLogcat) logcat(m)
        }
        return missing(m) + render()
    }

    // --- sources ---

    private fun system(now: SysState) {
        val was = lastSys
        lastSys = now
        if (was == null) return
        if (now.net != was.net) emit("net", Tone.SYSTEM, "${was.net} → ${now.net}")
        if (now.power != was.power) emit("power", Tone.SYSTEM, if (now.power == "battery") "unplugged" else "plugged in (${now.power})")
        if (now.level / 5 != was.level / 5) emit("bat", Tone.SYSTEM, listOf(Span("${now.level}%", Thresholds.battery(now.level))))
        if (now.thermal != was.thermal) {
            emit("therm", Tone.SYSTEM, listOf(Span("${was.thermal} → "), Span(now.thermal, Thresholds.thermal(now.thermal))))
        }
        if (now.lowMemory != was.lowMemory) {
            emit("mem", Tone.SYSTEM, listOf(if (now.lowMemory) Span("LOW MEMORY", Thresholds.lowMemory) else Span("memory ok", Tone.GOOD)))
        }
    }

    /** Installs, updates and removals since the last look, from the package manager's change counter. */
    private fun packages() {
        val pm = env.context.packageManager
        val changed = pm.getChangedPackages(packageSeq ?: 0)
        val first = packageSeq == null
        packageSeq = changed?.sequenceNumber ?: packageSeq ?: 0
        if (first || changed == null) return
        changed.packageNames.forEach { pkg ->
            val info = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()
            val label = env.labels.pkgLabel(pkg)
            emit(
                "pkg", Tone.SYSTEM,
                when {
                    info == null -> "- $label removed"
                    info.firstInstallTime == info.lastUpdateTime -> "+ $label installed"
                    else -> "~ $label updated"
                },
            )
        }
    }

    private fun usage(e: UsageEvent, m: MonitorPrefs) {
        when (e.kind) {
            UsageEvent.Kind.FOREGROUND -> {
                // Skip ourselves and activity changes inside the same app.
                if (e.pkg == env.context.packageName) {
                    lastForeground = e.pkg
                } else if (m.evApps && e.pkg != lastForeground) {
                    lastForeground = e.pkg
                    emit("fg", Tone.USAGE, env.labels.pkgLabel(e.pkg), e.time)
                }
            }
            UsageEvent.Kind.SERVICE_START -> if (m.evServices) emit("svc", Tone.USAGE, "+ ${env.labels.pkgLabel(e.pkg)}", e.time)
            UsageEvent.Kind.SERVICE_STOP -> if (m.evServices) emit("svc", Tone.USAGE, "- ${env.labels.pkgLabel(e.pkg)}", e.time)
            UsageEvent.Kind.SCREEN_ON -> if (m.evScreen) emit("scrn", Tone.USAGE, "on", e.time)
            UsageEvent.Kind.SCREEN_OFF -> if (m.evScreen) emit("scrn", Tone.USAGE, "off", e.time)
            UsageEvent.Kind.UNLOCKED -> if (m.evScreen) emit("lock", Tone.USAGE, "unlocked", e.time)
            UsageEvent.Kind.LOCKED -> if (m.evScreen) emit("lock", Tone.USAGE, "locked", e.time)
        }
    }

    private fun dns() {
        DnsLog.since(dnsSeen).forEach { l ->
            dnsSeen = maxOf(dnsSeen, l.time)
            // Reverse lookups (…in-addr.arpa) are tools resolving IPs, not apps reaching servers.
            if (l.name.endsWith(".arpa") || l.name.isEmpty()) return@forEach
            val key = "${l.uid}/${l.name}"
            // Apps repeat lookups constantly; show each app+name once every few minutes.
            if (l.time - (dnsEmitted[key] ?: 0L) > DNS_REPEAT_MS) {
                dnsEmitted[key] = l.time
                emit("dns", Tone.DNS, "${env.labels.uidLabel(l.uid)} → ${l.name}", l.time)
            }
        }
        dnsEmitted.entries.removeAll { System.currentTimeMillis() - it.value > 2 * DNS_REPEAT_MS }
    }

    private fun notifications(m: MonitorPrefs) {
        NotifLog.since(notifSeen).forEach { n ->
            notifSeen = maxOf(notifSeen, n.time)
            val what = n.category?.let { " · $it" } ?: ""
            val title = if (m.notifTitles) n.title?.let { "  \"$it\"" } ?: "" else ""
            emit("ntf", Tone.NOTIF, env.labels.pkgLabel(n.pkg) + what + title, n.time)
        }
    }

    private suspend fun procs() {
        val out = env.shizuku.exec("top -b -n 1 -q -o PID,UID,%CPU,RES,NAME") ?: return
        val apps = Parsers.top(out).filter { it.isApp }.associateBy { it.pid }
        val was = lastApps
        lastApps = apps
        if (was == null) return
        (apps.keys - was.keys).forEach { emit("proc", Tone.SHELL, "+ ${env.labels.procLabel(apps.getValue(it))}  pid $it") }
        (was.keys - apps.keys).forEach { emit("proc", Tone.SHELL, "- ${env.labels.procLabel(was.getValue(it))}  pid $it") }
    }

    private suspend fun conns() {
        val socks = listOf("tcp", "tcp6", "udp", "udp6").flatMap { proto ->
            Parsers.procNet(env.shizuku.readFile("/proc/net/$proto").orEmpty(), proto)
        }.distinctBy { it.uid to it.remote }
        val keys = socks.map { it.uid to it.remote }.toSet()
        val was = lastConns
        lastConns = keys
        if (was == null) return
        socks.filter { (it.uid to it.remote) !in was }.forEach { s ->
            emit(
                "conn", Tone.SHELL,
                "${env.labels.uidLabel(s.uid)} → ${s.remote}" + (DnsLog.host(s.remoteIp)?.let { "  $it" } ?: "") +
                    if (s.proto == "udp") "  udp" else "",
            )
        }
    }

    private suspend fun logcat(m: MonitorPrefs) {
        val level = if (m.logcatWarnings) "W" else "E"
        val since = logcatLast
        val command = if (since == null) {
            "logcat -d -v epoch -t 20 '*:$level'"
        } else {
            "logcat -d -v epoch -T ${String.format(Locale.US, "%.3f", since)} '*:$level' | tail -n 100"
        }
        val lines = Parsers.logcat(env.shizuku.exec(command) ?: return)
        lines.filter { since == null || it.time > since }.forEach {
            val tone = if (it.level == 'W') Tone.WARN else Tone.CRIT
            emit("log${it.level}", tone, "${it.tag}: ${it.message}", (it.time * 1000).toLong())
        }
        logcatLast = lines.maxOfOrNull { it.time } ?: since ?: (System.currentTimeMillis() / 1000.0)
    }

    // --- output ---

    private fun emit(key: String, tone: Tone, text: String, time: Long = System.currentTimeMillis()) =
        emit(key, tone, listOf(Span(text)), time)

    private fun emit(key: String, tone: Tone, text: List<Span>, time: Long = System.currentTimeMillis()) {
        val event = Event(time, key, tone, text, ++seq)
        // Late events (usage, DNS, logcat) arrive with their own time: keep the stream in time order.
        var i = events.size
        while (i > 0 && events[i - 1].time > time) i--
        events.add(i, event)
        while (events.size > MAX) events.removeFirst()
    }

    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    private fun render(): List<Line> {
        if (events.isEmpty()) return listOf(Line(listOf(Span("-- no entries yet --", Tone.DIM))))
        return events.map { e ->
            Line(
                listOf(Span(clock.format(Instant.ofEpochMilli(e.time)) + " ", Tone.DIM), Span(e.key.padEnd(KEY), e.tone)) + e.text,
                indent = 9 + KEY,
            )
        }
    }

    /** What enabled sources can't report yet, as tappable lines above the stream. */
    private fun missing(m: MonitorPrefs): List<Line> = buildList {
        if ((m.evApps || m.evServices || m.evScreen) && env.missing(Access.USAGE) != null) {
            add(needs("app, service and screen events need usage access, tap to grant", Tap.Grant(Access.USAGE)))
        }
        if ((m.evProcs || m.evConns || m.evLogcat)) env.missing(Access.SHIZUKU)?.let {
            add(needs("process, connection and logcat events need shizuku, tap to set up", Tap.Grant(Access.SHIZUKU)))
        }
        if (m.evNotif && env.missing(Access.NOTIFICATIONS) != null) {
            add(needs("notification events need notification access, tap to grant", Tap.Grant(Access.NOTIFICATIONS)))
        }
        if (m.evDns && !DnsLog.running.value) {
            add(needs("dns events need the dns monitor, tap to set it up", Tap.Goto(Page.CONF)))
        }
    }

    private companion object {
        const val MAX = 500
        const val KEY = 6
        const val DNS_REPEAT_MS = 5 * 60_000L
    }
}
