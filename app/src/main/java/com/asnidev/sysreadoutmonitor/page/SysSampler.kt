package com.asnidev.sysreadoutmonitor.page

import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserManager
import com.asnidev.sysreadoutmonitor.term.Line
import com.asnidev.sysreadoutmonitor.term.Span
import com.asnidev.sysreadoutmonitor.term.Tone

/** `fastfetch`: what the phone is. */
class SysSampler(private val env: Env) : PageSampler {

    private val context = env.context
    private val appsCadence = Cadence { 60_000L }
    private var apps: String? = null

    init {
        env.reader.appsSummary = { apps }
    }

    override suspend fun sample(): List<Line> {
        if (appsCadence.due()) apps = countApps()
        val host = env.hostName
        val title = Line(listOf(Span("user", Tone.KEY), Span("@"), Span(host, Tone.KEY)))
        val rule = Line(listOf(Span("-".repeat(5 + host.length))))
        return listOf(title, rule) + env.reader.values(ROWS).map { (id, value) -> Paint.row(id, value) }
    }

    /** Installed packages (user-installed among them) and launchable apps per profile. */
    private fun countApps(): String {
        val pm = context.packageManager
        val installed = runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
        val user = installed.count { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
        val launcher = context.getSystemService(LauncherApps::class.java)
        val profiles = context.getSystemService(UserManager::class.java).userProfiles
        val me = Process.myUserHandle()
        val mine = runCatching { launcher.getActivityList(null, me).size }.getOrDefault(0)
        val work = profiles.filter { it != me }.sumOf { runCatching { launcher.getActivityList(null, it).size }.getOrDefault(0) }
        return "${installed.size} installed ($user by you)  $mine launchable" + if (work > 0) "  +$work work" else ""
    }

    private companion object {
        val ROWS = listOf("dev", "os", "kern", "props", "soc", "gpu", "disp", "up", "boot", "time", "apps")
    }
}
