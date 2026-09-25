package com.asnidev.sysreadoutmonitor.term

import com.asnidev.sysreadoutmonitor.log.Access
import com.asnidev.sysreadoutmonitor.page.Page

/** What a tappable span does. The activity carries it out (it owns the permission launchers). */
sealed interface Tap {
    /** Ask for what [access] needs: a runtime permission, a settings screen or Shizuku's dialog. */
    data class Grant(val access: Access) : Tap

    /** Switch to another page, e.g. conf for the DNS monitor's explanation. */
    data class Goto(val page: Page) : Tap
}
