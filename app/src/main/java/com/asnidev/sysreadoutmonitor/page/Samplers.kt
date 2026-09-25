package com.asnidev.sysreadoutmonitor.page

import com.asnidev.sysreadoutmonitor.term.Line
import com.asnidev.sysreadoutmonitor.term.comment

object Samplers {
    fun create(env: Env): Map<Page, PageSampler> = Page.entries.associateWith { page ->
        when (page) {
            Page.SYS -> SysSampler(env)
            Page.CPU -> CpuSampler(env)
            Page.MEM -> MemSampler(env)
            Page.POWER -> PowerSampler(env)
            Page.NET -> NetSampler(env)
            Page.APPS -> AppsSampler(env)
            Page.SENSORS -> SensorsSampler(env)
            Page.STORAGE -> StorageSampler(env)
            else -> Placeholder(page)
        }
    }
}

/** Stands in for a page that isn't built yet. */
private class Placeholder(private val page: Page) : PageSampler {
    override suspend fun sample(): List<Line> = listOf(comment("${page.tab}: not built yet"))
}
