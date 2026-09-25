package com.asnidev.sysreadoutmonitor.page

import com.asnidev.sysreadoutmonitor.data.MonitorPrefs

/** An on/off line in srm.conf. */
enum class Setting(val key: String, val get: (MonitorPrefs) -> Boolean, val set: (MonitorPrefs, Boolean) -> MonitorPrefs) {
    REVERSE_DNS("reverse_dns", { it.resolveHosts }, { p, v -> p.copy(resolveHosts = v) }),
    SYSTEM("system", { it.evSystem }, { p, v -> p.copy(evSystem = v) }),
    APPS("app_switches", { it.evApps }, { p, v -> p.copy(evApps = v) }),
    SERVICES("services", { it.evServices }, { p, v -> p.copy(evServices = v) }),
    SCREEN("screen_lock", { it.evScreen }, { p, v -> p.copy(evScreen = v) }),
    PROCS("processes", { it.evProcs }, { p, v -> p.copy(evProcs = v) }),
    CONNS("connections", { it.evConns }, { p, v -> p.copy(evConns = v) }),
    DNS("dns", { it.evDns }, { p, v -> p.copy(evDns = v) }),
    NOTIFICATIONS("notifications", { it.evNotif }, { p, v -> p.copy(evNotif = v) }),
    NOTIF_TITLES("notif_titles", { it.notifTitles }, { p, v -> p.copy(notifTitles = v) }),
    LOGCAT("logcat", { it.evLogcat }, { p, v -> p.copy(evLogcat = v) }),
    LOGCAT_WARNINGS("logcat_warnings", { it.logcatWarnings }, { p, v -> p.copy(logcatWarnings = v) }),
}

/** What a tap on the conf page changes. */
sealed interface ConfAction {
    data object Interval : ConfAction
    data object TextSize : ConfAction
    data class TogglePage(val page: Page) : ConfAction
    data class MovePage(val page: Page, val delta: Int) : ConfAction
    data class Toggle(val setting: Setting) : ConfAction
    data class License(val name: String) : ConfAction
}

object Conf {
    /** Every page but conf in the user's order, hidden ones included (they keep their place). */
    fun order(p: MonitorPrefs): List<Page> = Page.arrange(p.order, emptySet()) - Page.CONF

    /** The settings after [action]; actions that aren't settings leave them as they are. */
    fun apply(p: MonitorPrefs, action: ConfAction): MonitorPrefs = when (action) {
        ConfAction.Interval -> {
            val all = MonitorPrefs.INTERVALS
            p.copy(intervalSec = all[(all.indexOf(p.intervalSec) + 1) % all.size])
        }
        ConfAction.TextSize -> p.copy(textSp = MonitorPrefs.DEFAULT_SP)
        is ConfAction.TogglePage -> if (action.page == Page.CONF) p else {
            val tab = action.page.tab
            p.copy(hidden = if (tab in p.hidden) p.hidden - tab else p.hidden + tab)
        }
        is ConfAction.MovePage -> {
            val list = order(p).toMutableList()
            val from = list.indexOf(action.page)
            val to = (from + action.delta).coerceIn(0, list.lastIndex)
            if (from < 0 || from == to) p else p.copy(order = list.apply { add(to, removeAt(from)) }.map { it.tab })
        }
        is ConfAction.Toggle -> action.setting.set(p, !action.setting.get(p))
        is ConfAction.License -> p
    }
}
