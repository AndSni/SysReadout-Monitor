package com.asnidev.sysreadoutmonitor.monitor

/**
 * Access SR Monitor can switch on for itself through Shizuku's shell, only when
 * the user taps it: the same switches as in Android's settings. The grants stay
 * after Shizuku stops. Ported from SysReadout Launcher, without its lock service.
 */
object ShizukuSetup {

    fun listenerComponent(pkg: String) = "$pkg/${NotifListener::class.java.name}"

    /** Usage access; true when the shell ran the command. */
    suspend fun usage(bridge: ShizukuBridge, pkg: String, userId: Int): Boolean =
        bridge.exec("appops set --user $userId $pkg GET_USAGE_STATS allow") != null

    /** Notification access; true when the shell ran the command. */
    suspend fun notifications(bridge: ShizukuBridge, pkg: String, userId: Int): Boolean =
        bridge.exec("cmd notification allow_listener ${listenerComponent(pkg)} $userId") != null
}
