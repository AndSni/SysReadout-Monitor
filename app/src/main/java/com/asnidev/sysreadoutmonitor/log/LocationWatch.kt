package com.asnidev.sysreadoutmonitor.log

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.abs

/**
 * GPS fix and satellite status. Only switched on while a row needs it and the
 * home screen is visible: an active GPS costs battery.
 */
class LocationWatch(private val context: Context) {

    private val lm = context.getSystemService(LocationManager::class.java)
    @Volatile private var fix: Location? = null
    @Volatile private var status: GnssStatus? = null
    private var running = false

    private val listener = LocationListener { fix = it }
    private val gnss = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(s: GnssStatus) {
            status = s
        }
    }

    private fun granted() = Access.LOCATION.runtimeGranted(context)

    /** Approximate location is enough for a last known position (sunrise). */
    private fun anyLocationGranted() = Access.LOCATION.permissions.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission") // checked by granted()
    fun update(gps: Boolean) {
        val want = gps && granted()
        if (want == running) return
        running = want
        if (want) {
            val main = Looper.getMainLooper()
            runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2_000L, 0f, listener, main) }
            @Suppress("DEPRECATION") // the executor overload is API 30+
            runCatching { lm.registerGnssStatusCallback(gnss, Handler(main)) }
        } else {
            runCatching { lm.removeUpdates(listener) }
            runCatching { lm.unregisterGnssStatusCallback(gnss) }
            status = null
        }
    }

    /** Any recent position, for things that don't need GPS precision (sunrise). */
    @SuppressLint("MissingPermission")
    fun lastKnown(): Location? {
        if (!anyLocationGranted()) return null
        val providers = buildList {
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }
        return (listOfNotNull(fix) + providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() })
            .maxByOrNull { it.elapsedRealtimeNanos }
    }

    fun fixLine(): String {
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) return "location is off"
        val f = fix ?: return "waiting for a fix"
        val age = (SystemClock.elapsedRealtimeNanos() - f.elapsedRealtimeNanos) / 1_000_000_000
        val lat = String.format(Locale.US, "%.5f%s", abs(f.latitude), if (f.latitude >= 0) "N" else "S")
        val lon = String.format(Locale.US, "%.5f%s", abs(f.longitude), if (f.longitude >= 0) "E" else "W")
        return buildString {
            append("$lat $lon")
            if (f.hasAltitude()) append("  alt ${f.altitude.toInt()}m")
            if (f.hasAccuracy()) append("  ±${f.accuracy.toInt()}m")
            if (f.hasSpeed() && f.speed > 0.5f) append(String.format(Locale.US, "  %.1fkm/h", f.speed * 3.6f))
            if (age > 10) append("  ${age}s old")
        }
    }

    fun satellitesLine(): String {
        val s = status ?: return if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) "searching" else "location is off"
        val all = 0 until s.satelliteCount
        val used = all.filter { s.usedInFix(it) }
        val systems = all.groupBy { s.getConstellationType(it) }.entries.sortedBy { it.key }.joinToString("  ") { (type, sats) ->
            "${SYSTEMS[type] ?: "?"} ${sats.count { s.usedInFix(it) }}/${sats.size}"
        }
        val cn0 = used.map { s.getCn0DbHz(it) }.average().takeIf { !it.isNaN() }
        return "${used.size}/${s.satelliteCount} used  $systems" + (cn0?.let { "  C/N0 ${it.toInt()}" } ?: "")
    }

    /** One satellite from the last GnssStatus: constellation, id, signal (dB-Hz), position in the sky, used in the fix. */
    data class Satellite(val system: String, val svid: Int, val cn0: Float, val elevation: Float, val azimuth: Float, val used: Boolean)

    fun satellites(): List<Satellite> {
        val s = status ?: return emptyList()
        return (0 until s.satelliteCount).map {
            Satellite(
                SYSTEMS[s.getConstellationType(it)] ?: "?",
                s.getSvid(it),
                s.getCn0DbHz(it),
                s.getElevationDegrees(it),
                s.getAzimuthDegrees(it),
                s.usedInFix(it),
            )
        }
    }

    private companion object {
        val SYSTEMS = mapOf(
            GnssStatus.CONSTELLATION_GPS to "GPS",
            GnssStatus.CONSTELLATION_SBAS to "SBAS",
            GnssStatus.CONSTELLATION_GLONASS to "GLO",
            GnssStatus.CONSTELLATION_QZSS to "QZSS",
            GnssStatus.CONSTELLATION_BEIDOU to "BDS",
            GnssStatus.CONSTELLATION_GALILEO to "GAL",
            7 to "NavIC", // CONSTELLATION_IRNSS
        )
    }
}
