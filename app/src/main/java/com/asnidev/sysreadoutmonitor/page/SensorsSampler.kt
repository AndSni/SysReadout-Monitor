package com.asnidev.sysreadoutmonitor.page

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import com.asnidev.sysreadoutmonitor.log.Access
import com.asnidev.sysreadoutmonitor.log.LocationWatch
import com.asnidev.sysreadoutmonitor.monitor.NotifListener
import com.asnidev.sysreadoutmonitor.monitor.NotifLog
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
        // Notification access is enough to see media sessions; Shizuku's dumpsys is the fallback.
        val sessions = NotifLog.connected
        if (sessions) {
            media = nowPlaying()
        } else if (env.missing(Access.SHIZUKU) == null && mediaCadence.due()) {
            env.shizuku.exec("dumpsys media_session")?.let { text ->
                media = Parsers.nowPlaying(text)?.let { describe(it.title, it.artist, it.pkg) } ?: "nothing playing"
            }
        }
        val mediaRow = when {
            sessions || env.missing(Access.SHIZUKU) == null -> row("media", media ?: "…")
            else -> env.gateRow("media", Access.NOTIFICATIONS)
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
            own = mapOf("media" to mediaRow),
        )
        return out
    }

    private fun describe(title: String, artist: String?, pkg: String) =
        title + (artist?.let { " — $it" } ?: "") + " · ${env.labels.pkgLabel(pkg)}"

    /** The first playing media session, through the notification listener's access. */
    private fun nowPlaying(): String = runCatching {
        val msm = env.context.getSystemService(MediaSessionManager::class.java)
        val sessions = msm.getActiveSessions(ComponentName(env.context, NotifListener::class.java))
        val playing = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val meta = playing?.metadata ?: return@runCatching "nothing playing"
        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: return@runCatching "playing, no title"
        describe(title, meta.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }, playing.packageName)
    }.getOrDefault("…")

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
