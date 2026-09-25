package com.asnidev.sysreadoutmonitor.log

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/**
 * Bluetooth devices around the phone and how strong their signal is, for
 * finding one (a watch left somewhere). Scans only while the scan page is on
 * screen. A tracked device that is connected to the phone usually stops
 * advertising, so its signal is read over that connection instead.
 * Callbacks arrive on the main thread; readers take snapshots.
 */
class BleWatch(private val context: Context) {

    /** [smooth] is an average of recent readings: single readings jump by 10 dB or more. */
    data class Device(
        val address: String,
        val name: String?,
        val rssi: Int,
        val smooth: Double,
        val seenAt: Long,
        val viaLink: Boolean,
    )

    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val seen = ConcurrentHashMap<String, Device>()
    @Volatile var scanning = false
        private set
    /** ScanCallback's error code when the last scan couldn't start. */
    @Volatile var failure: Int? = null
        private set

    private var gatt: BluetoothGatt? = null
    private var tracked: String? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = record(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::record)
        override fun onScanFailed(errorCode: Int) {
            failure = errorCode
            scanning = false
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) runCatching { g.readRemoteRssi() }
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) put(g.device.address, null, rssi, viaLink = true)
        }
    }

    val present: Boolean get() = adapter != null
    val enabled: Boolean get() = runCatching { adapter?.isEnabled == true }.getOrDefault(false)
    fun granted(): Boolean = Access.NEARBY.runtimeGranted(context)

    @SuppressLint("MissingPermission") // granted() checked
    fun start() {
        if (scanning || !granted() || !enabled) return
        val scanner = adapter?.bluetoothLeScanner ?: return
        failure = null
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanning = runCatching { scanner.startScan(null, settings, scanCallback) }.isSuccess
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (scanning) runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        scanning = false
        track(null)
        seen.clear()
    }

    fun devices(): List<Device> = seen.values.toList()

    /** Paired devices, in range or not, with whether each is connected to the phone now. */
    @SuppressLint("MissingPermission")
    fun paired(): List<Pair<BluetoothDevice, Boolean>> {
        if (!granted()) return emptyList()
        return runCatching { adapter?.bondedDevices?.toList() }.getOrNull().orEmpty().map { it to connected(it) }
    }

    @SuppressLint("MissingPermission")
    fun name(d: BluetoothDevice): String? = runCatching {
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) d.alias else null) ?: d.name
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * Follows [address] closely. When it's connected to the phone over LE, its signal is
     * read through our own GATT client on that link (reading only; the owning app's
     * connection is untouched and ours is closed when tracking stops).
     */
    @SuppressLint("MissingPermission")
    fun track(address: String?) {
        if (address == tracked && (address == null || gatt != null)) return
        gatt?.let { g -> runCatching { g.disconnect(); g.close() } }
        gatt = null
        tracked = address
        if (address == null || !granted()) return
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: return
        if (device.type == BluetoothDevice.DEVICE_TYPE_CLASSIC || !connected(device)) return
        gatt = runCatching { device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE) }.getOrNull()
    }

    /** Asks the tracked device's link for a fresh reading; it arrives in the callback. */
    @SuppressLint("MissingPermission")
    fun poll() {
        runCatching { gatt?.readRemoteRssi() }
    }

    val trackingLink: Boolean get() = gatt != null

    private fun record(r: ScanResult) {
        val advertised = r.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
        put(r.device.address, advertised ?: name(r.device), r.rssi, viaLink = false)
    }

    private fun put(address: String, name: String?, rssi: Int, viaLink: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val was = seen[address]
        // Start the average afresh after a gap; otherwise move a third of the way to each new reading.
        val smooth = if (was == null || now - was.seenAt > 10_000) rssi.toDouble() else was.smooth + (rssi - was.smooth) / 3
        seen[address] = Device(address, name ?: was?.name, rssi, smooth, now, viaLink)
    }

    /** BluetoothDevice.isConnected() is hidden but long-standing; false when it can't be asked. */
    private fun connected(d: BluetoothDevice): Boolean =
        runCatching { d.javaClass.getMethod("isConnected").invoke(d) as Boolean }.getOrDefault(false)
}
