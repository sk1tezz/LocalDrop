package com.skz.localdrop.core

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager

/** CPU and Wi-Fi stay awake while files or sound are moving, even with the screen off. */
class Locks(ctx: Context) {
    private val wake = ctx.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalDrop:busy")
        .apply { setReferenceCounted(false) }
    @Suppress("DEPRECATION")
    private val wifi = ctx.applicationContext.getSystemService(WifiManager::class.java)
        ?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "LocalDrop:busy")?.apply { setReferenceCounted(false) }
    private var held = false

    @Synchronized
    fun update(busy: Boolean) {
        if (busy == held) return
        held = busy
        if (busy) {
            wake.acquire(6 * 60 * 60 * 1000L)
            runCatching { wifi?.acquire() }
        } else {
            runCatching { wake.release() }
            runCatching { wifi?.release() }
        }
    }
}
