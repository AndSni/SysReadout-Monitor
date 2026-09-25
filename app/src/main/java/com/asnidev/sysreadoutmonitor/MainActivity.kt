package com.asnidev.sysreadoutmonitor

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.asnidev.sysreadoutmonitor.data.MonitorPrefs
import com.asnidev.sysreadoutmonitor.page.Page
import com.asnidev.sysreadoutmonitor.term.Tap
import com.asnidev.sysreadoutmonitor.ui.MonitorScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vm: MonitorViewModel by viewModels()

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
            is Tap.Grant -> Unit
        }
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
}
