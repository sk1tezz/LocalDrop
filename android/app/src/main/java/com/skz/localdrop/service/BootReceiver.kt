package com.skz.localdrop.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the background service after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) LocalDropService.start(ctx)
    }
}
