package com.asnidev.sysreadoutmonitor

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.asnidev.sysreadoutmonitor.data.MonitorPrefs
import com.asnidev.sysreadoutmonitor.data.SettingsStore
import com.asnidev.sysreadoutmonitor.log.Access
import com.asnidev.sysreadoutmonitor.monitor.DnsLog
import com.asnidev.sysreadoutmonitor.monitor.DnsVpnService
import com.asnidev.sysreadoutmonitor.monitor.ShizukuBridge
import com.asnidev.sysreadoutmonitor.page.Conf
import com.asnidev.sysreadoutmonitor.page.ConfAction
import com.asnidev.sysreadoutmonitor.page.Coordinator
import com.asnidev.sysreadoutmonitor.page.Env
import com.asnidev.sysreadoutmonitor.page.Page
import com.asnidev.sysreadoutmonitor.page.Samplers
import com.asnidev.sysreadoutmonitor.page.Shell
import com.asnidev.sysreadoutmonitor.term.Prompt
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MonitorViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SettingsStore(app)

    // Loaded once; from then on this is the truth and every change is written through.
    private val _prefs = MutableStateFlow<MonitorPrefs?>(null)
    val prefs: StateFlow<MonitorPrefs?> = _prefs
    private val current: MonitorPrefs get() = _prefs.value ?: MonitorPrefs()

    val shizuku = ShizukuBridge(app)
    val env = Env(app, shizuku) { current }
    val coordinator = Coordinator(Samplers.create(env), env.serial) { current.intervalSec * 1000L }

    val prompt = Prompt.text(env.hostName)

    /** The shell tab's session: kept while the app lives, so its history survives page switches. */
    val shell = Shell(env, viewModelScope)

    /** Live text size; saved when a pinch ends. */
    var textSp by mutableFloatStateOf(MonitorPrefs.DEFAULT_SP)

    /** Runtime permissions already asked for in this session (see MainActivity.runtime). */
    val asked = HashSet<Access>()

    /** A page to switch to (debug extra, or a tap that leads to another page), and a line on it to show. */
    var pendingJump by mutableStateOf<Page?>(null)
    var pendingAnchor by mutableStateOf<String?>(null)

    fun jump(page: Page, anchor: String? = null) {
        pendingJump = page
        pendingAnchor = anchor
    }

    /** Follows a Bluetooth device on the scan page; the same one again stops. */
    fun track(address: String?) {
        env.tracked = if (address == env.tracked) null else address
        coordinator.poke()
    }

    init {
        viewModelScope.launch {
            val p = store.prefs.first()
            textSp = p.textSp
            _prefs.value = p
        }
        env.poke = coordinator::poke
        // Shizuku connecting or going away changes what the visible page can show.
        viewModelScope.launch {
            shizuku.state.drop(1).collect {
                coordinator.poke()
                shell.refresh()
            }
        }
        // Bring the DNS monitor back after the app restarts, if the user left it on.
        viewModelScope.launch {
            val p = _prefs.filterNotNull().first()
            if (p.dnsVpn && !DnsLog.running.value && DnsVpnService.consentIntent(app) == null) DnsVpnService.start(app)
        }
        viewModelScope.launch { DnsLog.running.drop(1).collect { coordinator.poke() } }
    }

    /** Samples the visible page until cancelled; the activity runs this while started. */
    suspend fun run() {
        _prefs.filterNotNull().first()
        coordinator.run()
    }

    fun show(page: Page) {
        coordinator.visible.value = page
        if (current.lastPage != page.tab) update { it.copy(lastPage = page.tab) }
    }

    fun update(transform: (MonitorPrefs) -> MonitorPrefs) {
        val next = transform(current)
        _prefs.value = next
        viewModelScope.launch { store.update { next } }
        coordinator.poke()
    }

    fun conf(action: ConfAction) {
        when (action) {
            is ConfAction.License -> {
                val open = env.openLicences
                env.openLicences = if (action.name in open) open - action.name else open + action.name
                coordinator.poke()
            }
            ConfAction.TextSize -> {
                textSp = MonitorPrefs.DEFAULT_SP
                update { Conf.apply(it, action) }
            }
            else -> update { Conf.apply(it, action) }
        }
    }

    fun setDnsMonitor(on: Boolean) {
        update { it.copy(dnsVpn = on) }
        if (on) DnsVpnService.start(getApplication()) else DnsVpnService.stop(getApplication())
    }

    fun zoomBy(factor: Float) {
        textSp = (textSp * factor).coerceIn(MonitorPrefs.MIN_SP, MonitorPrefs.MAX_SP)
    }

    fun saveTextSize() {
        textSp = (textSp * 2).roundToInt() / 2f
        update { it.copy(textSp = textSp) }
    }

    /** Back from system settings or the Shizuku app: access may have changed. */
    fun resumed() {
        shizuku.refresh()
        coordinator.poke()
    }

    override fun onCleared() {
        env.scope.cancel()
        shizuku.close()
    }
}
