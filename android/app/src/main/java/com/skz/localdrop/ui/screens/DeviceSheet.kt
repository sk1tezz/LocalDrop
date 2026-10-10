package com.skz.localdrop.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skz.localdrop.R
import com.skz.localdrop.core.AppState
import com.skz.localdrop.core.Device
import com.skz.localdrop.ui.LocalBackend
import com.skz.localdrop.ui.LocalNav
import com.skz.localdrop.ui.LocalPickers
import com.skz.localdrop.ui.components.Badge
import com.skz.localdrop.ui.components.Btn
import com.skz.localdrop.ui.components.Dot
import com.skz.localdrop.ui.components.IconTile
import com.skz.localdrop.ui.components.Sheet
import com.skz.localdrop.ui.components.Texts
import com.skz.localdrop.ui.components.Toggle
import com.skz.localdrop.ui.components.Txt
import com.skz.localdrop.ui.theme.Ld

/** Bottom sheet for one device: send files or clipboard, audio, pairing and trust. */
@Composable
fun DeviceSheet(dev: Device?, s: AppState, onClose: () -> Unit) {
    // keep the last device while the sheet animates out
    var last by remember { mutableStateOf(dev) }
    if (dev != null) last = dev
    val d = last
    Sheet(dev != null, onClose) {
        if (d != null) SheetBody(d, s, onClose)
    }
}

@Composable
private fun SheetBody(d: Device, s: AppState, onClose: () -> Unit) {
    val c = Ld.c
    val b = LocalBackend.current
    val nav = LocalNav.current
    val pick = LocalPickers.current

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        IconTile(d.icon, c.surf2, c.tx2, size = 56.dp, radius = 14.dp, iconSize = 28.dp)
        Column(Modifier.weight(1f)) {
            Txt(d.name, size = 18.sp, weight = 800, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Dot(if (d.online) c.ok else c.tx3)
                Txt(stringResource(if (d.online) R.string.online else R.string.offline) + (if (d.ip.isNotEmpty()) " · " + d.ip else ""), size = 12.sp, color = c.tx2, maxLines = 1)
            }
        }
        when {
            d.trusted -> Badge(stringResource(R.string.lbl_trusted), c.tx2, c.surf2)
            d.paired -> Badge(stringResource(R.string.lbl_paired), c.tx2, c.surf2)
            else -> Badge(stringResource(R.string.badge_new), c.acc, c.accT)
        }
    }

    when {
        !d.paired -> {
            Txt(stringResource(R.string.new_note), size = 13.sp, color = c.tx2)
            Btn(stringResource(R.string.pair_btn), { onClose(); b.pairRequest(d.id) }, Modifier.fillMaxWidth(), radius = 14.dp, fontSize = 15.sp, enabled = d.online)
        }
        !d.online -> Txt(stringResource(R.string.offline_note), size = 13.sp, color = c.tx2)
        else -> {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (s.settings.featFiles) Btn(
                    stringResource(R.string.send_files), { onClose(); pick.files(d.id) }, Modifier.weight(1f), icon = "upload_file",
                    bg = c.filesT, color = c.files, height = 80.dp, radius = 16.dp, fontSize = 13.sp, iconSize = 26.dp, vertical = true, padding = 8.dp,
                )
                if (s.settings.featClip) Btn(
                    stringResource(R.string.send_clip), { onClose(); b.clipSendCurrent(d.id) }, Modifier.weight(1f), icon = "content_paste_go",
                    bg = c.clipT, color = c.clip, height = 80.dp, radius = 16.dp, fontSize = 13.sp, iconSize = 26.dp, vertical = true, padding = 8.dp,
                )
            }
            if (s.settings.featAudio && d.trusted) {
                val shape = RoundedCornerShape(16.dp)
                val target = s.settings.audioTargets.firstOrNull { it.dev == d.id }
                Row(
                    Modifier.fillMaxWidth().border(1.dp, c.line2, shape).padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    IconTile("cast", c.audioT, c.audio, size = 36.dp, iconSize = 20.dp)
                    Texts(stringResource(R.string.stream_audio), stringResource(R.string.stream_audio_sub))
                    val on = s.audio.on && target?.on == true
                    Toggle(on, c.audio) {
                        if (on) b.audioTarget(d.id, on = false)
                        else {
                            b.audioTarget(d.id, on = true)
                            nav.audioMode = "tx"
                            if (!s.audio.on) b.audioToggle()
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().border(1.dp, c.line2, shape).padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    IconTile("headphones", c.audioT, c.audio, size = 36.dp, iconSize = 20.dp)
                    Texts(stringResource(R.string.listen_audio), stringResource(R.string.listen_audio_sub))
                    val on = s.audio.listening == d.id
                    Toggle(on, c.audio) {
                        nav.audioMode = "rx"
                        if (on) b.audioListenStop() else b.audioListen(d.id)
                    }
                }
            }
        }
    }
    if (d.paired && !d.trusted) Txt(stringResource(R.string.untrusted_note), size = 12.sp, color = c.tx3)
    if (d.paired) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (d.trusted) Btn(stringResource(R.string.untrust), { onClose(); b.setTrust(d.id, false) }, Modifier.weight(1f), height = 44.dp, bg = androidx.compose.ui.graphics.Color.Transparent, color = c.bad, padding = 4.dp)
        else {
            Btn(stringResource(R.string.make_trusted), { b.setTrust(d.id, true) }, Modifier.weight(1f), height = 44.dp, bg = c.accT, color = c.acc, padding = 4.dp)
            Btn(stringResource(R.string.forget), { onClose(); b.forget(d.id) }, Modifier.weight(1f), height = 44.dp, bg = androidx.compose.ui.graphics.Color.Transparent, color = c.bad, padding = 4.dp)
        }
    }
}
