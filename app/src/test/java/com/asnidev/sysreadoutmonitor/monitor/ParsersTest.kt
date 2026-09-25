package com.asnidev.sysreadoutmonitor.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Inputs are real output captured from an Android 14 emulator over adb shell. */
class ParsersTest {

    @Test
    fun top() {
        val out = """
            10609 10195 30.7 156M com.sysreadoutmonitor.app
              764 10167 11.5 173M com.android.systemui
              404  1000  7.6  19M surfaceflinger
            10804     0  0.0    0 [kworker/u8:1-events_unbound]
        """.trimIndent()
        val procs = Parsers.top(out)
        assertEquals(4, procs.size)
        assertEquals(Proc(10609, 10195, 30.7f, 156L shl 20, "com.sysreadoutmonitor.app"), procs[0])
        assertEquals(19L shl 20, procs[2].resBytes)
        assertEquals("[kworker/u8:1-events_unbound]", procs[3].name)
        assertTrue(procs[0].isApp)
        assertTrue(!procs[2].isApp)
    }

    @Test
    fun sizes() {
        assertEquals(0L, Parsers.size("0"))
        assertEquals(812L shl 10, Parsers.size("812K"))
        assertEquals((4.7 * (1 shl 20)).toLong(), Parsers.size("4.7M"))
        assertEquals((1.2 * (1L shl 30)).toLong(), Parsers.size("1.2G"))
    }

    @Test
    fun tcp6KeepsEstablishedDropsListening() {
        val out = """
              sl  local_address                         remote_address                        st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
               0: 00000000000000000000000000000000:15B3 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000  2000        0 28678 1 0000000000000000 100 0 0 10 0
               1: 0000000000000000FFFF00001002000A:EA08 0000000000000000FFFF000017071A68:01BB 08 00000000:00000028 00:00000000 00000000 10194        0 81585 1 0000000000000000 20 4 30 10 -1
               2: 0000000000000000FFFF00001002000A:9FB0 0000000000000000FFFF0000BC837D4A:146C 01 00000000:00000000 00:00000000 00000000 10125        0 86646 1 0000000000000000 122 4 31 10 -1
        """.trimIndent()
        val socks = Parsers.procNet(out, "tcp6")
        assertEquals(2, socks.size)
        assertEquals(Sock("tcp", "104.26.7.23", 443, 8, 10194), socks[0])
        assertEquals("74.125.131.188:5228", socks[1].remote)
        assertEquals(10125, socks[1].uid)
    }

    @Test
    fun ipv4AndIpv6Addresses() {
        assertEquals("127.0.0.1", Parsers.hexIp("0100007F"))
        assertEquals("10.0.2.16", Parsers.hexIp("1002000A"))
        // 2a00:1450:4001:82b::200e in /proc word order
        assertEquals("2a00:1450:4001:82b::200e", Parsers.hexIp(flipWords("2A0014504001082B000000000000200E")))
    }

    /** Builds a /proc-style (little-endian words) hex string from a big-endian one. */
    private fun flipWords(bigEndian: String) = bigEndian.chunked(8).joinToString("") { w -> w.chunked(2).reversed().joinToString("") }

    @Test
    fun pmPackages() {
        val out = """
            package:com.android.systemui uid:10167
            package:com.android.keychain uid:1000
            package:com.android.inputdevices uid:1000
        """.trimIndent()
        val map = Parsers.pmPackages(out)
        assertEquals(listOf("com.android.systemui"), map[10167])
        assertEquals(listOf("com.android.keychain", "com.android.inputdevices"), map[1000])
    }
}

class ShellParsersTest {

    @Test
    fun temperaturesKeepsHighestPerTypeAndSkipsBcl() {
        val out = """
            Current temperatures from HAL:
            	Temperature{mValue=30.8, mType=3, mName=test temperature sensor, mStatus=0}
            	Temperature{mValue=41.5, mType=0, mName=cpu0, mStatus=0}
            	Temperature{mValue=44.0, mType=0, mName=cpu7, mStatus=0}
            	Temperature{mValue=3900.0, mType=6, mName=vbat, mStatus=0}
            Current cooling devices from HAL:
            	CoolingDevice{mValue=100, mType=0, mName=test cooling device}
        """.trimIndent()
        assertEquals(mapOf("skin" to 30.8f, "cpu" to 44.0f), Parsers.temperatures(out))
    }

    @Test
    fun wakeLocksPreferWorkSourceUid() {
        val out = """
            Wake Locks: size=2
              PARTIAL_WAKE_LOCK              'AudioMix' ACQ=-2s23ms (uid=1041 pid=1234 ws=WorkSource{10123})
              SCREEN_BRIGHT_WAKE_LOCK        'WindowManager' ON_AFTER_RELEASE ACQ=-1m2s (uid=1000 pid=552)

            Suspend Blockers: size=5
        """.trimIndent()
        assertEquals(
            listOf(WakeLock("partial", "AudioMix", 10123), WakeLock("screen_bright", "WindowManager", 1000)),
            Parsers.wakeLocks(out),
        )
    }

    @Test
    fun nowPlayingFindsThePlayingSession() {
        val out = """
                package=com.google.android.googlequicksearchbox
                state=PlaybackState {state=NONE(0), position=0}
                metadata: size=0, description=null, null, null
                package=com.spotify.music
                state=PlaybackState {state=PLAYING(3), position=12345}
                metadata: size=11, description=Paranoid Android, Radiohead, OK Computer
        """.trimIndent()
        assertEquals(NowPlaying("com.spotify.music", "Paranoid Android", "Radiohead"), Parsers.nowPlaying(out))
    }

    @Test
    fun logcatEpochLines() {
        val out = """
            --------- beginning of main
                     1790263597.345  1234  5678 E ActivityManager: ANR in com.example.app
                     1790263598.001     0     0 W logd    : something: with a colon
        """.trimIndent()
        val lines = Parsers.logcat(out)
        assertEquals(2, lines.size)
        assertEquals(LogLine(1790263597.345, 'E', "ActivityManager", "ANR in com.example.app"), lines[0])
        assertEquals("logd", lines[1].tag)
        assertEquals("something: with a colon", lines[1].message)
    }

    @Test
    fun cpuTicksPerCore() {
        val stat = """
            cpu  403483 66809 120066 12394435 2157 17894 1768 18990 0 0
            cpu0 131802 10275 33057 3065859 555 4708 511 5285 0 0
            cpu1 44934 24962 25477 3150163 584 5624 477 4901 0 0
        """.trimIndent()
        val ticks = Parsers.cpuTicks(stat)
        assertEquals(setOf(0, 1), ticks.keys)
        val total0 = 131802L + 10275 + 33057 + 3065859 + 555 + 4708 + 511 + 5285
        assertEquals(CoreTicks(total0 - 3065859 - 555, total0), ticks[0])
    }
}

class DnsTest {

    /** A query for example.com (A), as an app's resolver would send it. */
    private val query = byteArrayOf(
        0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
        3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0,
        0x00, 0x01, 0x00, 0x01,
    )

    /** The answer: a CNAME-free A record pointing back at the question name via compression. */
    private val response = query.copyOf().also { it[2] = 0x81.toByte(); it[3] = 0x80.toByte(); it[7] = 1 } + byteArrayOf(
        0xC0.toByte(), 0x0C, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x0E, 0x10, 0x00, 0x04,
        93, 184.toByte(), 215.toByte(), 14,
    )

    @Test
    fun questionAndAnswers() {
        assertEquals(Dns.Question("example.com", Dns.TYPE_A), Dns.question(query))
        assertEquals(listOf("93.184.215.14"), Dns.addresses(response))
    }

    @Test
    fun udpRoundTrip() {
        val src = byteArrayOf(10, 111, 222.toByte(), 1)
        val dst = byteArrayOf(10, 111, 222.toByte(), 2)
        val packet = Packets.buildUdp4(src, dst, 40000, 53, query)
        // A valid IPv4 header sums to zero with its checksum included.
        assertEquals(0, Packets.checksum(packet, 0, 20))
        val parsed = Packets.parseUdp4(packet, packet.size)!!
        assertEquals(40000, parsed.srcPort)
        assertEquals(53, parsed.dstPort)
        assertTrue(parsed.payload.contentEquals(query))
        assertTrue(parsed.dstIp.contentEquals(dst))
    }

    @Test
    fun ipv6MatchesProcNetForm() {
        val bytes = byteArrayOf(0x2A, 0x00, 0x14, 0x50, 0x40, 0x01, 0x08, 0x2B, 0, 0, 0, 0, 0, 0, 0x20, 0x0E)
        assertEquals("2a00:1450:4001:82b::200e", Parsers.ipv6(bytes))
    }
}

class BatteryTest {

    @Test
    fun usageLinesWithTheBiggestConsumer() {
        val out = """
              Estimated power use (mAh):
                Capacity: 3000, Computed drain: 0, actual drain: 0
                Global
                  screen: 2.50 apps: 2.50 duration: 16h 38m 27s 528ms
                UID 1000: 50.0 fg: 0.0000257 cached: 0.00000242 ( cpu=0.0427 (35m 5s 92ms) cpu:fg=0.0000257 (926ms) sensors=49.9 (16h 38m 9s 180ms) )
                UID u0a195: 39.4 fg: 0.00681 ( screen=0.639 (2h 28m 58s 255ms) cpu=0.00685 (5m 47s 52ms) sensors=38.7 (2h 34m 59s 829ms) )
                UID u10a12: 0.5 ( cpu=0.5 (1m) )
        """.trimIndent()
        assertEquals(
            listOf(Drain(1000, 50.0, "sensors"), Drain(10195, 39.4, "sensors"), Drain(1_010_012, 0.5, "cpu")),
            Parsers.batteryUsage(out),
        )
    }
}
