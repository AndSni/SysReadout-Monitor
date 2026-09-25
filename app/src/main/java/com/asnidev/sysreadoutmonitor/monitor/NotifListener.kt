package com.asnidev.sysreadoutmonitor.monitor

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.time.LocalDate

/**
 * Runs once the user grants notification access. Records who notified and
 * when (plus the title, used only if the user turns titles on), in memory
 * only; nothing is stored on disk or sent anywhere.
 */
class NotifListener : NotificationListenerService() {

    override fun onListenerConnected() {
        NotifLog.connected = true
        NotifLog.active = activeCount()
    }

    override fun onListenerDisconnected() {
        NotifLog.connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        NotifLog.active = activeCount()
        val n = sbn.notification
        // Ongoing ones (music, downloads, navigation) and group summaries aren't new messages.
        if (sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0 || sbn.packageName == packageName) return
        val title = n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        NotifLog.posted(sbn.key, sbn.postTime, sbn.packageName, n.category, title)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NotifLog.active = activeCount()
    }

    private fun activeCount(): Int = runCatching { activeNotifications?.size ?: 0 }.getOrDefault(0)
}

object NotifLog {
    data class Posted(val time: Long, val pkg: String, val category: String?, val title: String?)

    @Volatile var connected = false
    @Volatile var active = 0

    private val events = ArrayDeque<Posted>()
    private val lastByKey = HashMap<String, Long>()
    private val today = HashMap<String, Int>()
    private var day = LocalDate.now().toEpochDay()

    @Synchronized
    fun posted(key: String, time: Long, pkg: String, category: String?, title: String?) {
        // Apps re-post the same notification to update it (progress, edits): count it once.
        val last = lastByKey.put(key, time)
        if (last != null && time - last < 5 * 60_000L) return
        if (LocalDate.now().toEpochDay() != day) {
            day = LocalDate.now().toEpochDay()
            today.clear()
        }
        today[pkg] = (today[pkg] ?: 0) + 1
        events.addLast(Posted(time, pkg, category, title))
        while (events.size > 500) events.removeFirst()
        if (lastByKey.size > 2000) lastByKey.entries.removeAll { time - it.value > 60 * 60_000L }
    }

    @Synchronized
    fun since(time: Long): List<Posted> = events.filter { it.time > time }

    /** Notifications received today, per package, most first. */
    @Synchronized
    fun todayByApp(): List<Pair<String, Int>> =
        if (LocalDate.now().toEpochDay() != day) emptyList() else today.entries.sortedByDescending { it.value }.map { it.key to it.value }

    @Synchronized
    fun todayTotal(): Int = todayByApp().sumOf { it.second }
}
