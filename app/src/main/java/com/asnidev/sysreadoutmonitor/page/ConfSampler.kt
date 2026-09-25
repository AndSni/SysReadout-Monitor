package com.asnidev.sysreadoutmonitor.page

import android.provider.Settings
import com.asnidev.sysreadoutmonitor.BuildConfig
import com.asnidev.sysreadoutmonitor.log.Access
import com.asnidev.sysreadoutmonitor.monitor.DnsLog
import com.asnidev.sysreadoutmonitor.monitor.NotifLog
import com.asnidev.sysreadoutmonitor.monitor.ShizukuBridge
import com.asnidev.sysreadoutmonitor.monitor.ShizukuState
import com.asnidev.sysreadoutmonitor.term.Line
import com.asnidev.sysreadoutmonitor.term.Span
import com.asnidev.sysreadoutmonitor.term.Tap
import com.asnidev.sysreadoutmonitor.term.Tone
import java.util.Locale

/**
 * `nano ~/.config/srm.conf`: the settings as a config file. Values are links:
 * a tap changes the setting, grants access, or explains what's missing.
 */
class ConfSampler(private val env: Env) : PageSampler {

    override suspend fun sample(): List<Line> {
        val m = env.prefs()
        val out = ArrayList<Line>()
        fun note(text: String) {
            out += Line(listOf(Span("# $text", Tone.DIM)), indent = 2)
        }
        fun section(name: String) {
            if (out.isNotEmpty()) out += Line.BLANK
            out += Line(listOf(Span("[$name]", Tone.KEY)))
        }
        fun set(key: String, value: List<Span>) {
            out += Line(listOf(Span(key.padEnd(KEY_WIDTH) + "= ")) + value, indent = KEY_WIDTH + 2)
        }
        fun link(text: String, tap: Tap) = listOf(Span(text, Tone.LINK, tap))
        fun toggle(s: Setting) = set(s.key, link(if (s.get(m)) "on" else "off", Tap.Conf(ConfAction.Toggle(s))))

        note("srm.conf · tap a value to change it")

        section("sampling")
        set("refresh", link("${m.intervalSec}s", Tap.Conf(ConfAction.Interval)))
        note("1, 2, 5 or 10 s. shell-based tables refresh every 5 s at most, dumpsys every 10 s")
        set("text_size", link(String.format(Locale.US, "%.1fsp", m.textSp).replace(".0sp", "sp"), Tap.Conf(ConfAction.TextSize)))
        note("pinch a page to zoom between 8 and 20; tap to go back to 12")

        section("pages")
        toggle(Setting.BANNER)
        toggle(Setting.REMEMBER_PAGE)
        note("off: the app opens on sys. on: it opens on the page you left")
        note("tap [x] to show or hide a page, ↑ ↓ to move it")
        Conf.order(m).forEach { page ->
            val shown = page.tab !in m.hidden
            out += Line(
                link(if (shown) "[x]" else "[ ]", Tap.Conf(ConfAction.TogglePage(page))) +
                    Span(" " + page.tab.padEnd(9), if (shown) Tone.FG else Tone.DIM) +
                    link("↑", Tap.Conf(ConfAction.MovePage(page, -1))) + Span(" ") +
                    link("↓", Tap.Conf(ConfAction.MovePage(page, 1))),
            )
        }
        out += Line(listOf(Span("[x] conf     always shown, always last", Tone.DIM)))

        section("access")
        note("nothing is asked for until you tap it here or on the page that needs it")
        access(::set, "usage", Access.USAGE, "screen time, traffic, app sizes, app and screen events")
        access(::set, "notifications", Access.NOTIFICATIONS, "notification counts and events, now playing")
        access(::set, "location", Access.LOCATION, "gps, satellites, wi-fi name and networks, serving cell, sunrise")
        access(::set, "nearby", Access.NEARBY, "bluetooth devices and their signal on the scan page")
        access(::set, "phone", Access.PHONE, "5G / LTE-CA link details")
        access(::set, "bluetooth", Access.BLUETOOTH, "connected bluetooth devices and their battery")
        access(::set, "activity", Access.ACTIVITY, "steps")
        set("revoke", link("open android's settings for SR Monitor", Tap.AppSettings))

        shizuku(out, ::section, ::set, ::note)

        section("dns monitor")
        note("shows which app looks up which server name (imap.gmail.com, not just an ip).")
        note("it is a local vpn that routes only dns: each lookup is noted and passed on unchanged to your network's dns server. nothing else goes through it and nothing is sent anywhere else.")
        note("android allows one vpn at a time, so it can't run next to another vpn. apps with their own encrypted dns (some browsers) bypass it.")
        val running = DnsLog.running.value
        set("dns_monitor", link(if (m.dnsVpn && running) "on" else "off", Tap.DnsMonitor(!(m.dnsVpn && running))))
        if (m.dnsVpn && !running) note("switched on but not running: another vpn may have taken over")
        if (Settings.Global.getString(env.context.contentResolver, "private_dns_mode") == "hostname") {
            out += Line(
                listOf(Span("! private dns is set to a specific provider, so android sends lookups there and the monitor sees none. set it to automatic or off to use the monitor.", Tone.WARN)),
                indent = 2,
            )
        }

        section("names")
        toggle(Setting.REVERSE_DNS)
        note("look up server names for connections the dns monitor didn't see (through shizuku)")

        section("journal")
        note("which events journalctl shows")
        listOf(Setting.SYSTEM, Setting.APPS, Setting.SERVICES, Setting.SCREEN, Setting.PROCS, Setting.CONNS, Setting.DNS, Setting.NOTIFICATIONS)
            .forEach(::toggle)
        toggle(Setting.NOTIF_TITLES)
        note("notification titles can be private; they are only kept in memory")
        toggle(Setting.LOGCAT)
        set("logcat_level", link(if (m.logcatWarnings) "errors+warnings" else "errors", Tap.Conf(ConfAction.Toggle(Setting.LOGCAT_WARNINGS))))

        section("about")
        set("version", listOf(Span(BuildConfig.VERSION_NAME + if (BuildConfig.DEBUG) " (debug)" else "")))
        set("licence", listOf(Span("GPL-3.0-only, free software")))
        set("forked_from", link("SysReadout Launcher", Tap.Url(UPSTREAM)))
        set("font", link("Hack 3.003 (MIT + Bitstream Vera)", Tap.Conf(ConfAction.License(HACK))))
        if (HACK in env.openLicences) license("licenses/Hack-LICENSE.md").forEach { out += it }
        set("libraries", listOf(Span("Shizuku API, AndroidX, Kotlin (Apache-2.0)")))
        return out
    }

    private fun access(set: (String, List<Span>) -> Unit, key: String, access: Access, usedFor: String) {
        val missing = env.missing(access)
        set(
            key,
            if (missing == null) listOf(Span("granted", Tone.GOOD), Span("  # $usedFor", Tone.DIM))
            else listOf(Span(if (access.isRuntime) "not granted, tap to allow" else "not granted, tap to grant", Tone.LINK, Tap.Grant(access)), Span("  # $usedFor", Tone.DIM)),
        )
    }

    /**
     * Shizuku is optional: everything works without it, and it adds the shell's view.
     * A step-by-step setup, each step a link that does it.
     */
    private fun shizuku(
        out: MutableList<Line>,
        section: (String) -> Unit,
        set: (String, List<Span>) -> Unit,
        note: (String) -> Unit,
    ) {
        val state = env.shizuku.state.value
        section("shizuku")
        out[out.lastIndex] = out.last().copy(anchor = SHIZUKU_ANCHOR)
        note("optional. shizuku lets apps you allow use the shell's access (as adb has), so SR Monitor can also show processes, connections, per-core load, temperatures, wake locks, battery use per app and logcat. everything else works without it.")
        set(
            "status",
            when (state) {
                ShizukuState.READY -> listOf(Span("connected", Tone.GOOD))
                ShizukuState.CONNECTING -> listOf(Span("connecting…", Tone.DIM))
                ShizukuState.NOT_INSTALLED -> listOf(Span("not installed", Tone.DIM))
                ShizukuState.NOT_RUNNING -> listOf(Span("installed, not running", Tone.WARN))
                ShizukuState.UNSUPPORTED -> listOf(Span("too old, needs version 11 or newer", Tone.WARN))
                ShizukuState.NO_PERMISSION -> listOf(Span("running, SR Monitor not allowed yet", Tone.WARN))
            },
        )
        val installed = state != ShizukuState.NOT_INSTALLED && state != ShizukuState.UNSUPPORTED
        val running = installed && state != ShizukuState.NOT_RUNNING
        val allowed = running && state != ShizukuState.NO_PERMISSION
        fun done() = listOf(Span("done", Tone.GOOD))
        set("1_install", if (installed) done() else listOf(Span("tap to get shizuku (play store or github)", Tone.LINK, Tap.Url(ShizukuBridge.DOWNLOAD))))
        set(
            "2_start",
            when {
                running -> done()
                installed -> listOf(Span("tap to open shizuku, then start it", Tone.LINK, Tap.Grant(Access.SHIZUKU)))
                else -> listOf(Span("after step 1", Tone.DIM))
            },
        )
        if (!running) {
            note("android 11+: in shizuku choose \"start via wireless debugging\" and follow its pairing steps (developer options must be on). older android: start it once from a computer with adb. with root: start it with root, and it starts itself after reboots.")
        }
        set(
            "3_allow",
            when {
                allowed -> done()
                running -> listOf(Span("tap to let SR Monitor use shizuku", Tone.LINK, Tap.Grant(Access.SHIZUKU)))
                else -> listOf(Span("after step 2", Tone.DIM))
            },
        )
        note("without root, shizuku stops when the phone restarts; start it again in the shizuku app (step 2). the pages show what needs it until then.")
    }

    private fun license(asset: String): List<Line> {
        val text = runCatching { env.context.assets.open(asset).bufferedReader().readText() }.getOrDefault("licence text missing")
        return text.lines().map { Line(listOf(Span("  " + it.removePrefix("### "), Tone.DIM)), indent = 2) }
    }

    companion object {
        private const val KEY_WIDTH = 14
        private const val HACK = "hack"
        /** The [shizuku] section, for taps that lead to the setup steps. */
        const val SHIZUKU_ANCHOR = "shizuku"
        private const val UPSTREAM = "https://github.com/AndSni/SysReadout-Launcher"
    }
}
