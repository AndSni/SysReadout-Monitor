package com.asnidev.sysreadoutmonitor.log

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi

/**
 * What the status bar would call the network: "5G" on an LTE anchor (NSA),
 * LTE-A carrier aggregation. Only Android 12+ reports this to apps.
 */
class PhoneWatch(private val context: Context) {

    private val tm = context.getSystemService(TelephonyManager::class.java)
    @Volatile var override: String? = null
        private set
    private var callback: Any? = null

    fun update(want: Boolean) {
        val on = want && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Access.PHONE.runtimeGranted(context)
        if (on == (callback != null)) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (on) register() else unregister()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    @SuppressLint("MissingPermission") // checked in update()
    private fun register() {
        val cb = object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
            override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
                override = when (info.overrideNetworkType) {
                    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> "LTE-CA"
                    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> "LTE-A Pro"
                    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA -> "5G NSA"
                    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> "5G+ (mmWave/advanced)"
                    else -> null
                }
            }
        }
        // The main thread's executor: a new thread per registration would leak one on every visit.
        runCatching { tm.registerTelephonyCallback(context.mainExecutor, cb) }.onSuccess { callback = cb }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun unregister() {
        (callback as? TelephonyCallback)?.let { runCatching { tm.unregisterTelephonyCallback(it) } }
        callback = null
        override = null
    }
}
