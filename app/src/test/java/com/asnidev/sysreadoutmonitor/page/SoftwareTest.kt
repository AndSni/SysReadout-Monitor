package com.asnidev.sysreadoutmonitor.page

import com.asnidev.sysreadoutmonitor.term.Thresholds
import com.asnidev.sysreadoutmonitor.term.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class SoftwareTest {

    @Test
    fun androidNames() {
        assertEquals("12", Software.androidName(31))
        assertEquals("14", Software.androidName(34))
        assertNull(Software.androidName(99))
    }

    @Test
    fun patchAge() {
        val today = LocalDate.of(2026, 9, 28)
        assertEquals(454L, Software.patchAgeDays("2025-07-01", today))
        assertNull(Software.patchAgeDays("n/a", today))
        assertNull(Software.patchAgeDays(null, today))
        assertEquals("this month", Software.age(20))
        assertEquals("1 month old", Software.age(40))
        assertEquals("1 year 3 months old", Software.age(454))
        assertEquals("2 years old", Software.age(730))
        assertEquals(Tone.GOOD, Thresholds.patchAge(90))
        assertEquals(Tone.WARN, Thresholds.patchAge(91))
        assertEquals(Tone.WARN, Thresholds.patchAge(180))
        assertEquals(Tone.CRIT, Thresholds.patchAge(181))
    }

    @Test
    fun kernelBuildFromProcVersion() {
        // Sony Xperia 10 IV, Android 14.
        val v = "Linux version 5.4.289-qgki-g20a42b042cf2 (hudsonslave@ip-10-26-21-101) (Android (6877366 based on r383902b1) " +
            "clang version 11.0.2 (https://android.googlesource.com/toolchain/llvm-project b397f81060ce6d701042b782172ed13bee898b79), " +
            "LLD 11.0.2 (https://android.googlesource.com/toolchain/llvm-project b397f81060ce6d701042b782172ed13bee898b79)) " +
            "#1 SMP PREEMPT Tue Jun 17 13:59:20 JST 2025"
        assertEquals("#1 SMP PREEMPT Tue Jun 17 13:59:20 JST 2025 · clang 11.0.2", Software.kernelBuild(v))
        assertNull(Software.kernelBuild(""))
    }

    @Test
    fun playUpdateAndBaseband() {
        assertEquals("2025-01-01", Software.playUpdate("2025-01-01S+"))
        assertEquals("v1", Software.playUpdate("v1"))
        assertNull(Software.playUpdate(null))
        assertEquals("strait.gen-01223-30", Software.baseband("strait.gen-01223-30,strait.gen-01223-30"))
        assertNull(Software.baseband("unknown"))
    }

    @Test
    fun verifiedBootColours() {
        assertEquals(Tone.GOOD, Thresholds.verifiedBoot("green"))
        assertEquals(Tone.WARN, Thresholds.verifiedBoot("yellow"))
        assertEquals(Tone.CRIT, Thresholds.verifiedBoot("orange"))
    }
}
