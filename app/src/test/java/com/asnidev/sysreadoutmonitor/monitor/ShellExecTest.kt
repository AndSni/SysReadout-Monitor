package com.asnidev.sysreadoutmonitor.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class ShellExecTest {

    @Test
    fun returnsTheOutput() {
        assertEquals("hi\n", ShellExec.run("echo hi", 5_000))
    }

    @Test
    fun includesErrorOutput() {
        assertEquals("oops\n", ShellExec.run("echo oops >&2", 5_000))
    }

    @Test
    fun aCommandThatRunsTooLongIsNullNotEmpty() {
        val started = System.nanoTime()
        assertNull(ShellExec.run("sleep 10", 300))
        assertTrue("gave up in time", (System.nanoTime() - started) / 1_000_000 < 3_000)
    }

    @Test
    fun capsLongOutputAndDrainsTheRest() {
        assertEquals("abcd", ShellExec.readCapped(StringReader("abcdefghij".repeat(5_000)), 4))
    }

    @Test
    fun resolveKeepsWhatAnsweredWithinTheBudget() {
        val started = System.nanoTime()
        val found = ShellExec.resolve(listOf("1.1.1.1", "9.9.9.9", "1.1.1.1"), budgetMs = 500) { ip ->
            if (ip == "9.9.9.9") {
                Thread.sleep(5_000)
                "slow.example"
            } else {
                "fast.example"
            }
        }
        assertEquals(mapOf("1.1.1.1" to "fast.example"), found)
        assertTrue("one budget for all lookups", (System.nanoTime() - started) / 1_000_000 < 2_000)
    }

    @Test
    fun resolveLeavesOutIpsWithoutAName() {
        assertEquals(emptyMap<String, String>(), ShellExec.resolve(listOf("10.0.0.1"), 1_000) { null })
    }
}
