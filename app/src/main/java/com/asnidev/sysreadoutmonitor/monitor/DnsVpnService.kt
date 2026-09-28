package com.asnidev.sysreadoutmonitor.monitor

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The optional DNS monitor: a VPN that routes nothing but DNS. Android sends
 * every app's lookups to a fake resolver address inside this interface; each
 * query is noted (which app, which name, which addresses came back) and
 * relayed unchanged to the network's real DNS server (Quad9 9.9.9.9 and
 * Cloudflare 1.1.1.1 only when the network names none). All other traffic
 * bypasses it. SR Monitor itself is excluded so its relaying can't loop.
 */
class DnsVpnService : VpnService() {

    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var running = false
    // Bounded: while the network is down, queries back up; drop the oldest (the app retries) rather than pile up.
    private val pool = ThreadPoolExecutor(
        4, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(128), ThreadPoolExecutor.DiscardOldestPolicy(),
    )
    private val cm by lazy { getSystemService(ConnectivityManager::class.java) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        if (tun == null && !establish()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onRevoke() {
        // Another VPN took over, or the user switched us off in system settings.
        shutdown()
        stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        pool.shutdownNow()
        super.onDestroy()
    }

    private fun establish(): Boolean {
        // establish() only works for the app Android last "prepared"; doing it here
        // also covers starts that didn't come through the settings switch (always-on VPN).
        if (prepare(this) != null) {
            Log.w(TAG, "not allowed to run a VPN: the user hasn't approved it")
            return false
        }
        val fd = runCatching {
            Builder()
                .setSession("SR Monitor DNS monitor")
                .setMtu(MTU)
                .addAddress(VPN_ADDRESS, 32)
                .addRoute(DNS_ADDRESS, 32)
                .addDnsServer(DNS_ADDRESS)
                .setBlocking(false)
                .apply { runCatching { addDisallowedApplication(packageName) } }
                .establish()
        }.onFailure { Log.w(TAG, "couldn't start the DNS monitor", it) }.getOrNull() ?: return false
        tun = fd
        running = true
        DnsLog.running.value = true
        thread(name = "dns-monitor") { loop(fd) }
        return true
    }

    private fun shutdown() {
        running = false
        DnsLog.running.value = false
        runCatching { tun?.close() }
        tun = null
    }

    private fun loop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val poll = StructPollfd().apply {
            this.fd = fd.fileDescriptor
            events = OsConstants.POLLIN.toShort()
        }
        val buf = ByteArray(MTU)
        while (running) {
            try {
                // Wake up every second so shutdown() never waits on a blocked read.
                if (Os.poll(arrayOf(poll), 1000) <= 0) continue
                val n = input.read(buf)
                if (n <= 0) continue
                val query = Packets.parseUdp4(buf, n) ?: continue
                if (query.dstPort != 53) continue // e.g. the DNS-over-TLS probe to :853: let it time out
                val uid = ownerUid(query) // must be asked while the app's socket still exists
                pool.execute { answer(query, uid, output) }
            } catch (e: ErrnoException) {
                if (e.errno != OsConstants.EINTR) {
                    if (running) Log.w(TAG, "tun read failed", e)
                    break
                }
            } catch (e: IOException) {
                if (running) Log.w(TAG, "tun read failed", e)
                break
            } catch (e: RejectedExecutionException) { // the service is being destroyed
                break
            } catch (e: Exception) {
                if (running) Log.w(TAG, "dns monitor loop failed", e)
                break
            }
        }
        // Ending on its own leaves the VPN up with nobody answering: every lookup on
        // the phone would hang. Take it down instead.
        if (running) {
            Log.w(TAG, "dns monitor stopped reading; shutting it down")
            shutdown()
            stopSelf()
        }
    }

    private fun answer(query: UdpPacket, uid: Int, output: FileOutputStream) {
        try {
            val response = relay(query.payload) ?: return
            val reply = Packets.buildUdp4(query.dstIp, query.srcIp, query.dstPort, query.srcPort, response)
            synchronized(output) { runCatching { output.write(reply) } }
            Dns.question(query.payload)?.let { q ->
                DnsLog.add(DnsLog.Lookup(System.currentTimeMillis(), uid, q.name, Dns.addresses(response)))
            }
        } catch (e: Exception) { // a thrown exception on a pool thread would crash the app
            Log.w(TAG, "couldn't answer a lookup", e)
        }
    }

    /** Sends the query to the real resolver(s) and returns the raw answer. */
    private fun relay(message: ByteArray): ByteArray? {
        for (server in upstream()) {
            try {
                DatagramSocket().use { socket ->
                    protect(socket)
                    socket.soTimeout = 4_000
                    socket.send(DatagramPacket(message, message.size, server, 53))
                    val buf = ByteArray(MTU)
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    return buf.copyOf(packet.length)
                }
            } catch (_: IOException) {
                // try the next server
            }
        }
        return null
    }

    /** SR Monitor is excluded from its own VPN, so its default network is the real one. */
    private fun upstream(): List<InetAddress> =
        cm.getLinkProperties(cm.activeNetwork)?.dnsServers.orEmpty()
            .filterNot { it.hostAddress == DNS_ADDRESS }
            .ifEmpty { FALLBACK }

    private fun ownerUid(p: UdpPacket): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1
        return runCatching {
            cm.getConnectionOwnerUid(
                OsConstants.IPPROTO_UDP,
                InetSocketAddress(InetAddress.getByAddress(p.srcIp), p.srcPort),
                InetSocketAddress(InetAddress.getByAddress(p.dstIp), p.dstPort),
            )
        }.getOrDefault(-1)
    }

    companion object {
        private const val TAG = "DnsMonitor"
        private const val ACTION_STOP = "com.asnidev.sysreadoutmonitor.DNS_STOP"
        private const val MTU = 4096
        private const val VPN_ADDRESS = "10.111.222.1"
        const val DNS_ADDRESS = "10.111.222.2"
        private val FALLBACK = listOf("9.9.9.9", "1.1.1.1").map { InetAddress.getByName(it) }

        /** Null when the user has already allowed the VPN; otherwise the consent screen to show. */
        fun consentIntent(context: Context): Intent? = prepare(context)

        /** False when Android refused (e.g. SR Monitor isn't in the foreground). */
        fun start(context: Context): Boolean = runCatching {
            context.startService(Intent(context, DnsVpnService::class.java)) != null
        }.onFailure { Log.w(TAG, "couldn't start the DNS monitor", it) }.getOrDefault(false)

        fun stop(context: Context) {
            if (!DnsLog.running.value) return
            runCatching { context.startService(Intent(context, DnsVpnService::class.java).setAction(ACTION_STOP)) }
                .onFailure { Log.w(TAG, "couldn't stop the DNS monitor", it) }
        }
    }
}
