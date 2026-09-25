package com.asnidev.sysreadoutmonitor.term

import com.asnidev.sysreadoutmonitor.log.Access
import com.asnidev.sysreadoutmonitor.page.ConfAction
import com.asnidev.sysreadoutmonitor.page.Page

/** What a tappable span does. The activity carries it out (it owns the permission launchers). */
sealed interface Tap {
    /** Ask for what [access] needs: a runtime permission, a settings screen or Shizuku's dialog. */
    data class Grant(val access: Access) : Tap

    /** Switch to another page, e.g. conf for the DNS monitor's explanation, scrolled to the line with [anchor]. */
    data class Goto(val page: Page, val anchor: String? = null) : Tap

    /** Follow one Bluetooth device's signal on the scan page (null stops). */
    data class Track(val address: String?) : Tap

    /** A system screen by its intent action, e.g. location settings or "turn Bluetooth on". */
    data class Intent(val action: String) : Tap

    /** A setting on the conf page. */
    data class Conf(val action: ConfAction) : Tap

    /** Switch the DNS monitor on (Android asks for VPN consent the first time) or off. */
    data class DnsMonitor(val on: Boolean) : Tap

    /** Android's settings page for this app, the only place to revoke a permission. */
    data object AppSettings : Tap

    data class Url(val url: String) : Tap
}
