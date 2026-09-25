package com.asnidev.sysreadoutmonitor.page

import android.provider.Settings
import com.asnidev.sysreadoutmonitor.BuildConfig
import com.asnidev.sysreadoutmonitor.log.Access
import com.asnidev.sysreadoutmonitor.monitor.DnsLog
import com.asnidev.sysreadoutmonitor.monitor.NotifLog
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
        access(::set, "notifications", Access.NOTIFICATIONS, "notification counts and events")
        shizuku(::set, ::note)
        access(::set, "location", Access.LOCATION, "gps, satellites, wi-fi name and networks, serving cell, sunrise")
        access(::set, "phone", Access.PHONE, "5G / LTE-CA link details")
        access(::set, "bluetooth", Access.BLUETOOTH, "connected bluetooth devices and their battery")
        access(::set, "activity", Access.ACTIVITY, "steps")
        set("revoke", link("open android's settings for SR Monitor", Tap.AppSettings))

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

    private fun shizuku(set: (String, List<Span>) -> Unit, note: (String) -> Unit) {
        val state = env.shizuku.state.value
        val tap = Tap.Grant(Access.SHIZUKU)
        val used = Span("  # processes, connections, temperatures, core load, wake locks, battery use, logcat", Tone.DIM)
        set(
            "shizuku",
            when (state) {
                ShizukuState.READY -> listOf(Span("connected", Tone.GOOD), used)
                ShizukuState.CONNECTING -> listOf(Span("connecting…", Tone.DIM), used)
                ShizukuState.NOT_INSTALLED -> listOf(Span("not installed, tap to get it", Tone.LINK, tap), used)
                ShizukuState.NOT_RUNNING -> listOf(Span("not running, tap to open shizuku", Tone.LINK, tap), used)
                ShizukuState.UNSUPPORTED -> listOf(Span("too old, tap to update it", Tone.LINK, tap), used)
                ShizukuState.NO_PERMISSION -> listOf(Span("not allowed, tap to allow", Tone.LINK, tap), used)
            },
        )
        if (state == ShizukuState.NOT_RUNNING || state == ShizukuState.NOT_INSTALLED) {
            note("shizuku gives apps you allow the shell's access. start it in the shizuku app with wireless debugging, adb or root; without root it stops at every reboot.")
        }
    }

    private fun license(asset: String): List<Line> {
        val text = runCatching { env.context.assets.open(asset).bufferedReader().readText() }.getOrDefault("licence text missing")
        return text.lines().map { Line(listOf(Span("  " + it.removePrefix("### "), Tone.DIM)), indent = 2) }
    }

    private companion object {
        const val KEY_WIDTH = 14
        const val HACK = "hack"
        const val UPSTREAM = "https://github.com/AndSni/SysReadout-Launcher"
    }
}
