package com.asnidev.sysreadoutmonitor.log

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

enum class ProbeGroup(val title: String) {
    SYSTEM("system"),
    COMPUTE("cpu & memory"),
    POWER("power"),
    NETWORK("network"),
    STORAGE("storage"),
    DEVICE("device"),
    ACTIVITY("activity"),
    POSITION("position"),
}

/**
 * What a row needs beyond normal app permissions. Runtime ones list the
 * Android permissions to ask for when the user switches such a row on.
 */
enum class Access(val tag: String, val permissions: List<String> = emptyList()) {
    NONE(""),
    USAGE("usage access"),
    SHIZUKU("shizuku"),
    NOTIFICATIONS("notification access"),
    LOCATION("location", listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)),
    PHONE("phone", listOf(Manifest.permission.READ_PHONE_STATE)),
    BLUETOOTH(
        "bluetooth",
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList(),
    ),
    ACTIVITY(
        "activity",
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listOf(Manifest.permission.ACTIVITY_RECOGNITION) else emptyList(),
    ),

    /** Bluetooth scanning: Android 12's "nearby devices" (scan + connect for names), location before that. */
    NEARBY(
        "nearby devices",
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        },
    );

    /** True for the plain runtime permissions (not usage/notification access or Shizuku). */
    val isRuntime: Boolean get() = this in setOf(LOCATION, PHONE, BLUETOOTH, ACTIVITY, NEARBY)

    /**
     * Location needs only its first permission (fine; coarse is only offered alongside);
     * every other access needs all of its permissions.
     */
    fun runtimeGranted(context: Context): Boolean =
        (if (this == LOCATION) permissions.take(1) else permissions).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
}

/** One pinned log row the user can switch on. [id] doubles as the row's key column. */
data class ProbeInfo(
    val id: String,
    val group: ProbeGroup,
    val summary: String,
    val defaultOn: Boolean = false,
    val needs: Access = Access.NONE,
)

object ProbeCatalog {
    val all = listOf(
        ProbeInfo("time", ProbeGroup.SYSTEM, "time with seconds, UTC offset, unix epoch"),
        ProbeInfo("up", ProbeGroup.SYSTEM, "uptime and how much of it was awake", true),
        ProbeInfo("boot", ProbeGroup.SYSTEM, "boot count and when this boot started"),
        ProbeInfo("os", ProbeGroup.SYSTEM, "Android version, API level, security patch", true),
        ProbeInfo("kern", ProbeGroup.SYSTEM, "kernel release", true),
        ProbeInfo("props", ProbeGroup.SYSTEM, "A/B slot, verified boot, bootloader lock, treble, first API, build type"),
        ProbeInfo("dev", ProbeGroup.SYSTEM, "manufacturer, model, codename"),
        ProbeInfo("self", ProbeGroup.SYSTEM, "SR Monitor's own process: pid, CPU, memory, threads"),
        ProbeInfo("cpu", ProbeGroup.COMPUTE, "online cores, current clock range, governor", true),
        ProbeInfo("cores", ProbeGroup.COMPUTE, "load of every CPU core in percent", needs = Access.SHIZUKU),
        ProbeInfo("load", ProbeGroup.COMPUTE, "load average and process count", needs = Access.SHIZUKU),
        ProbeInfo("soc", ProbeGroup.COMPUTE, "chipset and CPU architecture", true),
        ProbeInfo("gpu", ProbeGroup.COMPUTE, "GPU, OpenGL ES and Vulkan versions"),
        ProbeInfo("mem", ProbeGroup.COMPUTE, "available / total RAM, low-memory flag", true),
        ProbeInfo("vm", ProbeGroup.COMPUTE, "memory detail: cached, active, dirty, slab"),
        ProbeInfo("swap", ProbeGroup.COMPUTE, "swap (usually zram) in use", true),
        ProbeInfo("therm", ProbeGroup.COMPUTE, "thermal status and throttling headroom", true),
        ProbeInfo("temps", ProbeGroup.COMPUTE, "every hardware temperature sensor: cpu, gpu, skin…", needs = Access.SHIZUKU),
        ProbeInfo("bat", ProbeGroup.POWER, "level, charge state, temperature, health", true),
        ProbeInfo("pwr", ProbeGroup.POWER, "voltage, current, watts, power source", true),
        ProbeInfo("chg", ProbeGroup.POWER, "charge counter, estimated capacity, cycles, time to full"),
        ProbeInfo("mode", ProbeGroup.POWER, "battery saver, doze, do-not-disturb, ringer"),
        ProbeInfo("net", ProbeGroup.NETWORK, "connection type and live throughput", true),
        ProbeInfo("wifi", ProbeGroup.NETWORK, "Wi-Fi signal, link speed, band", true),
        ProbeInfo("cell", ProbeGroup.NETWORK, "mobile operator, network type, signal"),
        ProbeInfo("ip", ProbeGroup.NETWORK, "IP address and gateway", true),
        ProbeInfo("radio", ProbeGroup.NETWORK, "airplane mode, bluetooth, NFC, location on/off"),
        ProbeInfo("rf", ProbeGroup.NETWORK, "mobile signal quality: RSRP, RSRQ, SINR (LTE/5G) or RSSI"),
        ProbeInfo("link", ProbeGroup.NETWORK, "network type incl. 5G NSA/SA and LTE-CA, carrier bandwidths", needs = Access.PHONE),
        ProbeInfo("tower", ProbeGroup.NETWORK, "serving cell: technology, band, channel, PCI, area code", needs = Access.LOCATION),
        ProbeInfo("ssid", ProbeGroup.NETWORK, "Wi-Fi name, BSSID, channel, Wi-Fi standard", needs = Access.LOCATION),
        ProbeInfo("aps", ProbeGroup.NETWORK, "Wi-Fi networks nearby and the strongest", needs = Access.LOCATION),
        ProbeInfo("data", ProbeGroup.NETWORK, "traffic since boot, total and mobile"),
        ProbeInfo("fs", ProbeGroup.STORAGE, "internal storage free / total", true),
        ProbeInfo("sd", ProbeGroup.STORAGE, "removable storage free / total"),
        ProbeInfo("disp", ProbeGroup.DEVICE, "resolution, refresh rate, density, brightness"),
        ProbeInfo("audio", ProbeGroup.DEVICE, "media and ring volume, audio output"),
        ProbeInfo("media", ProbeGroup.DEVICE, "what's playing and in which app", needs = Access.NOTIFICATIONS),
        ProbeInfo("alarm", ProbeGroup.DEVICE, "next alarm"),
        ProbeInfo("env", ProbeGroup.DEVICE, "light, pressure, altitude, temperature, humidity sensors"),
        ProbeInfo("compass", ProbeGroup.DEVICE, "heading, pitch and roll"),
        ProbeInfo("moon", ProbeGroup.DEVICE, "moon phase and days to the next full moon"),
        ProbeInfo("bt", ProbeGroup.DEVICE, "connected Bluetooth devices and their battery", needs = Access.BLUETOOTH),
        ProbeInfo("debug", ProbeGroup.DEVICE, "adb, wireless debugging, developer options, USB"),
        ProbeInfo("apps", ProbeGroup.DEVICE, "launchable, hidden and work-profile app counts"),
        ProbeInfo("gps", ProbeGroup.POSITION, "GPS fix: coordinates, altitude, accuracy, speed (GPS on while visible)", needs = Access.LOCATION),
        ProbeInfo("gnss", ProbeGroup.POSITION, "satellites used / in view per system: GPS, GLONASS, Galileo, BeiDou…", needs = Access.LOCATION),
        ProbeInfo("sun", ProbeGroup.POSITION, "sunrise, sunset and day length where you are", needs = Access.LOCATION),
        ProbeInfo("today", ProbeGroup.ACTIVITY, "screen-on time and unlocks today", needs = Access.USAGE),
        ProbeInfo("month", ProbeGroup.ACTIVITY, "data used this month, Wi-Fi and mobile", needs = Access.USAGE),
        ProbeInfo("steps", ProbeGroup.ACTIVITY, "steps today and since boot", needs = Access.ACTIVITY),
        ProbeInfo("ntf", ProbeGroup.ACTIVITY, "notifications showing now and received today", needs = Access.NOTIFICATIONS),
    )

    val byId = all.associateBy { it.id }
    val defaultIds = all.filter { it.defaultOn }.map { it.id }
}
