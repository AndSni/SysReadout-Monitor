package com.asnidev.sysreadoutmonitor.page

object Samplers {
    /** Every page but the shell, which runs the user's commands instead of sampling. */
    fun create(env: Env): Map<Page, PageSampler> = (Page.entries - Page.SHELL).associateWith { page ->
        when (page) {
            Page.SYS -> SysSampler(env)
            Page.CPU -> CpuSampler(env)
            Page.MEM -> MemSampler(env)
            Page.POWER -> PowerSampler(env)
            Page.NET -> NetSampler(env)
            Page.SCAN -> ScanSampler(env)
            Page.APPS -> AppsSampler(env)
            Page.SENSORS -> SensorsSampler(env)
            Page.STORAGE -> StorageSampler(env)
            Page.JOURNAL -> JournalSampler(env)
            Page.CONF -> ConfSampler(env)
            Page.SHELL -> error("the shell has no sampler")
        }
    }
}
