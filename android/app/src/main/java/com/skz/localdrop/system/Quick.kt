package com.skz.localdrop.system

import android.app.Activity
import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.skz.localdrop.LocalDropApp
import com.skz.localdrop.R
import com.skz.localdrop.core.Device
import java.util.concurrent.Executors

/** Paired devices as Direct Share targets in the system share sheet. */
object Shortcuts {
    const val PREFIX = "dev:"
    const val CATEGORY = "com.skz.localdrop.category.SHARE_TARGET"
    private var last: List<String> = emptyList()

    fun update(ctx: Context, devices: List<Device>) {
        val paired = devices.filter { it.paired }.sortedBy { !it.online }.take(ShortcutManagerCompat.getMaxShortcutCountPerActivity(ctx).coerceAtMost(4))
        val key = paired.map { it.id + it.name }
        if (key == last) return
        last = key
        val list = paired.mapIndexed { rank, d ->
            ShortcutInfoCompat.Builder(ctx, PREFIX + d.id)
                .setShortLabel(d.name)
                .setLongLived(true)
                .setRank(rank)
                .setPerson(Person.Builder().setName(d.name).setKey(d.id).build())
                .setIcon(IconCompat.createWithResource(ctx, R.mipmap.ic_launcher))
                .setCategories(setOf(CATEGORY))
                .setIntent(Intent(ctx, ShareActivity::class.java).setAction(Intent.ACTION_SEND).putExtra(Intent.EXTRA_SHORTCUT_ID, PREFIX + d.id))
                .build()
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(ctx, list) }
    }
}

/** Quick Settings tile "send the clipboard": opens an invisible activity, which may read the clipboard. */
class ClipTileService : TileService() {
    override fun onStartListening() {
        val core = (application as LocalDropApp).core
        qsTile?.apply {
            state = if (core.state.value.devices.any { it.online && it.paired }) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = core.str(R.string.tile_label)
            if (Build.VERSION.SDK_INT >= 29) subtitle = core.str(R.string.tile_sub_short)
            updateTile()
        }
    }

    override fun onClick() {
        val i = Intent(this, ClipSendActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        else @Suppress("DEPRECATION") startActivityAndCollapse(i)
    }

    companion object {
        fun requestAdd(activity: Activity, onAdded: () -> Unit) {
            if (Build.VERSION.SDK_INT < 33) return
            val core = (activity.application as LocalDropApp).core
            activity.getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(activity, ClipTileService::class.java), core.str(R.string.tile_label),
                Icon.createWithResource(activity, R.drawable.ic_stat), Executors.newSingleThreadExecutor(),
            ) { result ->
                if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) onAdded()
            }
        }
    }
}

/**
 * Has no window of its own: gets focus for a moment (Android allows reading the clipboard only
 * then), sends it to the chosen computers and closes. Used by the tile and the widget.
 */
class ClipSendActivity : Activity() {
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as LocalDropApp).core.start()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !done) {
            done = true
            (application as LocalDropApp).core.clip.sendCurrent(null)
            window.decorView.postDelayed({ finish() }, 300)
        }
    }
}
