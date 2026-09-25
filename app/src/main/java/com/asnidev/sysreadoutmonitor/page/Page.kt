package com.asnidev.sysreadoutmonitor.page

/** The pages, in their default order. [command] is flavour text for the page's prompt line. */
enum class Page(val tab: String, val command: String) {
    SYS("sys", "fastfetch"),
    CPU("cpu", "top"),
    MEM("mem", "free -h"),
    POWER("power", "upower -d"),
    NET("net", "ip addr; ss -tunp"),
    APPS("apps", "dumpsys usagestats"),
    SENSORS("sensors", "sensors"),
    STORAGE("storage", "df -h"),
    JOURNAL("journal", "journalctl -f"),
    CONF("conf", "nano ~/.config/srm.conf");

    companion object {
        fun byTab(tab: String): Page? = entries.firstOrNull { it.tab == tab }

        /**
         * The pages to show, in the user's [order] (tab names; unknown ones are
         * skipped, missing ones keep their default place at the end). Conf can't
         * be hidden, or there would be no way back, and it always comes last.
         */
        fun arrange(order: List<String>, hidden: Set<String>): List<Page> {
            val ordered = (order.mapNotNull(::byTab) + entries).distinct()
            return ordered.filter { it != CONF && it.tab !in hidden } + CONF
        }
    }
}
