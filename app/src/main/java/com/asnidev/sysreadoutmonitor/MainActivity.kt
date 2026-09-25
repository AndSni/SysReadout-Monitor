package com.asnidev.sysreadoutmonitor

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.asnidev.sysreadoutmonitor.data.MonitorPrefs
import com.asnidev.sysreadoutmonitor.log.Access
import com.asnidev.sysreadoutmonitor.monitor.DnsVpnService
import com.asnidev.sysreadoutmonitor.monitor.NotifListener
import com.asnidev.sysreadoutmonitor.monitor.ShizukuState
import com.asnidev.sysreadoutmonitor.page.Page
import com.asnidev.sysreadoutmonitor.term.Tap
import com.asnidev.sysreadoutmonitor.ui.MonitorScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vm: MonitorViewModel by viewModels()

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.resumed()
    }

    // Android's one-time "allow this VPN?" dialog for the DNS monitor.
    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) vm.setDnsMonitor(true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        debugExtras(intent)
        // Sampling runs only while the activity is visible.
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { vm.run() } }
        setContent { MonitorScreen(vm, ::onTap) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        debugExtras(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.resumed()
    }

    private fun onTap(tap: Tap) {
        when (tap) {
            is Tap.Goto -> vm.pendingJump = tap.page
            is Tap.Grant -> grant(tap.access)
            is Tap.Conf -> vm.conf(tap.action)
            is Tap.DnsMonitor -> dnsMonitor(tap.on)
            Tap.AppSettings -> open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            is Tap.Url -> open(Intent(Intent.ACTION_VIEW, Uri.parse(tap.url)))
        }
    }

    private fun dnsMonitor(on: Boolean) {
        if (!on) return vm.setDnsMonitor(false)
        val ask = DnsVpnService.consentIntent(this)
        if (ask == null) vm.setDnsMonitor(true) else vpnConsent.launch(ask)
    }

    /** The same grant flows as the launcher: nothing is asked for until the user taps what needs it. */
    private fun grant(access: Access) {
        when (access) {
            Access.NONE -> Unit
            Access.USAGE -> open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            Access.NOTIFICATIONS -> notificationAccess()
            Access.SHIZUKU -> shizuku()
            else -> runtime(access)
        }
    }

    private fun runtime(access: Access) {
        val permissions = access.permissions
        if (permissions.isEmpty()) return
        // After two refusals Android stops showing the dialog; then only the app's settings can grant it.
        val blocked = access in vm.asked && permissions.none { shouldShowRequestPermissionRationale(it) }
        if (blocked) {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } else {
            vm.asked += access
            askPermissions.launch(permissions.toTypedArray())
        }
    }

    private fun notificationAccess() {
        val component = ComponentName(this, NotifListener::class.java).flattenToString()
        val direct = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component))
        if (!direct) open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun shizuku() {
        val bridge = vm.shizuku
        bridge.refresh()
        when (bridge.state.value) {
            ShizukuState.NOT_INSTALLED, ShizukuState.UNSUPPORTED -> open(Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_DOWNLOAD)))
            // Starting it is up to the user (wireless debugging, adb or root); conf explains how.
            ShizukuState.NOT_RUNNING -> if (!bridge.openApp()) vm.pendingJump = Page.CONF
            ShizukuState.NO_PERMISSION -> bridge.requestPermission()
            ShizukuState.CONNECTING, ShizukuState.READY -> vm.resumed()
        }
    }

    private fun open(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    /**
     * Debug builds only, so states can be looked at without tapping through the UI:
     * `--es page cpu` opens a page, `--ef textsp 20` sets the text size (not saved).
     */
    private fun debugExtras(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        intent.getStringExtra("page")?.let { tab -> Page.byTab(tab)?.let { vm.pendingJump = it } }
        if (intent.hasExtra("textsp")) {
            vm.textSp = intent.getFloatExtra("textsp", vm.textSp).coerceIn(MonitorPrefs.MIN_SP, MonitorPrefs.MAX_SP)
        }
    }

    private companion object {
        const val SHIZUKU_DOWNLOAD = "https://shizuku.rikka.app/download/"
    }
}
