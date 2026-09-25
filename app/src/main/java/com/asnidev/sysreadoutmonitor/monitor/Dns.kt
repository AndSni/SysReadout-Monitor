package com.asnidev.sysreadoutmonitor.monitor

import kotlinx.coroutines.flow.MutableStateFlow

/** An IPv4/UDP packet read from the VPN interface. */
class UdpPacket(val srcIp: ByteArray, val dstIp: ByteArray, val srcPort: Int, val dstPort: Int, val payload: ByteArray)

/** Just enough IPv4/UDP to answer DNS queries on a tun interface. */
object Packets {

    fun parseUdp4(buf: ByteArray, length: Int): UdpPacket? {
        if (length < 28 || (buf[0].toInt() ushr 4) != 4) return null
        val ihl = (buf[0].toInt() and 0x0F) * 4
        if (buf[9].toInt() != 17 || length < ihl + 8) return null // not UDP
        val total = u16(buf, 2).coerceAtMost(length)
        val udpLen = u16(buf, ihl + 4)
        val end = minOf(total, ihl + udpLen)
        if (end < ihl + 8) return null
        return UdpPacket(
            srcIp = buf.copyOfRange(12, 16),
            dstIp = buf.copyOfRange(16, 20),
            srcPort = u16(buf, ihl),
            dstPort = u16(buf, ihl + 2),
            payload = buf.copyOfRange(ihl + 8, end),
        )
    }

    /** IPv4 header with a valid checksum; UDP checksum 0 ("none"), which IPv4 allows. */
    fun buildUdp4(srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val total = 28 + payload.size
        val p = ByteArray(total)
        p[0] = 0x45
        put16(p, 2, total)
        put16(p, 6, 0x4000) // don't fragment
        p[8] = 64 // TTL
        p[9] = 17 // UDP
        srcIp.copyInto(p, 12)
        dstIp.copyInto(p, 16)
        put16(p, 10, checksum(p, 0, 20))
        put16(p, 20, srcPort)
        put16(p, 22, dstPort)
        put16(p, 24, 8 + payload.size)
        payload.copyInto(p, 28)
        return p
    }

    fun checksum(p: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        while (i < offset + length - 1) {
            sum += u16(p, i)
            i += 2
        }
        if (length % 2 == 1) sum += (p[offset + length - 1].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv().toInt() and 0xFFFF
    }

    fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    private fun put16(b: ByteArray, i: Int, v: Int) {
        b[i] = (v ushr 8).toByte()
        b[i + 1] = v.toByte()
    }
}

object Dns {
    data class Question(val name: String, val type: Int)

    const val TYPE_A = 1
    const val TYPE_AAAA = 28

    fun question(msg: ByteArray): Question? = runCatching {
        if (msg.size < 12 || Packets.u16(msg, 4) < 1) return null
        val (name, next) = readName(msg, 12)
        Question(name, Packets.u16(msg, next))
    }.getOrNull()

    /** A and AAAA addresses in a response, in the same text form the /proc/net parser uses. */
    fun addresses(msg: ByteArray): List<String> = runCatching {
        if (msg.size < 12) return emptyList()
        val questions = Packets.u16(msg, 4)
        val answers = Packets.u16(msg, 6)
        var i = 12
        repeat(questions) { i = readName(msg, i).second + 4 }
        val out = mutableListOf<String>()
        repeat(answers) {
            i = readName(msg, i).second
            val type = Packets.u16(msg, i)
            val len = Packets.u16(msg, i + 8)
            val data = i + 10
            when {
                type == TYPE_A && len == 4 -> out += (0 until 4).joinToString(".") { (msg[data + it].toInt() and 0xFF).toString() }
                type == TYPE_AAAA && len == 16 -> out += Parsers.ipv6(msg.copyOfRange(data, data + 16))
            }
            i = data + len
        }
        out
    }.getOrDefault(emptyList())

    /** Reads a possibly-compressed name; returns it and the offset just past it. */
    fun readName(msg: ByteArray, start: Int): Pair<String, Int> {
        val labels = mutableListOf<String>()
        var i = start
        var end = -1
        var jumps = 0
        while (true) {
            val len = msg[i].toInt() and 0xFF
            when {
                len == 0 -> {
                    if (end < 0) end = i + 1
                    break
                }
                len and 0xC0 == 0xC0 -> {
                    if (end < 0) end = i + 2
                    if (++jumps > 16) error("compression loop")
                    i = ((len and 0x3F) shl 8) or (msg[i + 1].toInt() and 0xFF)
                }
                else -> {
                    labels += String(msg, i + 1, len, Charsets.US_ASCII)
                    i += len + 1
                }
            }
        }
        return labels.joinToString(".") to end
    }
}

/** What the DNS monitor saw, kept in memory so the log can catch up when it's next visible. */
object DnsLog {
    data class Lookup(val time: Long, val uid: Int, val name: String, val addresses: List<String>)

    val running = MutableStateFlow(false)

    private val lookups = ArrayDeque<Lookup>()
    private val hosts = object : LinkedHashMap<String, String>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 4096
    }

    @Synchronized
    fun add(lookup: Lookup) {
        lookups.addLast(lookup)
        while (lookups.size > 2000) lookups.removeFirst()
        lookup.addresses.forEach { hosts[it] = lookup.name }
    }

    @Synchronized
    fun since(time: Long): List<Lookup> = lookups.filter { it.time > time }

    /** The name an app looked up to get [ip], if the monitor saw it. */
    @Synchronized
    fun host(ip: String): String? = hosts[ip]
}
