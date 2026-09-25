package com.asnidev.sysreadoutmonitor.log

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

/** Connected Bluetooth devices and, where they report it, their battery level. */
class BtWatch(private val context: Context) {

    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val proxies = ConcurrentHashMap<Int, BluetoothProfile>()
    private val levels = ConcurrentHashMap<String, Int>()
    private var running = false

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            proxies[profile] = proxy
        }

        override fun onServiceDisconnected(profile: Int) {
            proxies.remove(profile)
        }
    }

    // Headsets announce battery changes with this (hidden but long-standing) broadcast.
    private val batteryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(c: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            val level = intent.getIntExtra("android.bluetooth.device.extra.BATTERY_LEVEL", -1)
            if (level in 0..100) levels[device.address] = level
        }
    }

    private fun granted() = Access.BLUETOOTH.runtimeGranted(context)

    fun update(want: Boolean) {
        val on = want && adapter != null && granted()
        if (on == running) return
        running = on
        if (on) {
            PROFILES.forEach { runCatching { adapter!!.getProfileProxy(context, listener, it) } }
            ContextCompat.registerReceiver(
                context, batteryReceiver, IntentFilter(BATTERY_CHANGED), ContextCompat.RECEIVER_EXPORTED,
            )
        } else {
            proxies.forEach { (profile, proxy) -> runCatching { adapter?.closeProfileProxy(profile, proxy) } }
            proxies.clear()
            runCatching { context.unregisterReceiver(batteryReceiver) }
        }
    }

    @SuppressLint("MissingPermission") // checked by granted()
    fun line(): String {
        val a = adapter ?: return "no bluetooth"
        if (!granted()) return "needs bluetooth permission"
        if (!a.isEnabled) return "off"
        val devices = proxies.values.flatMap { runCatching { it.connectedDevices }.getOrDefault(emptyList()) }
            .distinctBy { it.address }
        if (devices.isEmpty()) return "on, nothing connected"
        return devices.joinToString("  ") { d ->
            val name = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) d.alias else null) ?: d.name ?: d.address
            name + (battery(d)?.let { " $it%" } ?: "")
        }
    }

    /** BluetoothDevice.getBatteryLevel() is hidden; fall back to the last broadcast value. */
    private fun battery(d: BluetoothDevice): Int? =
        runCatching { d.javaClass.getMethod("getBatteryLevel").invoke(d) as Int }.getOrNull()?.takeIf { it in 0..100 }
            ?: levels[d.address]

    private companion object {
        const val BATTERY_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        const val HEARING_AID = 21 // BluetoothProfile.HEARING_AID, API 29
        const val LE_AUDIO = 22 // BluetoothProfile.LE_AUDIO, API 33
        val PROFILES = listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET, HEARING_AID, LE_AUDIO)
    }
}
