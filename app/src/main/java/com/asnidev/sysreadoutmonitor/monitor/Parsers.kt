package com.asnidev.sysreadoutmonitor.monitor

/** One row of `top -b -n 1 -q -o PID,UID,%CPU,RES,NAME`. */
data class Proc(val pid: Int, val uid: Int, val cpu: Float, val resBytes: Long, val name: String) {
    /** App processes are named after their package, optionally with ":suffix". */
    val pkg: String get() = name.substringBefore(':')
    val isApp: Boolean get() = uid >= FIRST_APP_UID
}

/** A socket from /proc/net/{tcp,tcp6,udp,udp6}. */
data class Sock(val proto: String, val remoteIp: String, val remotePort: Int, val state: Int, val uid: Int) {
    val remote: String get() = if (':' in remoteIp) "[$remoteIp]:$remotePort" else "$remoteIp:$remotePort"
}

/** A held wakelock from `dumpsys power`; [uid] is the app it's held for (work source) when known. */
data class WakeLock(val level: String, val tag: String, val uid: Int)

/** A playing media session from `dumpsys media_session`. */
data class NowPlaying(val pkg: String, val title: String, val artist: String?)

/** A line from `logcat -v epoch`. */
data class LogLine(val time: Double, val level: Char, val tag: String, val message: String)

/** One app's share of battery since the last charge, and what used most of it. */
data class Drain(val uid: Int, val mah: Double, val mostly: String?)

/** Busy and total jiffies of one CPU core from /proc/stat. */
data class CoreTicks(val busy: Long, val total: Long)

const val FIRST_APP_UID = 10_000

object Parsers {

    fun top(text: String): List<Proc> = text.lineSequence().mapNotNull { line ->
        val parts = line.trim().split(Regex("\\s+"), limit = 5)
        if (parts.size < 5) return@mapNotNull null
        val pid = parts[0].toIntOrNull() ?: return@mapNotNull null
        val uid = parts[1].toIntOrNull() ?: return@mapNotNull null
        val cpu = parts[2].toFloatOrNull() ?: return@mapNotNull null
        Proc(pid, uid, cpu, size(parts[3]), parts[4].trim())
    }.toList()

    /** top's RES column: "0", "812K", "4.7M", "1.2G". */
    fun size(s: String): Long {
        val unit = when (s.lastOrNull()?.uppercaseChar()) {
            'K' -> 1L shl 10
            'M' -> 1L shl 20
            'G' -> 1L shl 30
            else -> return (s.toDoubleOrNull() ?: 0.0).toLong() * 1024 // plain numbers are KiB
        }
        return ((s.dropLast(1).toDoubleOrNull() ?: 0.0) * unit).toLong()
    }

    /**
     * Connected sockets only: skips listening/unconnected ones and loopback.
     * TCP states kept: ESTABLISHED (1), SYN_SENT (2), CLOSE_WAIT (8).
     */
    fun procNet(text: String, proto: String): List<Sock> = text.lineSequence().drop(1).mapNotNull { line ->
        val f = line.trim().split(Regex("\\s+"))
        if (f.size < 8) return@mapNotNull null
        val (ipHex, portHex) = f[2].split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
        val state = f[3].toIntOrNull(16) ?: return@mapNotNull null
        val uid = f[7].toIntOrNull() ?: return@mapNotNull null
        val port = portHex.toIntOrNull(16) ?: return@mapNotNull null
        if (port == 0) return@mapNotNull null
        if (proto.startsWith("tcp") && state !in setOf(1, 2, 8)) return@mapNotNull null
        val ip = hexIp(ipHex) ?: return@mapNotNull null
        if (isLoopback(ip)) return@mapNotNull null
        Sock(proto.removeSuffix("6"), ip, port, state, uid)
    }.toList()

    /**
     * /proc/net addresses are 32-bit words in little-endian host order.
     * IPv4-mapped IPv6 (::ffff:a.b.c.d) is returned as plain IPv4.
     */
    fun hexIp(hex: String): String? {
        if (hex.length == 8) return v4(hex)
        if (hex.length != 32) return null
        val words = hex.chunked(8)
        if (words[0] == "00000000" && words[1] == "00000000" && words[2] == "FFFF0000") return v4(words[3])
        val bytes = words.flatMap { w -> w.chunked(2).reversed() }.map { it.toInt(16).toByte() }
        return ipv6(bytes.toByteArray())
    }

    /** RFC 5952 text form (lowercase, longest zero run as "::"), matching across sources. */
    fun ipv6(bytes: ByteArray): String {
        val groups = (0 until 8).map { g ->
            (((bytes[g * 2].toInt() and 0xFF) shl 8) or (bytes[g * 2 + 1].toInt() and 0xFF)).toString(16)
        }
        return compress(groups)
    }

    private fun v4(word: String): String? = runCatching {
        word.chunked(2).reversed().joinToString(".") { it.toInt(16).toString() }
    }.getOrNull()

    /** Collapses the longest run of zero groups to "::". */
    private fun compress(groups: List<String>): String {
        var bestStart = -1
        var bestLen = 0
        var i = 0
        while (i < groups.size) {
            if (groups[i] == "0") {
                val start = i
                while (i < groups.size && groups[i] == "0") i++
                if (i - start > bestLen) { bestStart = start; bestLen = i - start }
            } else i++
        }
        if (bestLen < 2) return groups.joinToString(":")
        val head = groups.subList(0, bestStart).joinToString(":")
        val tail = groups.subList(bestStart + bestLen, groups.size).joinToString(":")
        return "$head::$tail"
    }

    private fun isLoopback(ip: String) = ip.startsWith("127.") || ip == "::1" || ip == "0.0.0.0" || ip == "::"

    /** Highest reading per sensor type from `dumpsys thermalservice` (HAL section), e.g. cpu → 41.2. */
    fun temperatures(text: String): Map<String, Float> {
        val section = text.substringAfter("Current temperatures from HAL:", "").lineSequence()
            .drop(1).takeWhile { it.trimStart().startsWith("Temperature{") }
        val out = LinkedHashMap<String, Float>()
        section.forEach { line ->
            val value = Regex("mValue=([-\\d.]+)").find(line)?.groupValues?.get(1)?.toFloatOrNull() ?: return@forEach
            val type = Regex("mType=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
            val name = THERMAL_TYPES[type] ?: return@forEach // skips battery-current/voltage "sensors"
            if (value.isNaN() || value <= -100f) return@forEach
            out[name] = maxOf(out[name] ?: value, value)
        }
        return out
    }

    private val THERMAL_TYPES = mapOf(
        0 to "cpu", 1 to "gpu", 2 to "bat", 3 to "skin", 4 to "usb", 5 to "pa", 9 to "npu", 10 to "tpu",
        11 to "disp", 12 to "modem", 13 to "soc", 14 to "wifi", 15 to "cam", 16 to "flash", 17 to "spkr", 18 to "amb",
    )

    fun wakeLocks(text: String): List<WakeLock> {
        val block = text.substringAfter("Wake Locks: size=", "").lineSequence().drop(1).takeWhile { it.isNotBlank() }
        return block.mapNotNull { line ->
            val level = line.trim().substringBefore(' ').removeSuffix("_WAKE_LOCK").ifEmpty { return@mapNotNull null }
            val tag = line.substringAfter('\'', "").substringBefore('\'').ifEmpty { return@mapNotNull null }
            val ws = Regex("ws=WorkSource\\{(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
            val owner = Regex("\\(uid=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            WakeLock(level.lowercase(), tag, ws ?: owner)
        }.toList()
    }

    /** First session in PLAYING state that has a title. */
    fun nowPlaying(text: String): NowPlaying? {
        var pkg: String? = null
        var playing = false
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("package=") -> {
                    pkg = line.removePrefix("package=")
                    playing = false
                }
                line.startsWith("state=PlaybackState {") -> playing = "state=PLAYING" in line
                line.startsWith("metadata:") && playing && pkg != null -> {
                    val parts = line.substringAfter("description=", "").split(", ")
                    val title = parts.getOrNull(0)?.takeIf { it.isNotBlank() && it != "null" } ?: return@forEach
                    return NowPlaying(pkg!!, title, parts.getOrNull(1)?.takeIf { it.isNotBlank() && it != "null" })
                }
            }
        }
        return null
    }

    private val LOG_LINE = Regex("^\\s*(\\d+\\.\\d+)\\s+\\d+\\s+\\d+\\s+([VDIWEF])\\s+(.*?)\\s*: (.*)$")

    fun logcat(text: String): List<LogLine> = text.lineSequence().mapNotNull { line ->
        val m = LOG_LINE.find(line) ?: return@mapNotNull null
        LogLine(m.groupValues[1].toDouble(), m.groupValues[2][0], m.groupValues[3], m.groupValues[4])
    }.toList()

    /** Per-core counters, indexed by core number. */
    fun cpuTicks(stat: String): Map<Int, CoreTicks> = stat.lineSequence().mapNotNull { line ->
        if (!line.startsWith("cpu") || line.startsWith("cpu ")) return@mapNotNull null
        val f = line.trim().split(Regex("\\s+"))
        val core = f[0].removePrefix("cpu").toIntOrNull() ?: return@mapNotNull null
        val n = f.drop(1).mapNotNull { it.toLongOrNull() }
        if (n.size < 4) return@mapNotNull null
        val idle = n[3] + (n.getOrNull(4) ?: 0)
        core to CoreTicks(n.sum() - idle, n.sum())
    }.toMap()

    private val DRAIN_LINE = Regex("^\\s*UID (\\S+): ([\\d.]+)(.*)$")
    // "cpu=0.1" but not the "fg=" in "cpu:fg=0.1".
    private val DRAIN_PART = Regex("(?<![:\\w])(\\w+)=([\\d.]+)")

    /** `dumpsys batterystats --usage`: per-app mAh since the last full charge. */
    fun batteryUsage(text: String): List<Drain> = text.lineSequence().mapNotNull { line ->
        val m = DRAIN_LINE.find(line) ?: return@mapNotNull null
        val uid = batteryUid(m.groupValues[1]) ?: return@mapNotNull null
        val mah = m.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
        val mostly = DRAIN_PART.findAll(m.groupValues[3])
            .mapNotNull { p -> p.groupValues[2].toDoubleOrNull()?.let { p.groupValues[1] to it } }
            .maxByOrNull { it.second }?.first
        Drain(uid, mah, mostly)
    }.toList()

    /** "1000", "u0a195" (user 0, app 195) → Android uid. Isolated processes are skipped. */
    fun batteryUid(token: String): Int? {
        token.toIntOrNull()?.let { return it }
        val app = Regex("^u(\\d+)a(\\d+)$").find(token) ?: return null
        return app.groupValues[1].toInt() * 100_000 + FIRST_APP_UID + app.groupValues[2].toInt()
    }

    /** `pm list packages -U` → uid to packages (shared uids list several). */
    fun pmPackages(text: String): Map<Int, List<String>> {
        val out = HashMap<Int, MutableList<String>>()
        text.lineSequence().forEach { line ->
            val pkg = line.substringAfter("package:", "").substringBefore(' ').ifEmpty { return@forEach }
            line.substringAfter("uid:", "").split(',').mapNotNull { it.trim().toIntOrNull() }.forEach { uid ->
                out.getOrPut(uid) { mutableListOf() } += pkg
            }
        }
        return out
    }
}
