package com.asnidev.sysreadoutmonitor.page

import org.junit.Assert.assertEquals
import org.junit.Test

class PageTest {

    @Test
    fun defaultOrder() {
        assertEquals(Page.entries.toList(), Page.arrange(emptyList(), emptySet()))
    }

    @Test
    fun userOrderFirstThenTheRestInDefaultOrder() {
        val pages = Page.arrange(listOf("journal", "cpu", "nonsense"), setOf("apps"))
        assertEquals(
            listOf(Page.JOURNAL, Page.CPU, Page.SYS, Page.MEM, Page.POWER, Page.NET, Page.SENSORS, Page.STORAGE, Page.CONF),
            pages,
        )
    }

    @Test
    fun confIsAlwaysShownAndLast() {
        assertEquals(listOf(Page.SYS, Page.CONF), Page.arrange(listOf("conf", "sys"), Page.entries.map { it.tab }.toSet() - "sys"))
    }
}
