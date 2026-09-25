package com.asnidev.sysreadoutmonitor.page

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.asnidev.sysreadoutmonitor.log.Access
import com.asnidev.sysreadoutmonitor.log.BleWatch
import com.asnidev.sysreadoutmonitor.log.ProbeReader
import com.asnidev.sysreadoutmonitor.term.Cell
import com.asnidev.sysreadoutmonitor.term.Col
import com.asnidev.sysreadoutmonitor.term.KEY_WIDTH
import com.asnidev.sysreadoutmonitor.term.Line
import com.asnidev.sysreadoutmonitor.term.Meter
import com.asnidev.sysreadoutmonitor.term.Signal
import com.asnidev.sysreadoutmonitor.term.Span
import com.asnidev.sysreadoutmonitor.term.Tap
import com.asnidev.sysreadoutmonitor.term.Thresholds
import com.asnidev.sysreadoutmonitor.term.Tone
import com.asnidev.sysreadoutmonitor.term.cell
import com.asnidev.sysreadoutmonitor.term.comment
import com.asnidev.sysreadoutmonitor.term.link
import com.asnidev.sysreadoutmonitor.term.needs
import com.asnidev.sysreadoutmonitor.term.row
import com.asnidev.sysreadoutmonitor.term.table
import java.util.Locale
import kotlin.math.roundToInt

/**
 * `nmcli device wifi list; bluetoothctl scan on`: every Wi-Fi access point and
 * Bluetooth device in range with its signal, to find the strongest network or
 * a lost device. Tap a Bluetooth device to track it: it moves to the top with
 * a full-width meter and whether it got stronger or weaker. Scans run only
 * while this page is on screen.
 */
class ScanSampler(private val env: Env) : PageSampler {

    private val context = env.context
    private val wm = context.applicationContext.getSystemService(WifiManager::class.java)
    private val ble = BleWatch(context)

    // Wi-Fi: Android lets an app start 4 scans per 2 minutes, so ask every 30 s and keep what each scan saw.
    private var scanAskedAt = 0L
    @Volatile private var fresh = false
    private val scans = ArrayDeque<Map<String, Int>>() // bssid -> dBm, newest last
    private var receiving = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            fresh = true
            env.poke()
        }
    }

    // Bluetooth: the smoothed reading shown last time, for the stronger/weaker arrow.
    private val shown = HashMap<String, Double>()

    override fun start() {
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiving = true
        fresh = true // take what the last system scan found, if recent
    }

    override fun stop() {
        if (receiving) runCatching { context.unregisterReceiver(receiver) }
        receiving = false
        ble.stop()
        scans.clear()
        shown.clear()
        scanAskedAt = 0L
    }

    override suspend fun sample(): List<Line> {
        val out = ArrayList<Line>()
        out += wifi()
        out += bluetooth()
        return out
    }

    // --- Wi-Fi ---

    @SuppressLint("MissingPermission") // gated on the location permission
    private fun wifi(): List<Line> {
        val out = ArrayList<Line>()
        env.gate(Access.LOCATION)?.let { return listOf(comment("wi-fi"), it) }
        if (!wm.isWifiEnabled) {
            return listOf(comment("wi-fi"), needs("wi-fi is off, tap to open wi-fi settings", Tap.Intent(Settings.ACTION_WIFI_SETTINGS)))
        }
        if (!locationOn()) {
            return listOf(
                comment("wi-fi"),
                needs("location is off, and android shows no wi-fi scan results without it; tap to turn it on", Tap.Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)),
            )
        }
        val now = SystemClock.elapsedRealtime()
        if (now - scanAskedAt >= WIFI_SCAN_MS) {
            scanAskedAt = now
            @Suppress("DEPRECATION") // still the only way to ask; Android throttles it
            runCatching { wm.startScan() }
        }
        // Android may refuse a scan (4 per 2 minutes); then the last results show, with their age.
        val results = runCatching { wm.scanResults }.getOrNull().orEmpty()
        if (fresh) {
            fresh = false
            if (results.isNotEmpty()) {
                scans.addLast(results.associate { it.BSSID to it.level })
                while (scans.size > HISTORY) scans.removeFirst()
            }
        }
        @Suppress("DEPRECATION") // connectionInfo still gives the live RSSI of the network in use
        val info = runCatching { wm.connectionInfo }.getOrNull()
        val inUse = info?.bssid?.takeIf { info.networkId != -1 }
        val newest = results.maxOfOrNull { it.timestamp }?.let { (now - it / 1000) / 1000 } // timestamp: µs since boot
        val ssids = results.mapNotNull(::ssid).toSet().size

        out += comment(
            "wi-fi · ${plural(ssids, "network")}, ${plural(results.size, "access point")}" +
                (newest?.let { " · scanned ${age(it)} ago" } ?: " · scanning…"),
        )
        out += Line(listOf(Span("# android allows 4 scans in 2 minutes, so the list updates about every 30 s; the network in use (*) updates live. ±dB is how much a signal wanders, SEEN how many recent scans found it.", Tone.DIM)), indent = 2)
        if (results.isEmpty()) return out + note("nothing found yet")

        val rows = results.map { r ->
            val live = if (r.BSSID == inUse) info?.rssi?.takeIf { it > -127 } else null
            r to (live ?: r.level)
        }.sortedByDescending { it.second }
        out += table(
            listOf(Col(" "), Col("dBm", right = true), Col("%", right = true), Col("±dB", right = true), Col("SEEN", right = true), Col("SIGNAL"), Col("BAND"), Col("CH", right = true), Col("SSID")),
            rows.map { (r, dbm) ->
                val history = scans.mapNotNull { it[r.BSSID] }
                val pct = Signal.percent(dbm)
                val tone = Thresholds.dbm(dbm)
                listOf(
                    cell(if (r.BSSID == inUse) "*" else " ", Tone.GOOD),
                    cell(dbm.toString()),
                    cell(pct.toString(), tone),
                    cell(Signal.spread(history)?.let { String.format(Locale.US, "%.1f", it) } ?: "-", Tone.DIM),
                    cell(if (scans.isEmpty()) "-" else "${history.size}/${scans.size}", Tone.DIM),
                    cell(Signal.bars(pct, 8), tone),
                    cell(band(r.frequency)),
                    cell(ProbeReader.channel(r.frequency).toString()),
                    ssid(r)?.let { cell(it) } ?: cell("(hidden)", Tone.DIM),
                )
            },
        )
        return out
    }

    private fun plural(n: Int, what: String) = "$n $what" + if (n == 1) "" else "s"

    private fun age(sec: Long) = if (sec < 120) "${sec}s" else "${sec / 60} min"

    private fun ssid(r: ScanResult): String? {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) r.wifiSsid?.toString() else @Suppress("DEPRECATION") r.SSID
        return raw?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    }

    private fun band(mhz: Int) = when {
        mhz < 3000 -> "2.4G"
        mhz < 5925 -> "5G"
        else -> "6G"
    }

    private fun locationOn(): Boolean = runCatching {
        val lm = context.getSystemService(LocationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled else lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }.getOrDefault(true)

    // --- Bluetooth ---

    private fun bluetooth(): List<Line> {
        val out = ArrayList<Line>()
        out += Line.BLANK
        if (!ble.present) return out + comment("bluetooth") + note("this phone has no bluetooth")
        env.gate(Access.NEARBY)?.let { return out + comment("bluetooth") + it }
        if (!ble.enabled) {
            return out + comment("bluetooth") + needs("bluetooth is off, tap to turn it on", Tap.Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !locationOn()) {
            return out + comment("bluetooth") +
                needs("location is off; android 11 and older find no bluetooth devices without it, tap to turn it on", Tap.Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
        ble.start()
        ble.track(env.tracked)
        ble.poll()

        val now = SystemClock.elapsedRealtime()
        val devices = ble.devices().filter { now - it.seenAt < GONE_MS }.associateBy { it.address }
        val paired = ble.paired()
        val pairedAddresses = paired.map { it.first.address }.toSet()
        val failure = ble.failure

        out += comment(
            "bluetooth · " + (if (ble.scanning) "scanning" else "not scanning" + (failure?.let { " (error $it)" } ?: "")) +
                " · ${devices.size} in range · tap a name to track it",
        )

        env.tracked?.let { address ->
            val d = devices[address]
            val name = d?.name ?: paired.firstOrNull { it.first.address == address }?.let { ble.name(it.first) } ?: address
            out += tracked(name, address, d, now)
        }

        if (paired.isNotEmpty()) {
            out += comment("paired")
            out += table(
                listOf(Col("dBm", right = true), Col("%", right = true), Col(" "), Col("SIGNAL"), Col("NAME")),
                paired.sortedByDescending { devices[it.first.address]?.smooth ?: -999.0 }.map { (device, connected) ->
                    val d = devices[device.address]
                    val name = ble.name(device) ?: device.address
                    if (d == null) {
                        listOf(
                            cell("-", Tone.DIM), cell("-", Tone.DIM), cell(" "),
                            // Connected devices often stop advertising: tracking reads them over the link.
                            cell(if (connected) "linked" else "unseen", Tone.DIM),
                            link(name, Tap.Track(device.address)),
                        )
                    } else deviceRow(d, name)
                },
            )
        }

        val others = devices.values.filter { it.address !in pairedAddresses }.sortedByDescending { it.smooth }
        val named = others.filter { it.name != null }
        val unnamed = others.filter { it.name == null }
        out += comment("nearby" + if (others.isEmpty()) "" else " · ${named.size} named, ${unnamed.size} without a name")
        if (others.isEmpty()) {
            out += note(if (ble.scanning) "nothing heard yet" else "not scanning")
        } else {
            out += table(
                listOf(Col("dBm", right = true), Col("%", right = true), Col(" "), Col("SIGNAL"), Col("NAME")),
                (named + unnamed.take(UNNAMED_SHOWN)).map { deviceRow(it, it.name ?: "(no name) ${it.address.takeLast(8)}") },
            )
            if (unnamed.size > UNNAMED_SHOWN) out += note("and ${unnamed.size - UNNAMED_SHOWN} more without a name")
        }
        // Remember what was shown, for the next sample's arrows.
        devices.values.forEach { shown[it.address] = it.smooth }
        return out
    }

    private fun deviceRow(d: BleWatch.Device, name: String): List<Cell> {
        val dbm = d.smooth.roundToInt()
        val pct = Signal.percent(dbm)
        val tone = Thresholds.dbm(dbm)
        val (arrow, arrowTone) = trend(d)
        return listOf(
            cell(dbm.toString()),
            cell(pct.toString(), tone),
            cell(arrow, arrowTone),
            cell(Signal.bars(pct, 8), tone),
            link(name, Tap.Track(d.address)),
        )
    }

    /** ↑ stronger (closer), ↓ weaker, = about the same, since the last sample. */
    private fun trend(d: BleWatch.Device): Pair<String, Tone> {
        val was = shown[d.address] ?: return " " to Tone.FG
        val delta = d.smooth - was
        return when {
            delta >= 1.5 -> "↑" to Tone.GOOD
            delta <= -1.5 -> "↓" to Tone.WARN
            else -> "=" to Tone.DIM
        }
    }

    /** The tracked device on top: big meter, dBm, %, trend, and how the reading was taken. */
    private fun tracked(name: String, address: String, d: BleWatch.Device?, now: Long): List<Line> {
        val out = ArrayList<Line>()
        out += comment("tracking · walk around: the signal gets stronger as you get closer")
        out += row("track", listOf(Span(name, Tone.LINK, Tap.Track(null)), Span("  tap to stop", Tone.DIM)))
        if (d == null) {
            out += row("", listOf(Span("not heard yet. a device connected to another phone, switched off or out of range stays silent.", Tone.DIM)))
            return out
        }
        val dbm = d.smooth.roundToInt()
        val pct = Signal.percent(dbm)
        val tone = Thresholds.dbm(dbm)
        val (arrow, arrowTone) = trend(d)
        val age = (now - d.seenAt) / 1000
        out += row(
            "signal",
            listOf(
                Span("$dbm dBm  "), Span("$pct%", tone), Span("  ${Signal.words(dbm)}  "), Span(arrow, arrowTone),
                Span("  " + (if (d.viaLink) "read over its connection" else "heard advertising") + if (age > 1) ", ${age}s ago" else "", Tone.DIM),
            ),
        )
        out += Line(listOf(Span(" ".repeat(KEY_WIDTH))), indent = KEY_WIDTH, meter = Meter(pct / 100f, tone, "$pct%"))
        if (address == env.tracked && !ble.trackingLink && !d.viaLink) {
            out += row("", listOf(Span("readings come from its adverts, a few a second; hold the phone still for a moment at each spot.", Tone.DIM)))
        }
        return out
    }

    private companion object {
        const val WIFI_SCAN_MS = 30_000L
        const val HISTORY = 10
        const val GONE_MS = 30_000L
        const val UNNAMED_SHOWN = 10
    }
}
