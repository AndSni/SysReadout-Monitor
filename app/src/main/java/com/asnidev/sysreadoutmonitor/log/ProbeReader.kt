package com.asnidev.sysreadoutmonitor.log

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.location.LocationManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.os.storage.StorageManager
import android.provider.Settings
import android.system.Os
import android.system.OsConstants
import android.telephony.CellIdentityNr
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrength
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthWcdma
import android.telephony.TelephonyManager
import android.view.Display
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.asnidev.sysreadoutmonitor.monitor.NotifLog
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt

data class SysState(val net: String, val power: String, val level: Int, val thermal: String, val lowMemory: Boolean)

/**
 * Turns probe ids into log rows. Everything here works without special
 * permissions; a row whose data the device won't give out is dropped.
 * Not thread-safe: call from one coroutine.
 */
class ProbeReader(private val context: Context) {

    private val am = context.getSystemService(ActivityManager::class.java)
    private val bm = context.getSystemService(BatteryManager::class.java)
    private val pm = context.getSystemService(PowerManager::class.java)
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val wm = context.applicationContext.getSystemService(WifiManager::class.java)
    private val tm = context.getSystemService(TelephonyManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val storage = context.getSystemService(StorageManager::class.java)
    private val displays = context.getSystemService(DisplayManager::class.java)
    val sensors = SensorWatch(context)
    private val location = LocationWatch(context)
    private val steps = StepWatch(context)
    private val bt = BtWatch(context)
    private val phone = PhoneWatch(context)
    private var scanAt = 0L

    /** Supplies the "apps" row; set by the UI, which owns the app list. */
    var appsSummary: () -> String? = { null }

    /** Rows whose data the engine gathers itself (usage access, Shizuku). */
    var external: (String) -> String? = { null }

    // Refreshed once per sample() call and shared by the rows that need them.
    private var battery: Intent? = null
    private var meminfo: Map<String, Long> = emptyMap()

    private var lastRx = TrafficStats.getTotalRxBytes()
    private var lastTx = TrafficStats.getTotalTxBytes()
    private var lastNetAt = SystemClock.elapsedRealtime()
    private var rxRate = 0L
    private var txRate = 0L

    private var headroom = Float.NaN
    private var headroomAt = 0L

    /** Starts the listeners the enabled rows need and stops the rest. */
    fun updateWatchers(rows: List<String>) {
        sensors.update(ambient = "env" in rows, rotation = "compass" in rows)
        location.update(gps = "gps" in rows || "gnss" in rows)
        steps.update("steps" in rows)
        bt.update("bt" in rows)
        phone.update("link" in rows)
    }

    fun stopWatchers() = updateWatchers(emptyList())

    /** (id, value) for each of [ids] that has a value, in order. */
    fun values(ids: List<String>): List<Pair<String, String>> {
        battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        meminfo = readMeminfo()
        updateRates()
        return ids.mapNotNull { id ->
            runCatching { read(id) }.getOrNull()?.let { id to it }
        }
    }

    // --- raw numbers for meters and colours; call after values() so they share its readings ---

    fun memory(): ActivityManager.MemoryInfo = ActivityManager.MemoryInfo().also(am::getMemoryInfo)

    /** (used, total) swap in bytes, or null when the kernel doesn't say. */
    fun swapBytes(): Pair<Long, Long>? {
        val total = meminfo["SwapTotal"] ?: return null
        return (total - (meminfo["SwapFree"] ?: total)) * 1024 to total * 1024
    }

    fun batteryLevel(): Int = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    fun batteryTempC(): Double = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0

    /** Current clock of each possible core in kHz; null when offline or not a real reading (emulators). */
    fun coreClocks(): List<Long?> {
        val possible = cpuCount("/sys/devices/system/cpu/possible") ?: Runtime.getRuntime().availableProcessors()
        return (0 until possible).map { core ->
            readText("/sys/devices/system/cpu/cpu$core/cpufreq/scaling_cur_freq")?.trim()?.toLongOrNull()?.takeIf { it >= 100_000 }
        }
    }

    /** Highest clock of each possible core in kHz (for clock meters without Shizuku); null when unknown. */
    fun coreMaxClocks(): List<Long?> {
        val possible = cpuCount("/sys/devices/system/cpu/possible") ?: Runtime.getRuntime().availableProcessors()
        return (0 until possible).map { core ->
            listOf("cpuinfo_max_freq", "scaling_max_freq").firstNotNullOfOrNull { f ->
                readText("/sys/devices/system/cpu/cpu$core/cpufreq/$f")?.trim()?.toLongOrNull()?.takeIf { it >= 100_000 }
            }
        }
    }

    /** (available, total) bytes of the file system holding [dir]. */
    fun storageBytes(dir: File): Pair<Long, Long> = StatFs(dir.path).let { it.availableBytes to it.totalBytes }

    fun satellites(): List<LocationWatch.Satellite> = location.satellites()

    /** Android's Wi-Fi signal level scaled to 0..4, as the wifi row words it. */
    fun wifiLevel(rssi: Int): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        wm.calculateSignalLevel(rssi) * 4 / wm.maxSignalLevel.coerceAtLeast(1)
    } else {
        @Suppress("DEPRECATION")
        WifiManager.calculateSignalLevel(rssi, 5)
    }

    /** Mounted removable volumes (SD cards, USB drives) with their user-visible names. */
    fun removableVolumes(): List<Pair<String, File>> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            storage.storageVolumes.filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
                .mapNotNull { v -> v.directory?.let { v.getDescription(context) to it } }
        } else {
            context.getExternalFilesDirs(null).drop(1).filterNotNull().map { "removable" to it }
        }

    private fun read(id: String): String? {
        val needs = ProbeCatalog.byId[id]?.needs
        if (needs != null && needs.isRuntime && !needs.runtimeGranted(context) && id != "sun") return "needs ${needs.tag} permission"
        if (needs == Access.NOTIFICATIONS && !NotifLog.connected) return "needs notification access"
        return value(id)
    }

    private fun value(id: String): String? = when (id) {
        "time" -> time()
        "up" -> up()
        "os" -> "Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT} · patch ${Build.VERSION.SECURITY_PATCH}"
        "kern" -> System.getProperty("os.version")
        "dev" -> "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})"
        "self" -> self()
        "cpu" -> cpu()
        "soc" -> soc()
        "mem" -> mem()
        "swap" -> swap()
        "therm" -> therm()
        "bat" -> bat()
        "pwr" -> pwr()
        "chg" -> chg()
        "mode" -> mode()
        "net" -> net()
        "wifi" -> wifi()
        "cell" -> cell()
        "ip" -> ip()
        "data" -> "boot ↓${bytes(TrafficStats.getTotalRxBytes())} ↑${bytes(TrafficStats.getTotalTxBytes())}" +
            "  mobile ↓${bytes(TrafficStats.getMobileRxBytes())} ↑${bytes(TrafficStats.getMobileTxBytes())}"
        "fs" -> fs(Environment.getDataDirectory())
        "sd" -> sd()
        "disp" -> disp()
        "audio" -> audio()
        "alarm" -> alarm()
        "env" -> env()
        "apps" -> appsSummary()
        "props" -> props()
        "boot" -> boot()
        "gpu" -> GpuInfo.describe(context) ?: "n/a"
        "vm" -> vm()
        "radio" -> radio()
        "compass" -> compass()
        "moon" -> moon()
        "rf" -> rf()
        "link" -> link()
        "tower" -> tower()
        "ssid" -> ssid()
        "aps" -> aps()
        "bt" -> bt.line()
        "debug" -> debug()
        "gps" -> location.fixLine()
        "gnss" -> location.satellitesLine()
        "sun" -> sun()
        "steps" -> steps.line()
        "ntf" -> "${NotifLog.active} showing  ${NotifLog.todayTotal()} today"
        else -> external(id)
    }

    private fun time(): String {
        val now = ZonedDateTime.now()
        return "${now.format(DateTimeFormatter.ofPattern("HH:mm:ss"))}  UTC${now.offset.id.replace("Z", "+00:00")}" +
            "  epoch ${now.toEpochSecond()}"
    }

    private fun up(): String {
        val elapsed = SystemClock.elapsedRealtime()
        val awake = SystemClock.uptimeMillis() * 100 / elapsed.coerceAtLeast(1)
        return "${duration(elapsed)}  awake $awake%"
    }

    private fun self(): String {
        val status = readText("/proc/self/status")?.lines().orEmpty()
        fun field(name: String) = status.firstOrNull { it.startsWith("$name:") }
            ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()
        val rss = field("VmRSS")?.let { bytes(it * 1024) } ?: "?"
        val heap = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        val cpu = selfCpu()?.let { String.format(Locale.US, "  cpu %.1f%%", it) } ?: ""
        return "pid ${Process.myPid()}$cpu  rss $rss  heap ${bytes(heap)}  thr ${field("Threads") ?: "?"}"
    }

    private var selfTicks = -1L
    private var selfAt = 0L

    /** This process's CPU use since the last call, in % of one core (utime + stime from /proc/self/stat). */
    private fun selfCpu(): Double? {
        // Fields after the ")" that ends the process name: utime and stime are the 12th and 13th.
        val f = readText("/proc/self/stat")?.substringAfterLast(')')?.trim()?.split(' ') ?: return null
        val ticks = (f.getOrNull(11)?.toLongOrNull() ?: return null) + (f.getOrNull(12)?.toLongOrNull() ?: return null)
        val now = SystemClock.elapsedRealtime()
        val was = selfTicks
        val wasAt = selfAt
        selfTicks = ticks
        selfAt = now
        if (was < 0 || now <= wasAt) return null
        val hz = Os.sysconf(OsConstants._SC_CLK_TCK).coerceAtLeast(1)
        return (ticks - was) * 1000.0 / hz / (now - wasAt) * 100
    }

    private fun cpu(): String {
        val cores = cpuCount("/sys/devices/system/cpu/online") ?: Runtime.getRuntime().availableProcessors()
        val possible = cpuCount("/sys/devices/system/cpu/possible") ?: cores
        val freqs = (0 until possible).mapNotNull {
            readText("/sys/devices/system/cpu/cpu$it/cpufreq/scaling_cur_freq")?.trim()?.toLongOrNull()
        }
        val governor = readText("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor")?.trim()
        // Emulators report nonsense like 1 kHz; only show clocks that look real.
        val real = freqs.filter { it >= 100_000 }
        val clock = if (real.isEmpty()) "" else "  ${ghz(real.min())}–${ghz(real.max())}GHz"
        return "$cores/$possible cores$clock${governor?.let { "  $it" } ?: ""}"
    }

    private fun soc(): String {
        val chip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".trim().takeUnless { it.equals("unknown unknown", true) }
        } else null
        return "${chip ?: Build.HARDWARE} · ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}"
    }

    private fun mem(): String {
        val mi = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        val usedPct = (mi.totalMem - mi.availMem) * 100 / mi.totalMem.coerceAtLeast(1)
        return "${bytes(mi.availMem)}/${bytes(mi.totalMem)} avail  $usedPct% used" + if (mi.lowMemory) "  LOW" else ""
    }

    private fun swap(): String? {
        val total = meminfo["SwapTotal"] ?: return null
        if (total == 0L) return "none"
        val used = total - (meminfo["SwapFree"] ?: total)
        return "${bytes(used * 1024)}/${bytes(total * 1024)} used  ${used * 100 / total}%"
    }

    private fun therm(): String {
        val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            THERMAL.getOrElse(pm.currentThermalStatus) { "?" }
        } else "n/a"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // The platform returns NaN when polled more than about once a second.
            val now = SystemClock.elapsedRealtime()
            if (now - headroomAt > 1_500) {
                headroom = pm.getThermalHeadroom(0)
                headroomAt = now
            }
        }
        val head = if (headroom.isNaN()) "n/a" else String.format(Locale.US, "%.2f", headroom)
        return "$status  headroom $head  bat ${batteryTemp()}°C"
    }

    private fun bat(): String {
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = when (battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not charging"
            else -> "discharging"
        }
        val health = when (battery?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "OVERHEAT"
            BatteryManager.BATTERY_HEALTH_DEAD -> "DEAD"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "OVERVOLT"
            BatteryManager.BATTERY_HEALTH_COLD -> "cold"
            else -> "?"
        }
        return "$level%  $status  ${batteryTemp()}°C  health $health"
    }

    private fun pwr(): String {
        val mv = battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val ma = currentMa()
        val watts = mv / 1000.0 * ma / 1000.0
        return String.format(Locale.US, "%.2fV  %dmA  %.2fW  %s", mv / 1000.0, ma, watts, powerSource())
    }

    private fun powerSource(): String = when (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
        BatteryManager.BATTERY_PLUGGED_AC -> "ac"
        BatteryManager.BATTERY_PLUGGED_USB -> "usb"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
        8 -> "dock" // BATTERY_PLUGGED_DOCK, API 33
        else -> "battery"
    }

    private fun chg(): String {
        val counter = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val parts = mutableListOf<String>()
        if (counter > 0) {
            parts += "${counter / 1000}mAh"
            if (level > 0) parts += "~${counter / 10 / level}mAh full"
        }
        battery?.getIntExtra("android.os.extra.CYCLE_COUNT", -1)?.takeIf { it >= 0 }?.let { parts += "cycles $it" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            bm.computeChargeTimeRemaining().takeIf { it > 0 }?.let { parts += "full in ${shortDuration(it)}" }
        }
        return parts.joinToString("  ").ifEmpty { "n/a" }
    }

    private fun mode(): String {
        val dnd = notifications.currentInterruptionFilter.let {
            it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        }
        val ringer = when (audio.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "normal"
        }
        return "saver ${onOff(pm.isPowerSaveMode)}  doze ${onOff(pm.isDeviceIdleMode)}  dnd ${onOff(dnd)}  ringer $ringer"
    }

    private fun net(): String {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val metered = if (caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) " metered" else ""
        return "${netType()}$metered  ↓${bytes(rxRate)}/s  ↑${bytes(txRate)}/s"
    }

    private fun netType(): String {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "offline"
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "eth"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bt"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
        return if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && type != "vpn") "$type+vpn" else type
    }

    /** Values the engine watches for changes. Call after [sample]. */
    fun state(): SysState = SysState(
        net = netType(),
        power = powerSource(),
        level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
        thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) THERMAL.getOrElse(pm.currentThermalStatus) { "?" } else "n/a",
        lowMemory = ActivityManager.MemoryInfo().also(am::getMemoryInfo).lowMemory,
    )

    @Suppress("DEPRECATION") // connectionInfo still returns RSSI/speed/band without location access
    private fun wifi(): String? {
        if (!wm.isWifiEnabled) return "off"
        val info = wm.connectionInfo ?: return null
        if (info.networkId == -1 && info.rssi <= -127) return "on, not connected"
        val level = wifiLevel(info.rssi)
        val band = when {
            info.frequency <= 0 -> ""
            info.frequency < 3000 -> "  2.4GHz"
            info.frequency < 5925 -> "  5GHz"
            else -> "  6GHz"
        }
        return "${info.rssi}dBm ${strength(level)}  ${info.linkSpeed}Mbps$band"
    }

    private fun cell(): String? {
        if (tm.phoneType == TelephonyManager.PHONE_TYPE_NONE) return null
        val operator = tm.networkOperatorName.ifBlank { "no service" } +
            // Readable as a system property without the phone permission.
            (SystemProps["gsm.network.type"]?.substringBefore(',')?.takeIf { it != "Unknown" }?.let { " $it" } ?: "")
        val strength = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { tm.signalStrength?.cellSignalStrengths?.firstOrNull() }.getOrNull()
        } else null
        val signal = strength?.let { s ->
            (if (s.dbm != Int.MAX_VALUE) "  ${s.dbm}dBm" else "") + " ${strength(s.level)}"
        } ?: "  no signal"
        return operator + signal +
            if (tm.isNetworkRoaming) "  roaming" else ""
    }

    private fun ip(): String {
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return "offline"
        val v4 = lp.linkAddresses.firstOrNull { it.address is Inet4Address }
        val v6 = lp.linkAddresses.count { it.address is Inet6Address }
        // Prefer the IPv4 gateway; the IPv6 one is usually an unhelpful link-local fe80:: address.
        val gateways = lp.routes.filter { it.isDefaultRoute && it.gateway?.isAnyLocalAddress == false }.mapNotNull { it.gateway }
        val gw = (gateways.firstOrNull { it is Inet4Address } ?: gateways.firstOrNull())?.hostAddress
        return "${v4 ?: "no ipv4"}" + (gw?.let { "  gw $it" } ?: "") + if (v6 > 0) "  +$v6 v6" else ""
    }

    private fun fs(dir: File): String {
        val fs = StatFs(dir.path)
        val usedPct = (fs.totalBytes - fs.availableBytes) * 100 / fs.totalBytes.coerceAtLeast(1)
        return "${bytes(fs.availableBytes)}/${bytes(fs.totalBytes)} free  $usedPct% used"
    }

    private fun sd(): String? {
        val dirs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            storage.storageVolumes.filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
                .mapNotNull { it.directory }
        } else {
            // Secondary app-specific dirs live on removable volumes.
            context.getExternalFilesDirs(null).drop(1).filterNotNull()
        }
        return dirs.firstOrNull()?.let { fs(it) } ?: "none mounted"
    }

    private fun disp(): String {
        val display = displays.getDisplay(Display.DEFAULT_DISPLAY)
        val mode = display.mode
        val dpi = context.resources.displayMetrics.densityDpi
        val cr = context.contentResolver
        val brightness = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, -1)
        val auto = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) ==
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        val bright = if (brightness < 0) "" else "  bright ${brightness * 100 / 255}%" + if (auto) " auto" else ""
        return "${mode.physicalWidth}x${mode.physicalHeight} @${display.refreshRate.toInt()}Hz  ${dpi}dpi$bright"
    }

    private fun audio(): String {
        fun vol(stream: Int) = "${audio.getStreamVolume(stream)}/${audio.getStreamMaxVolume(stream)}"
        val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
        val out = when {
            outputs.any { it == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it == 26 /* BLE_HEADSET */ } -> "bluetooth"
            outputs.any { it == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it == AudioDeviceInfo.TYPE_WIRED_HEADSET } -> "wired"
            outputs.any { it == AudioDeviceInfo.TYPE_USB_HEADSET } -> "usb"
            else -> "speaker"
        }
        return "media ${vol(AudioManager.STREAM_MUSIC)}  ring ${vol(AudioManager.STREAM_RING)}  → $out" +
            if (audio.isMusicActive) "  playing" else ""
    }

    private fun alarm(): String {
        val next = alarms.nextAlarmClock?.triggerTime ?: return "none"
        val at = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(next), java.time.ZoneId.systemDefault())
        return "${at.format(DateTimeFormatter.ofPattern("EEE HH:mm", Locale.US)).lowercase()}" +
            "  in ${shortDuration(next - System.currentTimeMillis())}"
    }

    private fun env(): String {
        if (!sensors.hasAmbient) return "no ambient sensors"
        // Ranges drop readings no real sensor gives (emulators and cheap sensors report junk).
        fun reading(type: Int, range: ClosedFloatingPointRange<Float>) = sensors.value(type)?.takeIf { it in range }
        val parts = mutableListOf<String>()
        reading(Sensor.TYPE_LIGHT, 0f..200_000f)?.let { parts += "${it.toInt()}lx" }
        reading(Sensor.TYPE_PRESSURE, 300f..1_100f)?.let {
            parts += String.format(Locale.US, "%.1fhPa", it)
            parts += "alt ${SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, it).toInt()}m"
        }
        reading(Sensor.TYPE_AMBIENT_TEMPERATURE, -60f..100f)?.let { parts += String.format(Locale.US, "%.1f°C", it) }
        reading(Sensor.TYPE_RELATIVE_HUMIDITY, 0f..100f)?.let { parts += "${it.toInt()}%rh" }
        return parts.joinToString("  ").ifEmpty { "waiting for sensors" }
    }

    private fun props(): String {
        val p = SystemProps
        val parts = listOfNotNull(
            p["ro.boot.slot_suffix"]?.let { "slot ${it.removePrefix("_")}" },
            p["ro.boot.verifiedbootstate"]?.let { "vbs $it" },
            p["ro.boot.flash.locked"]?.let { if (it == "1") "locked" else "UNLOCKED" },
            if (p["ro.treble.enabled"] == "true") "treble" else null,
            p["ro.product.first_api_level"]?.let { "first api $it" },
            p["ro.build.type"],
        )
        return parts.joinToString(" · ").ifEmpty { "n/a" }
    }

    private fun boot(): String {
        val count = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        val since = ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime()),
            java.time.ZoneId.systemDefault(),
        ).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        return (if (count >= 0) "#$count  " else "") + "since $since"
    }

    private fun vm(): String? {
        if (meminfo.isEmpty()) return null
        fun kb(key: String) = meminfo[key]?.let { bytes(it * 1024) } ?: "?"
        return "cached ${kb("Cached")}  active ${kb("Active")}  inactive ${kb("Inactive")}  dirty ${kb("Dirty")}  slab ${kb("Slab")}"
    }

    private fun radio(): String {
        val cr = context.contentResolver
        val air = Settings.Global.getInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        val bt = runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled }.getOrNull()
        val nfc = runCatching { NfcAdapter.getDefaultAdapter(context)?.isEnabled }.getOrNull()
        val loc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.getSystemService(LocationManager::class.java).isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            Settings.Secure.getInt(cr, Settings.Secure.LOCATION_MODE, 0) != 0
        }
        return "air ${onOff(air)}  bt ${bt?.let(::onOff) ?: "n/a"}  nfc ${nfc?.let(::onOff) ?: "none"}  loc ${onOff(loc)}"
    }

    private fun compass(): String {
        if (!sensors.hasRotation) return "no orientation sensor"
        val (azimuth, pitch, roll) = sensors.orientation() ?: return "waiting for sensor"
        val cardinal = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[((azimuth + 22.5f) / 45f).toInt() % 8]
        return String.format(Locale.US, "%03d° %-2s  pitch %+d°  roll %+d°", azimuth.toInt(), cardinal, pitch.toInt(), roll.toInt())
    }

    private fun moon(): String {
        val days = (System.currentTimeMillis() - NEW_MOON_2000) / 86_400_000.0
        val age = ((days % SYNODIC) + SYNODIC) % SYNODIC
        val lit = ((1 - cos(2 * PI * age / SYNODIC)) / 2 * 100).roundToInt()
        val phase = when {
            age < 1.85 -> "new"
            age < 5.54 -> "waxing crescent"
            age < 9.23 -> "first quarter"
            age < 12.92 -> "waxing gibbous"
            age < 16.61 -> "full"
            age < 20.30 -> "waning gibbous"
            age < 23.99 -> "last quarter"
            age < 27.68 -> "waning crescent"
            else -> "new"
        }
        val toFull = ((SYNODIC / 2 - age) % SYNODIC + SYNODIC) % SYNODIC
        return "$phase $lit%  full in ${toFull.roundToInt()}d"
    }

    // --- radio, position, device state ---

    private fun rf(): String {
        if (tm.phoneType == TelephonyManager.PHONE_TYPE_NONE) return "no mobile radio"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "needs Android 10"
        val all = runCatching { tm.signalStrength?.cellSignalStrengths }.getOrNull().orEmpty()
        return all.joinToString("  ") { describe(it) }.ifEmpty { "no signal" }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun describe(s: CellSignalStrength): String = when (s) {
        is CellSignalStrengthNr -> "NR rsrp ${v(s.ssRsrp)} rsrq ${v(s.ssRsrq)} sinr ${v(s.ssSinr)}"
        is CellSignalStrengthLte -> "LTE rsrp ${v(s.rsrp)} rsrq ${v(s.rsrq)} snr ${v(s.rssnr)}"
        is CellSignalStrengthWcdma -> "WCDMA rscp ${v(s.dbm)}" + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) " ecno ${v(s.ecNo)}" else ""
        is CellSignalStrengthGsm -> "GSM rssi ${v(s.dbm)}"
        else -> "${v(s.dbm)}dBm"
    }

    @SuppressLint("MissingPermission") // read() checked the phone permission
    private fun link(): String {
        val type = runCatching { networkType(tm.dataNetworkType) }.getOrDefault("?")
        val bandwidths = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { tm.serviceState?.cellBandwidths }.getOrNull()?.filter { it > 0 }.orEmpty()
        } else emptyList()
        return type + (phone.override?.let { " ($it)" } ?: "") +
            (if (bandwidths.isEmpty()) "" else "  bw ${bandwidths.joinToString("+") { "${it / 1000}" }}MHz") +
            (if (tm.isNetworkRoaming) "  roaming" else "") +
            (tm.simOperatorName.takeIf { it.isNotBlank() }?.let { "  sim $it" } ?: "")
    }

    @SuppressLint("MissingPermission") // read() checked the location permission
    private fun tower(): String {
        val cell = runCatching { tm.allCellInfo }.getOrNull()?.firstOrNull { it.isRegistered } ?: return "no serving cell"
        fun bands(b: IntArray?, prefix: String = "") = b?.takeIf { it.isNotEmpty() }?.joinToString("+") { "$prefix$it" }?.let { " band $it" } ?: ""
        val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        return when {
            cell is CellInfoLte -> cell.cellIdentity.let { id ->
                "LTE" + (if (modern) bands(id.bands) else "") +
                    " earfcn ${v(id.earfcn)} pci ${v(id.pci)} tac ${v(id.tac)} ci ${v(id.ci)}"
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cell is CellInfoNr -> (cell.cellIdentity as CellIdentityNr).let { id ->
                "NR" + (if (modern) bands(id.bands, "n") else "") + " nrarfcn ${v(id.nrarfcn)} pci ${v(id.pci)} tac ${v(id.tac)}"
            }
            cell is CellInfoWcdma -> cell.cellIdentity.let { "WCDMA uarfcn ${v(it.uarfcn)} psc ${v(it.psc)} lac ${v(it.lac)} cid ${v(it.cid)}" }
            cell is CellInfoGsm -> cell.cellIdentity.let { "GSM arfcn ${v(it.arfcn)} bsic ${v(it.bsic)} lac ${v(it.lac)} cid ${v(it.cid)}" }
            else -> cell.javaClass.simpleName.removePrefix("CellInfo")
        }
    }

    @Suppress("DEPRECATION")
    private fun ssid(): String {
        if (!wm.isWifiEnabled) return "wifi off"
        val info = wm.connectionInfo ?: return "not connected"
        if (info.networkId == -1 && info.rssi <= -127) return "not connected"
        val name = info.ssid?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
            ?: return "name hidden: location must be on"
        val standard = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) WIFI_STANDARDS[info.wifiStandard] else null
        return "\"$name\"  ${info.bssid ?: "?"}  ch ${channel(info.frequency)}" + (standard?.let { "  $it" } ?: "")
    }

    @SuppressLint("MissingPermission") // read() checked the location permission
    private fun aps(): String {
        val now = SystemClock.elapsedRealtime()
        if (now - scanAt > 120_000L) { // Android throttles scans anyway; ask at most every 2 min
            scanAt = now
            @Suppress("DEPRECATION")
            runCatching { wm.startScan() }
        }
        val results = runCatching { wm.scanResults }.getOrNull().orEmpty()
        if (results.isEmpty()) return "none seen yet"
        val g24 = results.count { it.frequency < 3000 }
        val g5 = results.count { it.frequency in 3000..5924 }
        val g6 = results.count { it.frequency >= 5925 }
        val best = results.maxBy { it.level }
        @Suppress("DEPRECATION")
        val bestName = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) best.wifiSsid?.toString() else best.SSID)
            ?.removeSurrounding("\"")?.ifBlank { null } ?: "hidden"
        return "${results.size} nearby (2.4G $g24 · 5G $g5" + (if (g6 > 0) " · 6G $g6" else "") + ")" +
            "  best \"$bestName\" ${best.level}dBm"
    }

    private fun debug(): String {
        val cr = context.contentResolver
        val dev = Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        val adb = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 1
        val wireless = Settings.Global.getInt(cr, "adb_wifi_enabled", 0) == 1
        val usb = context.registerReceiver(null, IntentFilter("android.hardware.usb.action.USB_STATE"))
        val usbText = when {
            usb == null -> "?"
            !usb.getBooleanExtra("connected", false) -> "unplugged"
            else -> "connected" + listOf("mtp", "ptp", "rndis", "midi", "adb").filter { usb.getBooleanExtra(it, false) }
                .joinToString(",").let { if (it.isEmpty()) "" else " ($it)" }
        }
        return "dev options ${onOff(dev)}  adb ${onOff(adb)}  wireless adb ${onOff(wireless)}  usb $usbText"
    }

    private fun sun(): String {
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (!granted) return "needs location permission"
        val here = location.lastKnown() ?: return "no location known yet"
        val clock = DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault())
        return when (val day = Sun.day(here.latitude, here.longitude)) {
            is Sun.Day.Normal -> {
                val minutes = java.time.Duration.between(day.rise, day.set).toMinutes()
                "rise ${clock.format(day.rise)}  set ${clock.format(day.set)}  day ${minutes / 60}h${"%02d".format(minutes % 60)}m"
            }
            Sun.Day.AlwaysUp -> "sun up all day"
            Sun.Day.AlwaysDown -> "sun down all day"
        }
    }

    // --- helpers ---

    private fun updateRates() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNetAt < 500) return
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        val secs = (now - lastNetAt) / 1000.0
        rxRate = ((rx - lastRx) / secs).toLong().coerceAtLeast(0)
        txRate = ((tx - lastTx) / secs).toLong().coerceAtLeast(0)
        lastRx = rx; lastTx = tx; lastNetAt = now
    }

    private fun batteryTemp(): String =
        String.format(Locale.US, "%.1f", (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f)

    private var currentInMicroamps = false

    /**
     * Most devices report µA, a few mA; the sign convention also varies by vendor.
     * No phone draws 20 A, so a reading above 20 000 settles it as µA for good:
     * after that a small current (say 12 000 µA near a full charge) isn't misread as 12 A.
     */
    private fun currentMa(): Int {
        val raw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (raw == Int.MIN_VALUE) return 0
        if (abs(raw) > 20_000) currentInMicroamps = true
        return if (currentInMicroamps) raw / 1000 else raw
    }

    private fun readMeminfo(): Map<String, Long> =
        readText("/proc/meminfo")?.lines()?.mapNotNull { line ->
            val key = line.substringBefore(':', "").ifEmpty { return@mapNotNull null }
            line.substringAfter(':').trim().substringBefore(' ').toLongOrNull()?.let { key to it }
        }?.toMap().orEmpty()

    private fun cpuCount(path: String): Int? = readText(path)?.trim()?.split(',')?.sumOf { range ->
        val (a, b) = range.split('-').let { it[0].toInt() to (it.getOrNull(1)?.toInt() ?: it[0].toInt()) }
        b - a + 1
    }

    private fun readText(path: String): String? = runCatching { File(path).readText() }.getOrNull()

    companion object {
        private val THERMAL = listOf("none", "light", "moderate", "SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN")

        private const val SYNODIC = 29.530588853 // days between new moons
        private const val NEW_MOON_2000 = 947_182_440_000L // 2000-01-06 18:14 UTC

        fun bytes(b: Long): String {
            val v = b.coerceAtLeast(0).toDouble()
            return when {
                v >= 1L shl 30 -> String.format(Locale.US, "%.1fG", v / (1L shl 30))
                v >= 1L shl 20 -> String.format(Locale.US, "%.1fM", v / (1L shl 20))
                v >= 1L shl 10 -> String.format(Locale.US, "%.0fK", v / (1L shl 10))
                else -> "${v.toLong()}B"
            }
        }

        private fun ghz(khz: Long) = String.format(Locale.US, "%.2f", khz / 1_000_000.0)

        private fun onOff(b: Boolean) = if (b) "on" else "off"

        /** Radio values are Int.MAX_VALUE (CellInfo.UNAVAILABLE) when the modem doesn't report them. */
        private fun v(x: Int) = if (x == Int.MAX_VALUE) "?" else x.toString()

        private fun networkType(t: Int): String = when (t) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G SA"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+"
            TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA -> "HSPA"
            TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
            TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
            TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
            TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN (wifi calling)"
            TelephonyManager.NETWORK_TYPE_UNKNOWN -> "no data"
            else -> "type $t"
        }

        fun channel(f: Int): Int = when {
            f == 2484 -> 14
            f in 2412..2472 -> (f - 2407) / 5
            f in 5160..5885 -> (f - 5000) / 5
            f in 5955..7115 -> (f - 5950) / 5
            else -> 0
        }

        private val WIFI_STANDARDS = mapOf(1 to "802.11a/b/g", 4 to "Wi-Fi 4", 5 to "Wi-Fi 5", 6 to "Wi-Fi 6", 7 to "802.11ad", 8 to "Wi-Fi 7")

        /** Android's signal level 0..4 in words: a terminal spells it out rather than drawing bars. */
        fun strength(level: Int): String = when {
            level >= 4 -> "very strong"
            level == 3 -> "strong"
            level == 2 -> "medium"
            level == 1 -> "weak"
            else -> "very weak"
        }

        private fun duration(ms: Long): String {
            val s = ms / 1000
            return String.format(Locale.US, "%dd %02d:%02d:%02d", s / 86400, s % 86400 / 3600, s % 3600 / 60, s % 60)
        }

        private fun shortDuration(ms: Long): String {
            val m = (ms / 60_000).coerceAtLeast(0)
            return if (m >= 60) "${m / 60}h${m % 60}m" else "${m}m"
        }
    }
}
