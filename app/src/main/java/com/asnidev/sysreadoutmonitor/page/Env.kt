package com.asnidev.sysreadoutmonitor.page

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.asnidev.sysreadoutmonitor.data.MonitorPrefs
import com.asnidev.sysreadoutmonitor.log.Access
import com.asnidev.sysreadoutmonitor.log.ProbeReader
import com.asnidev.sysreadoutmonitor.monitor.NotifLog
import com.asnidev.sysreadoutmonitor.monitor.ShizukuBridge
import com.asnidev.sysreadoutmonitor.monitor.ShizukuState
import com.asnidev.sysreadoutmonitor.monitor.UsageSource
import com.asnidev.sysreadoutmonitor.term.Line
import com.asnidev.sysreadoutmonitor.term.Prompt
import com.asnidev.sysreadoutmonitor.term.Span
import com.asnidev.sysreadoutmonitor.term.Tap
import com.asnidev.sysreadoutmonitor.term.Tone
import com.asnidev.sysreadoutmonitor.term.needs
import com.asnidev.sysreadoutmonitor.term.needsRow
import com.asnidev.sysreadoutmonitor.term.row
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob

/** What every sampler shares. All of it is used from [serial], the coordinator's one thread. */
@OptIn(ExperimentalCoroutinesApi::class)
class Env(val context: Context, val shizuku: ShizukuBridge, val prefs: () -> MonitorPrefs) {
    val serial = Dispatchers.Default.limitedParallelism(1)

    /**
     * For slow work a sampler starts and waits for (reverse DNS, app sizes); samplers
     * cancel their jobs in stop(). A failure there is logged, never a crash.
     */
    val scope = CoroutineScope(
        SupervisorJob() + serial + CoroutineExceptionHandler { _, e -> Log.w("SRM", "background work failed", e) },
    )

    /** The Bluetooth device the scan page follows; set from the UI thread. */
    @Volatile var tracked: String? = null

    /** Licences the user opened on the conf page; set from the UI thread. */
    @Volatile var openLicences: Set<String> = emptySet()

    /** Asks for a fresh sample of the visible page, e.g. when slow work finished. */
    var poke: () -> Unit = {}

    val reader = ProbeReader(context)
    val usage = UsageSource(context)
    val labels = Labels(context)

    /** The phone's name as the prompt shows it. */
    val hostName: String = Prompt.host(Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME), Build.MODEL)

    /** Why [access] isn't usable yet, as the text of a tappable line; null when it is. */
    fun missing(access: Access): String? = when {
        access == Access.NONE -> null
        access == Access.USAGE -> if (usage.hasAccess()) null else "needs usage access, tap to grant"
        access == Access.NOTIFICATIONS -> if (NotifLog.connected) null else "needs notification access, tap to grant"
        access == Access.SHIZUKU -> when (shizuku.state.value) {
            ShizukuState.READY -> null
            ShizukuState.NOT_INSTALLED -> "needs shizuku (optional), tap to set it up"
            ShizukuState.NOT_RUNNING -> "shizuku isn't running, tap to start it"
            ShizukuState.UNSUPPORTED -> "needs a newer shizuku, tap to set it up"
            ShizukuState.NO_PERMISSION -> "needs shizuku permission, tap to allow"
            ShizukuState.CONNECTING -> "connecting to shizuku…"
            ShizukuState.FAILING -> "shizuku's helper keeps failing, tap to restart it"
            ShizukuState.OFF -> "shizuku is off"
        }
        access.isRuntime -> if (access.runtimeGranted(context)) null else "needs ${access.tag} permission, tap to allow"
        else -> null
    }

    /** A line to show in place of a whole section, or null when [access] is there. */
    fun gate(access: Access): Line? = missing(access)?.let { text ->
        if (text.endsWith('…')) Line(listOf(Span("  $text", Tone.DIM))) else needs(text, Tap.Grant(access))
    }

    /** The same for a single row. */
    fun gateRow(key: String, access: Access): Line? = missing(access)?.let { text ->
        if (text.endsWith('…')) row(key, listOf(Span(text, Tone.DIM))) else needsRow(key, text, Tap.Grant(access))
    }

    val shellReady: Boolean get() = shizuku.ready
}
