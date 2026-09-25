package com.asnidev.sysreadoutmonitor.page

import android.content.Context
import android.os.SystemClock
import com.asnidev.sysreadoutmonitor.monitor.FIRST_APP_UID
import com.asnidev.sysreadoutmonitor.monitor.Proc

/**
 * The names people know apps by, for packages, processes and uids. Cached;
 * the cache is dropped every few minutes so new installs get their names.
 * Not thread-safe: used from the coordinator's serial dispatcher only.
 */
class Labels(context: Context) {

    private val pm = context.packageManager
    private val labels = HashMap<String, String?>()
    private val uidNames = HashMap<Int, String>()
    private var clearedAt = SystemClock.elapsedRealtime()

    private fun expire() {
        val now = SystemClock.elapsedRealtime()
        if (now - clearedAt > 10 * 60_000L) {
            labels.clear()
            uidNames.clear()
            clearedAt = now
        }
    }

    /** The app's own label, or null for packages without one. */
    fun appLabel(pkg: String): String? {
        expire()
        if (pkg in labels) return labels[pkg]
        val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
            ?.takeIf { it.isNotBlank() && it != pkg }
        labels[pkg] = label
        return label
    }

    fun pkgLabel(pkg: String): String = appLabel(pkg) ?: shortPkg(pkg)

    /** Packages without a label: trim the vendor prefix instead. */
    fun shortPkg(pkg: String): String =
        PKG_PREFIXES.firstOrNull { pkg.startsWith(it) && pkg.length > it.length }?.let { pkg.removePrefix(it) } ?: pkg

    /**
     * "Google Play services:persistent" for com.google.android.gms.persistent: some
     * processes are named package.part without the usual ":" separator.
     */
    fun procLabel(p: Proc): String {
        if (!p.isApp) return p.name
        var pkg = p.name.substringBefore(':')
        val parts = ArrayDeque<String>()
        while (appLabel(pkg) == null && parts.size < 2 && '.' in pkg) {
            parts.addFirst(pkg.substringAfterLast('.'))
            pkg = pkg.substringBeforeLast('.')
        }
        val label = appLabel(pkg) ?: return shortPkg(p.name)
        val suffix = listOf(parts.joinToString("."), p.name.substringAfter(':', "")).filter { it.isNotEmpty() }
        return if (suffix.isEmpty()) label else label + ":" + suffix.joinToString(":")
    }

    fun uidLabel(uid: Int): String {
        expire()
        return uidNames.getOrPut(uid) {
            when (uid) {
                -1 -> "?"
                0 -> "root"
                1000 -> "system"
                SHELL_UID -> "shell"
                -4 -> "removed apps" // NetworkStats.Bucket.UID_REMOVED
                -5 -> "tethering" // NetworkStats.Bucket.UID_TETHERING
                else -> {
                    val pkgs = pm.getPackagesForUid(uid)?.toList().orEmpty()
                    // Shared uids list several packages; prefer one with a real name.
                    pkgs.firstNotNullOfOrNull { appLabel(it) }
                        ?: pkgs.firstOrNull()?.let(::shortPkg)
                        ?: if (uid < FIRST_APP_UID) "uid $uid" else "app $uid"
                }
            }
        }
    }

    companion object {
        const val SHELL_UID = 2000
        private val PKG_PREFIXES = listOf("com.google.android.apps.", "com.google.android.", "com.android.", "com.", "org.")
    }
}
