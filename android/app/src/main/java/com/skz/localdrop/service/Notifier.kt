package com.skz.localdrop.service

import com.skz.localdrop.core.Core
import com.skz.localdrop.protocol.files.Transfer

/** System notifications (filled in with the notification work). */
class Notifier(private val core: Core) {
    fun offer(t: Transfer, auto: Boolean, text: String) {}
    fun received(t: Transfer, location: String) {}
}
