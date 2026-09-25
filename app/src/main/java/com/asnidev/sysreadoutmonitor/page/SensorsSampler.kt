package com.asnidev.sysreadoutmonitor.page

import com.asnidev.sysreadoutmonitor.log.Access
import com.asnidev.sysreadoutmonitor.log.LocationWatch
import com.asnidev.sysreadoutmonitor.monitor.Parsers
import com.asnidev.sysreadoutmonitor.term.Col
import com.asnidev.sysreadoutmonitor.term.Line
import com.asnidev.sysreadoutmonitor.term.Tone
import com.asnidev.sysreadoutmonitor.term.cell
import com.asnidev.sysreadoutmonitor.term.comment
import com.asnidev.sysreadoutmonitor.term.row
import com.asnidev.sysreadoutmonitor.term.table
import java.util.Locale

/** `sensors`: the environment, position and sky, activity, radios and devices. */
class SensorsSampler(private val env: Env) : PageSampler {

    private val mediaCadence = Cadence { 2 * env.shellMs() }
    private var media: String? = null

    override fun start() = env.reader.updateWatchers(WATCHED)

    override fun stop() {
        env.reader.stopWatchers()
        mediaCadence.reset()
    }

    override suspend fun sample(): List<Line> {
        // Again each time, so a permission granted while the page is open takes effect.
        env.reader.updateWatchers(WATCHED)
        if (env.missing(Access.SHIZUKU) == null && mediaCadence.due()) {
            env.shizuku.exec("dumpsys media_session")?.let { text ->
                media = Parsers.nowPlaying(text)?.let { "${it.title}" + (it.artist?.let { a -> " — $a" } ?: "") + " · ${env.labels.pkgLabel(it.pkg)}" }
                    ?: "nothing playing"
            }
        }
        val out = ArrayList<Line>()
        out += comment("environment")
        out += env.probeRows(listOf("env", "compass"))
        out += comment("position · gps is on only while this page is visible")
        out += env.probeRows(listOf("gps", "gnss"))
        if (env.missing(Access.LOCATION) == null) out += satellites()
        out += env.probeRows(listOf("sun", "moon"))
        out += comment("activity")
        out += env.probeRows(listOf("steps"))
        out += comment("radios and devices")
        out += env.probeRows(
            listOf("radio", "bt", "audio", "media", "alarm", "debug"),
            own = mapOf("media" to row("media", media ?: "…")),
        )
        return out
    }

    /** One line per satellite in view, as `GnssStatus` reports it. */
    private fun satellites(): List<Line> {
        val sats = env.reader.satellites()
        if (sats.isEmpty()) return emptyList()
        return table(
            listOf(Col("SYS"), Col("ID", right = true), Col("C/N0", right = true), Col("ELEV", right = true), Col("AZ", right = true), Col("FIX")),
            sats.sortedWith(compareByDescending<LocationWatch.Satellite> { it.used }.thenByDescending { it.cn0 })
                .map { s ->
                    listOf(
                        cell(s.system),
                        cell(s.svid.toString(), Tone.DIM),
                        cell(String.format(Locale.US, "%.0f", s.cn0)),
                        cell(String.format(Locale.US, "%.0f°", s.elevation)),
                        cell(String.format(Locale.US, "%.0f°", s.azimuth)),
                        if (s.used) cell("used", Tone.GOOD) else cell("-", Tone.DIM),
                    )
                },
        )
    }

    private companion object {
        val WATCHED = listOf("env", "compass", "gps", "gnss", "steps", "bt")
    }
}
