package com.skz.localdrop.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.skz.localdrop.LocalDropApp
import com.skz.localdrop.MainActivity
import com.skz.localdrop.R
import com.skz.localdrop.core.AppState
import com.skz.localdrop.core.Core
import com.skz.localdrop.protocol.files.Transfer
import com.skz.localdrop.ui.Fmt
import kotlinx.coroutines.launch

/**
 * System notifications: transfer progress with Pause / Cancel, incoming files and pairing
 * requests with Accept / Decline, clipboard with Copy, sound with Stop, and the other notices
 * while the app is in the background (the desktop shows OS notifications when its window is hidden).
 */
class Notifier(private val core: Core) {
    private val ctx = core.ctx
    private val nm get() = NotificationManagerCompat.from(ctx)
    private var shownTransfer = ""
    private var lastProgressAt = 0L
    private var pairShown = ""
    private var audioShown = false

    companion object {
        const val CH_TRANSFERS = "transfers"
        const val CH_REQUESTS = "requests"
        const val CH_CLIP = "clip"
        const val CH_AUDIO = "audio"
        const val CH_EVENTS = "events"
        const val ID_TRANSFER = 2
        const val ID_AUDIO = 3
        const val ID_PAIR = 4
        const val ID_CLIP = 5
        private const val ID_OFFER_BASE = 1000
        private const val ID_EVENT_BASE = 2000

        fun channels(ctx: Context, core: Core) {
            val m = ctx.getSystemService(NotificationManager::class.java)
            fun ch(id: String, name: Int, imp: Int) = m.createNotificationChannel(NotificationChannel(id, core.str(name), imp))
            ch(CH_TRANSFERS, R.string.ch_transfers, NotificationManager.IMPORTANCE_LOW)
            ch(CH_REQUESTS, R.string.ch_requests, NotificationManager.IMPORTANCE_HIGH)
            ch(CH_CLIP, R.string.ch_clip, NotificationManager.IMPORTANCE_DEFAULT)
            ch(CH_AUDIO, R.string.ch_audio, NotificationManager.IMPORTANCE_LOW)
            ch(CH_EVENTS, R.string.ch_events, NotificationManager.IMPORTANCE_DEFAULT)
        }
    }

    private val enabled get() = core.settings.value.notify

    private fun open(screen: String? = null): PendingIntent =
        PendingIntent.getActivity(ctx, screen.hashCode(), Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (screen != null) putExtra("screen", screen)
        }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun action(act: String, arg: String = ""): PendingIntent =
        PendingIntent.getBroadcast(ctx, (act + arg).hashCode(), Intent(ctx, ActionReceiver::class.java).setAction(act).putExtra("arg", arg),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun builder(ch: String) = NotificationCompat.Builder(ctx, ch).setSmallIcon(R.drawable.ic_stat).setColor(0xFF3BC4E0.toInt())

    private fun post(id: Int, n: android.app.Notification) {
        runCatching { nm.notify(id, n) }       // no permission: silently nothing
    }

    // ---- generic notices --------------------------------------------------
    fun event(title: String, text: String) {
        if (!enabled || core.appVisible) return
        post(ID_EVENT_BASE + (title.hashCode() and 0xff), builder(CH_EVENTS).setContentTitle(title).setContentText(text)
            .setContentIntent(open()).setAutoCancel(true).build())
    }

    // ---- files ------------------------------------------------------------
    fun offer(t: Transfer, auto: Boolean, text: String) {
        if (!enabled || auto) return
        post(ID_OFFER_BASE + (t.id.hashCode() and 0xfff), builder(CH_REQUESTS)
            .setContentTitle(core.str(R.string.t_ask)).setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open("files")).setAutoCancel(true)
            .addAction(0, core.str(R.string.decline), action(ActionReceiver.OFFER_NO, t.id))
            .addAction(0, core.str(R.string.accept), action(ActionReceiver.OFFER_YES, t.id))
            .build())
    }

    fun cancelOffer(tid: String) = nm.cancel(ID_OFFER_BASE + (tid.hashCode() and 0xfff))

    fun received(t: Transfer, location: String) {
        if (!enabled || core.appVisible) return
        val name = core.peers.get(t.dev)?.name ?: "?"
        post(ID_EVENT_BASE + 300 + (t.id.hashCode() and 0xff), builder(CH_EVENTS)
            .setContentTitle(core.str(R.string.t_received)).setContentText(core.str(R.string.t_from, t.name, name))
            .setContentIntent(action(ActionReceiver.OPEN, location)).setAutoCancel(true).build())
    }

    // ---- clipboard --------------------------------------------------------
    fun clip(from: String, text: String, clipId: Long) {
        if (!enabled || core.appVisible) return
        post(ID_CLIP, builder(CH_CLIP).setContentTitle(core.str(R.string.n_clip_from, from)).setContentText(text.replace('\n', ' ').take(200))
            .setContentIntent(open("clip")).setAutoCancel(true)
            .addAction(0, core.str(R.string.copy), action(ActionReceiver.CLIP_COPY, clipId.toString()))
            .build())
    }

    // ---- state-driven: progress, pairing, sound -----------------------------
    /** Called whenever the UI state changes. */
    fun update(s: AppState) {
        transfer(s)
        pair(s)
        audio(s)
    }

    private fun transfer(s: AppState) {
        val cur = s.transfers.firstOrNull { it.status == "active" } ?: s.transfers.firstOrNull { it.status == "paused" }
        if (cur == null || !enabled) {
            if (shownTransfer.isNotEmpty()) {
                nm.cancel(ID_TRANSFER)
                shownTransfer = ""
            }
            return
        }
        val now = System.currentTimeMillis()
        if (cur.id == shownTransfer && cur.status == "active" && now - lastProgressAt < 1000) return
        lastProgressAt = now
        shownTransfer = cur.id
        val f = Fmt(core.res())
        val pct = if (cur.size > 0) (cur.done * 100 / cur.size).toInt() else 0
        val dev = s.device(cur.dev)?.name ?: "?"
        val arrow = if (cur.dir == "out") "→ " else "← "
        val state = if (cur.status == "active" && cur.speed > 0) f.rate(cur.speed) + " · " + f.eta((cur.size - cur.done) / cur.speed) else core.str(R.string.st_paused)
        val paused = cur.status == "paused"
        post(ID_TRANSFER, builder(CH_TRANSFERS)
            .setContentTitle(cur.name).setContentText("$arrow$dev · $pct% · $state")
            .setProgress(100, pct, false).setOngoing(!paused).setOnlyAlertOnce(true).setSilent(true)
            .setContentIntent(open("files"))
            .addAction(0, core.str(if (paused) R.string.resume else R.string.pause), action(ActionReceiver.TOGGLE, cur.id))
            .addAction(0, core.str(R.string.cancel), action(ActionReceiver.CANCEL, cur.id))
            .build())
    }

    private fun pair(s: AppState) {
        val p = s.pair
        if (p == null || p.dir != "in") {
            if (pairShown.isNotEmpty()) {
                nm.cancel(ID_PAIR)
                pairShown = ""
            }
            return
        }
        if (pairShown == p.id || core.appVisible) return
        pairShown = p.id
        post(ID_PAIR, builder(CH_REQUESTS)
            .setContentTitle(core.str(R.string.pair_in_title, p.name)).setContentText(core.str(R.string.n_pair_code, p.code))
            .setCategory(NotificationCompat.CATEGORY_CALL).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open()).setAutoCancel(true)
            .addAction(0, core.str(R.string.decline), action(ActionReceiver.PAIR_NO, p.id))
            .addAction(0, core.str(R.string.accept), action(ActionReceiver.PAIR_YES, p.id))
            .build())
    }

    private fun audio(s: AppState) {
        val inc = s.audio.incoming
        val on = s.audio.on || inc != null
        if (!on) {
            if (audioShown) {
                nm.cancel(ID_AUDIO)
                audioShown = false
            }
            return
        }
        val title = if (inc != null) core.str(R.string.audio_listening, s.device(inc.dev)?.name ?: "?")
        else core.str(R.string.audio_streaming, core.plural(R.plurals.n_devices, s.settings.audioTargets.count { it.on }))
        if (audioShown) return
        audioShown = true
        post(ID_AUDIO, builder(CH_AUDIO).setContentTitle(title).setContentText(core.str(R.string.n_audio_text))
            .setOngoing(true).setSilent(true).setContentIntent(open("audio"))
            .addAction(0, core.str(R.string.stop), action(ActionReceiver.AUDIO_STOP))
            .build())
    }
}

/** Notification buttons. */
class ActionReceiver : BroadcastReceiver() {
    companion object {
        const val TOGGLE = "com.skz.localdrop.TOGGLE"
        const val CANCEL = "com.skz.localdrop.CANCEL"
        const val OFFER_YES = "com.skz.localdrop.OFFER_YES"
        const val OFFER_NO = "com.skz.localdrop.OFFER_NO"
        const val PAIR_YES = "com.skz.localdrop.PAIR_YES"
        const val PAIR_NO = "com.skz.localdrop.PAIR_NO"
        const val CLIP_COPY = "com.skz.localdrop.CLIP_COPY"
        const val AUDIO_STOP = "com.skz.localdrop.AUDIO_STOP"
        const val OPEN = "com.skz.localdrop.OPEN"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val core = (ctx.applicationContext as LocalDropApp).core
        val arg = intent.getStringExtra("arg") ?: ""
        when (intent.action) {
            TOGGLE -> core.transferToggle(arg)
            CANCEL -> core.transferCancel(arg)
            OFFER_YES, OFFER_NO -> {
                core.offerRespond(arg, intent.action == OFFER_YES)
                core.notify.cancelOffer(arg)
            }
            PAIR_YES -> core.pairRespond(arg, true, true)
            PAIR_NO -> core.pairRespond(arg, false, false)
            CLIP_COPY -> {
                arg.toLongOrNull()?.let { core.clipCopy(it) }
                NotificationManagerCompat.from(ctx).cancel(Notifier.ID_CLIP)
            }
            AUDIO_STOP -> core.scope.launch {
                if (core.state.value.audio.on) core.audioToggle()
                if (core.state.value.audio.incoming != null) core.audioListenStop()
            }
            OPEN -> core.files.openLocation(arg)
        }
    }
}
