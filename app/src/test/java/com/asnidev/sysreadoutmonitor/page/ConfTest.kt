package com.asnidev.sysreadoutmonitor.page

import com.asnidev.sysreadoutmonitor.data.MonitorPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfTest {

    private val d = MonitorPrefs()

    @Test
    fun intervalCyclesThroughTheChoices() {
        var p = d.copy(intervalSec = 1)
        val seen = (1..4).map { p = Conf.apply(p, ConfAction.Interval); p.intervalSec }
        assertEquals(listOf(2, 5, 10, 1), seen)
    }

    @Test
    fun pagesHideAndShowButConfStays() {
        val hidden = Conf.apply(d, ConfAction.TogglePage(Page.APPS))
        assertEquals(setOf("apps"), hidden.hidden)
        assertFalse(Page.APPS in Page.arrange(hidden.order, hidden.hidden))
        assertEquals(d.hidden, Conf.apply(hidden, ConfAction.TogglePage(Page.APPS)).hidden)
        assertEquals(d, Conf.apply(d, ConfAction.TogglePage(Page.CONF)))
    }

    @Test
    fun movingAPageKeepsHiddenOnesInPlace() {
        val p = Conf.apply(d.copy(hidden = setOf("mem")), ConfAction.MovePage(Page.POWER, -1))
        assertEquals(listOf("sys", "cpu", "power", "mem", "net", "scan", "apps", "sensors", "storage", "journal"), p.order)
        assertEquals(listOf(Page.SYS, Page.CPU, Page.POWER, Page.NET), Page.arrange(p.order, p.hidden).take(4))
        // Already first: nothing to do.
        assertEquals(d, Conf.apply(d, ConfAction.MovePage(Page.SYS, -1)))
    }

    @Test
    fun togglesFlipTheirSetting() {
        Setting.entries.forEach { s ->
            val flipped = Conf.apply(d, ConfAction.Toggle(s))
            assertEquals(s.name, !s.get(d), s.get(flipped))
            assertEquals(s.name, d, Conf.apply(flipped, ConfAction.Toggle(s)))
        }
    }

    @Test
    fun opensOnSysUnlessAskedToRemember() {
        assertFalse(d.rememberPage)
        assertTrue(Conf.apply(d, ConfAction.Toggle(Setting.REMEMBER_PAGE)).rememberPage)
        assertTrue(d.banner)
    }

    @Test
    fun textSizeResets() {
        assertEquals(MonitorPrefs.DEFAULT_SP, Conf.apply(d.copy(textSp = 18f), ConfAction.TextSize).textSp)
        assertTrue(Conf.apply(d, ConfAction.License("hack")) == d)
    }
}
