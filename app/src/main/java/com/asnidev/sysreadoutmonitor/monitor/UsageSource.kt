package com.asnidev.sysreadoutmonitor.monitor

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import java.time.LocalDate
import java.time.ZoneId

/** Something that happened, as reported by UsageStatsManager. */
data class UsageEvent(val time: Long, val kind: Kind, val pkg: String) {
    enum class Kind { FOREGROUND, SERVICE_START, SERVICE_STOP, SCREEN_ON, SCREEN_OFF, UNLOCKED, LOCKED }
}

/**
 * Data behind the "usage access" permission the user grants in system
 * settings: app switches, foreground services, screen time and traffic.
 */
class UsageSource(private val context: Context) {

    private val usm = context.getSystemService(UsageStatsManager::class.java)
    private val appOps = context.getSystemService(AppOpsManager::class.java)
    private val netStats = context.getSystemService(NetworkStatsManager::class.java)

    @Suppress("DEPRECATION")
    fun hasAccess(): Boolean {
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openSettings() {
        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun events(from: Long, to: Long): List<UsageEvent> {
        val out = mutableListOf<UsageEvent>()
        val events = usm.queryEvents(from, to) ?: return out
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val kind = when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> UsageEvent.Kind.FOREGROUND
                FOREGROUND_SERVICE_START -> UsageEvent.Kind.SERVICE_START
                FOREGROUND_SERVICE_STOP -> UsageEvent.Kind.SERVICE_STOP
                SCREEN_INTERACTIVE -> UsageEvent.Kind.SCREEN_ON
                SCREEN_NON_INTERACTIVE -> UsageEvent.Kind.SCREEN_OFF
                KEYGUARD_HIDDEN -> UsageEvent.Kind.UNLOCKED
                KEYGUARD_SHOWN -> UsageEvent.Kind.LOCKED
                else -> null
            } ?: continue
            out += UsageEvent(e.timeStamp, kind, e.packageName ?: "")
        }
        return out
    }

    /** Foreground time per package since local midnight, longest first. */
    fun screenTimeToday(): List<Pair<String, Long>> {
        val stats = usm.queryAndAggregateUsageStats(startOfToday(), System.currentTimeMillis())
        return stats.values.map { s ->
            val ms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) s.totalTimeVisible else s.totalTimeInForeground
            s.packageName to ms
        }.filter { it.second >= 60_000 && it.first != context.packageName }
            .sortedByDescending { it.second }
    }

    /** Bytes (rx, tx) per uid since local midnight over Wi-Fi and mobile. */
    @Suppress("DEPRECATION") // TYPE_WIFI / TYPE_MOBILE are still what querySummary takes
    fun trafficToday(): Map<Int, Pair<Long, Long>> {
        val totals = HashMap<Int, Pair<Long, Long>>()
        for (type in listOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)) {
            val stats = runCatching { netStats.querySummary(type, null, startOfToday(), System.currentTimeMillis()) }
                .getOrNull() ?: continue
            val bucket = NetworkStats.Bucket()
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket)
                val (rx, tx) = totals[bucket.uid] ?: (0L to 0L)
                totals[bucket.uid] = (rx + bucket.rxBytes) to (tx + bucket.txBytes)
            }
            stats.close()
        }
        return totals
    }

    /** Screen-on time (ms) and unlock count since local midnight. */
    fun today(): Pair<Long, Int> {
        val start = startOfToday()
        val now = System.currentTimeMillis()
        var onSince: Long? = null
        var onMs = 0L
        var unlocks = 0
        var first = true
        val events = usm.queryEvents(start, now) ?: return 0L to 0
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                SCREEN_INTERACTIVE -> onSince = e.timeStamp
                SCREEN_NON_INTERACTIVE -> {
                    // The screen was already on at midnight if the day starts with "off".
                    onMs += e.timeStamp - (onSince ?: if (first) start else e.timeStamp)
                    onSince = null
                }
                KEYGUARD_HIDDEN -> unlocks++
                else -> continue
            }
            first = false
        }
        onSince?.let { onMs += now - it }
        return onMs to unlocks
    }

    /** Whole-device (Wi-Fi, mobile) bytes since the 1st of this month. */
    @Suppress("DEPRECATION")
    fun dataThisMonth(): Pair<Long, Long> {
        val start = LocalDate.now().withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        fun total(type: Int) = runCatching {
            netStats.querySummaryForDevice(type, null, start, System.currentTimeMillis()).let { it.rxBytes + it.txBytes }
        }.getOrDefault(0L)
        return total(ConnectivityManager.TYPE_WIFI) to total(ConnectivityManager.TYPE_MOBILE)
    }

    private fun startOfToday(): Long =
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private companion object {
        // Event types added after our minSdk; values are stable platform constants.
        const val SCREEN_INTERACTIVE = 15
        const val SCREEN_NON_INTERACTIVE = 16
        const val KEYGUARD_SHOWN = 17
        const val KEYGUARD_HIDDEN = 18
        const val FOREGROUND_SERVICE_START = 19
        const val FOREGROUND_SERVICE_STOP = 20
    }
}
