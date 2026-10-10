package com.skz.localdrop.system

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skz.localdrop.LocalDropApp
import com.skz.localdrop.MainActivity
import com.skz.localdrop.R
import com.skz.localdrop.core.Device
import com.skz.localdrop.service.LocalDropService
import com.skz.localdrop.ui.components.Empty
import com.skz.localdrop.ui.components.Ic
import com.skz.localdrop.ui.components.IconTile
import com.skz.localdrop.ui.components.Sheet
import com.skz.localdrop.ui.components.Txt
import com.skz.localdrop.ui.theme.Ld
import com.skz.localdrop.ui.theme.LdTheme
import kotlinx.coroutines.delay

/**
 * "Share → LocalDrop": a sheet of devices; a Direct Share target (a device shortcut) skips it.
 * Files go as transfers, plain text goes as clipboard.
 */
class ShareActivity : AppCompatActivity() {
    private val core get() = (application as LocalDropApp).core

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        LocalDropService.start(this)
        core.start()
        val uris = streams(intent)
        val text = if (uris.isEmpty()) intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() else null
        if (uris.isEmpty() && text.isNullOrBlank()) {
            finish()
            return
        }
        // Direct Share: the shortcut names the device
        intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID)?.removePrefix(Shortcuts.PREFIX)?.let { dev ->
            if (core.peers.get(dev)?.paired == true) {
                send(dev, uris, text)
                return
            }
        }
        setContent {
            val s by core.state.collectAsStateWithLifecycle()
            val dark = when (s.settings.theme) { "light" -> false; "system" -> isSystemInDarkTheme(); else -> true }
            var open by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { open = true }
            LdTheme(dark, s.settings.featureColors) {
                Box(Modifier.fillMaxSize()) {
                    Sheet(open, { open = false }) {
                        ShareSheet(s.devices.filter { it.paired }, uris.size, text) { dev -> send(dev, uris, text) }
                    }
                }
                LaunchedEffect(open) { if (!open) { delay(220); finish() } }
            }
        }
    }

    private fun streams(i: Intent): List<Uri> = when (i.action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }

    private fun send(dev: String, uris: List<Uri>, text: String?) {
        if (uris.isNotEmpty()) core.files.send(dev, uris, folder = false)
        else if (text != null) core.clip.sendText(text, dev)
        // show the queue: the share grant lives as long as our process anyway
        if (uris.isNotEmpty()) startActivity(Intent(this, MainActivity::class.java).putExtra("screen", "files").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}

@Composable
private fun ShareSheet(devices: List<Device>, files: Int, text: String?, onPick: (String) -> Unit) {
    val c = Ld.c
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconTile(if (text != null) "content_paste" else "upload_file", c.accT, c.acc, size = 44.dp, radius = 12.dp)
        Column(Modifier.weight(1f)) {
            Txt(stringResource(R.string.share_title), size = 15.sp, weight = 700)
            Txt(
                if (text != null) text.replace('\n', ' ').take(80)
                else androidx.compose.ui.platform.LocalContext.current.resources.getQuantityString(R.plurals.n_files, files, files),
                size = 12.sp, color = c.tx2, maxLines = 1,
            )
        }
    }
    Box(Modifier.padding(0.dp)) {
        if (devices.isEmpty()) Empty(stringResource(R.string.share_none))
        else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(14.dp), maxItemsInEachRow = 4) {
            for (d in devices) {
                val ok = d.online
                Column(
                    Modifier.width(76.dp).alpha(if (ok) 1f else 0.5f).clip(RoundedCornerShape(12.dp)).clickable { onPick(d.id) }.padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(58.dp)) {
                        Box(Modifier.size(58.dp).clip(CircleShape).background(c.surf2), contentAlignment = Alignment.Center) { Ic(d.icon, size = 26.dp, color = c.tx2) }
                        Box(
                            Modifier.align(Alignment.BottomEnd).offset(2.dp, 2.dp).size(22.dp).clip(RoundedCornerShape(7.dp)).background(c.surf).padding(2.dp)
                                .clip(RoundedCornerShape(6.dp)).background(c.acc),
                            contentAlignment = Alignment.Center,
                        ) { Ic("sync_alt", size = 13.dp, color = c.onAcc) }
                    }
                    Txt(d.name, size = 11.sp, weight = 600, maxLines = 2, align = TextAlign.Center, lineHeight = 14.sp)
                }
            }
        }
    }
}
