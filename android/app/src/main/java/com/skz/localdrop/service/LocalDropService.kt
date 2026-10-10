package com.skz.localdrop.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.skz.localdrop.LocalDropApp
import com.skz.localdrop.MainActivity
import com.skz.localdrop.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps LocalDrop reachable in the background: a foreground service of type connectedDevice
 * with the "LocalDrop is running" notification. The network code itself lives in [com.skz.localdrop.core.Core].
 */
class LocalDropService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    private var online = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        Notifications.channels(this)
        Notifier.channels(this, (application as LocalDropApp).core)
        applyTypes()
        val core = (application as LocalDropApp).core
        core.start()
        scope.launch {
            core.state.map { s -> s.devices.count { it.online } }.distinctUntilChanged().collect { n ->
                online = n
                getSystemService(NotificationManager::class.java).notify(Notifications.ID_SERVICE, build(n))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    /** The service types follow what is going on: playing sound, capturing it, or just staying reachable. */
    fun applyTypes() {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (playing) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (projecting) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        runCatching { ServiceCompat.startForeground(this, Notifications.ID_SERVICE, build(online), types) }
            .onFailure { android.util.Log.w(com.skz.localdrop.core.TAG, "foreground types $types refused", it) }
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    private fun build(online: Int): Notification {
        val core = (application as LocalDropApp).core
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, Notifications.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(core.str(R.string.n_running))
            .setContentText(core.plural(R.plurals.n_online, online))
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        @Volatile private var instance: LocalDropService? = null
        @Volatile private var playing = false
        @Volatile var projecting = false

        fun playing(ctx: Context, on: Boolean) {
            playing = on
            update()
        }

        /** Apply the service types right now (must be on the main thread). */
        fun applyNow() {
            instance?.applyTypes()
        }

        fun update() {
            instance?.let { s -> android.os.Handler(android.os.Looper.getMainLooper()).post { s.applyTypes() } }
        }

        fun start(ctx: Context) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, LocalDropService::class.java)) }
        }
    }
}

object Notifications {
    const val CH_SERVICE = "service"
    const val ID_SERVICE = 1

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val core = (ctx.applicationContext as LocalDropApp).core
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, core.str(R.string.ch_service), NotificationManager.IMPORTANCE_MIN).apply {
            setShowBadge(false)
        })
    }
}
