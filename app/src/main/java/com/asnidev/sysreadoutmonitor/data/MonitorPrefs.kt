package com.asnidev.sysreadoutmonitor.data

import org.json.JSONArray
import org.json.JSONObject

/** Everything the user can set on the conf page, plus the remembered page and text size. */
data class MonitorPrefs(
    /** Seconds between samples of the visible page; shell-based tables use at least [SHELL_MIN_SEC]. */
    val intervalSec: Int = 2,
    /** Tab names in display order; tabs missing here are appended in their default place. */
    val order: List<String> = emptyList(),
    /** Tab names the user switched off. */
    val hidden: Set<String> = emptySet(),
    val lastPage: String = "",
    val textSp: Float = DEFAULT_SP,
    val resolveHosts: Boolean = true,
    /** The optional local-VPN DNS monitor: real hostnames per app. */
    val dnsVpn: Boolean = false,
    val logcatWarnings: Boolean = false,
    val notifTitles: Boolean = false,
    // Journal sources.
    val evSystem: Boolean = true,
    val evApps: Boolean = true,
    val evServices: Boolean = true,
    val evScreen: Boolean = true,
    val evProcs: Boolean = true,
    val evConns: Boolean = true,
    val evDns: Boolean = true,
    val evNotif: Boolean = true,
    val evLogcat: Boolean = true,
) {
    fun toJson(): String = JSONObject()
        .put("intervalSec", intervalSec)
        .put("order", JSONArray(order))
        .put("hidden", JSONArray(hidden.toList()))
        .put("lastPage", lastPage)
        .put("textSp", textSp.toDouble())
        .put("resolveHosts", resolveHosts).put("dnsVpn", dnsVpn)
        .put("logcatWarnings", logcatWarnings).put("notifTitles", notifTitles)
        .put("evSystem", evSystem).put("evApps", evApps).put("evServices", evServices).put("evScreen", evScreen)
        .put("evProcs", evProcs).put("evConns", evConns).put("evDns", evDns).put("evNotif", evNotif)
        .put("evLogcat", evLogcat)
        .toString()

    companion object {
        val INTERVALS = listOf(1, 2, 5, 10)
        const val SHELL_MIN_SEC = 5
        const val DEFAULT_SP = 12f
        const val MIN_SP = 8f
        const val MAX_SP = 20f

        fun fromJson(s: String?): MonitorPrefs {
            val d = MonitorPrefs()
            val o = runCatching { JSONObject(s ?: return d) }.getOrElse { return d }
            fun strings(key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } }
            return MonitorPrefs(
                intervalSec = o.optInt("intervalSec", d.intervalSec).takeIf { it in INTERVALS } ?: d.intervalSec,
                order = strings("order") ?: d.order,
                hidden = strings("hidden")?.toSet() ?: d.hidden,
                lastPage = o.optString("lastPage", d.lastPage),
                textSp = o.optDouble("textSp", d.textSp.toDouble()).toFloat().coerceIn(MIN_SP, MAX_SP),
                resolveHosts = o.optBoolean("resolveHosts", d.resolveHosts),
                dnsVpn = o.optBoolean("dnsVpn", d.dnsVpn),
                logcatWarnings = o.optBoolean("logcatWarnings", d.logcatWarnings),
                notifTitles = o.optBoolean("notifTitles", d.notifTitles),
                evSystem = o.optBoolean("evSystem", d.evSystem),
                evApps = o.optBoolean("evApps", d.evApps),
                evServices = o.optBoolean("evServices", d.evServices),
                evScreen = o.optBoolean("evScreen", d.evScreen),
                evProcs = o.optBoolean("evProcs", d.evProcs),
                evConns = o.optBoolean("evConns", d.evConns),
                evDns = o.optBoolean("evDns", d.evDns),
                evNotif = o.optBoolean("evNotif", d.evNotif),
                evLogcat = o.optBoolean("evLogcat", d.evLogcat),
            )
        }
    }
}
