package com.skz.localdrop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skz.localdrop.R
import com.skz.localdrop.core.AppState
import com.skz.localdrop.core.HistoryItem
import com.skz.localdrop.ui.LocalBackend
import com.skz.localdrop.ui.LocalNav
import com.skz.localdrop.ui.LocalPickers
import com.skz.localdrop.ui.components.Btn
import com.skz.localdrop.ui.components.bleed
import com.skz.localdrop.ui.components.Chip
import com.skz.localdrop.ui.components.Dot
import com.skz.localdrop.ui.components.Empty
import com.skz.localdrop.ui.components.Group
import com.skz.localdrop.ui.components.GroupRow
import com.skz.localdrop.ui.components.Ic
import com.skz.localdrop.ui.components.IconTile
import com.skz.localdrop.ui.components.ProgressBar
import com.skz.localdrop.ui.components.RoundBtn
import com.skz.localdrop.ui.components.SectionHead
import com.skz.localdrop.ui.components.Texts
import com.skz.localdrop.ui.components.Toggle
import com.skz.localdrop.ui.components.Txt
import com.skz.localdrop.ui.components.Card
import com.skz.localdrop.ui.currentTarget
import com.skz.localdrop.ui.devName
import com.skz.localdrop.ui.dirIcon
import com.skz.localdrop.ui.feature
import com.skz.localdrop.ui.rememberFmt
import com.skz.localdrop.ui.sendTargets
import com.skz.localdrop.ui.totalSpeed
import com.skz.localdrop.ui.transferPct
import com.skz.localdrop.ui.theme.Ld

@Composable
fun HomeScreen(s: AppState) {
    val c = Ld.c
    val f = rememberFmt()
    val nav = LocalNav.current
    val b = LocalBackend.current
    val pick = LocalPickers.current
    val online = s.devices.filter { it.online }
    val target = currentTarget(s)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // header
        Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile("sync_alt", c.acc, c.onAcc, size = 38.dp, radius = 11.dp)
            Column(Modifier.weight(1f)) {
                Txt("LocalDrop", size = 17.sp, weight = 800)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Dot(if (s.me.ip.isNotEmpty()) c.ok else c.tx3)
                    Txt(s.me.name + " · " + s.me.ip.ifEmpty { "—" }, size = 12.sp, color = c.tx2, maxLines = 1)
                }
            }
            RoundBtn("history", { nav.push("history") })
            RoundBtn("settings", { nav.push("settings") })
        }
        Column {
            Txt(f.greeting(), size = 26.sp, weight = 800, letterSpacing = (-0.52).sp)
            Txt(stringResource(R.string.home_sub, f.pl(R.plurals.n_online, online.size)), Modifier.padding(top = 4.dp), size = 14.sp, color = c.tx2)
        }

        // send card
        if (s.settings.featFiles) Card {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconTile("upload", c.filesT, c.files)
                Texts(stringResource(R.string.send_to_pc), target?.let { stringResource(R.string.chosen, it.name) } ?: stringResource(R.string.no_target), titleSize = 15.sp)
            }
            TargetChips(s)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn(stringResource(R.string.btn_files), { target?.let { pick.files(it.id) } }, Modifier.weight(1f), icon = "upload_file", bg = c.files, enabled = target != null)
                Btn(stringResource(R.string.btn_media), { target?.let { pick.media(it.id) } }, Modifier.weight(1f), icon = "photo_library", bg = c.filesT, color = c.files, enabled = target != null, padding = 8.dp)
            }
        }

        // three features
        Group {
            if (s.settings.featFiles) {
                val active = s.transfers.filter { !it.final }
                val cur = s.transfers.firstOrNull { it.status == "active" } ?: active.firstOrNull()
                Column(Modifier.fillMaxWidth().background(c.surf).clickable { nav.go("files") }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconTile("swap_vert", c.filesT, c.files)
                        val speed = totalSpeed(s)
                        Texts(
                            stringResource(R.string.transfers),
                            when {
                                active.isEmpty() -> stringResource(R.string.no_active)
                                speed > 0 -> stringResource(R.string.files_summary, active.size, f.rate(speed))
                                else -> stringResource(R.string.files_summary_idle, active.size)
                            },
                        )
                        Ic("chevron_right", color = c.tx3)
                    }
                    if (cur != null) Column(Modifier.padding(start = 52.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val pct = transferPct(cur)
                        Row {
                            Txt(cur.name, Modifier.weight(1f), size = 12.sp, weight = 600, maxLines = 1)
                            Txt("${(pct * 100).toInt()}%", size = 12.sp, color = c.tx2, mono = true)
                        }
                        ProgressBar(pct, if (cur.status == "active") c.files else c.tx3, height = 4.dp)
                    }
                }
            }
            if (s.settings.featClip) GroupRow(padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
                IconTile("content_paste", c.clipT, c.clip)
                Column(Modifier.weight(1f).clickable { nav.go("clip") }) {
                    Txt(stringResource(R.string.clipboard), size = 14.sp, weight = 700)
                    Txt(s.clips.firstOrNull()?.let { if (it.type == "image") it.meta.ifEmpty { it.text } else it.text.replace('\n', ' ') } ?: stringResource(R.string.clip_empty), size = 12.sp, color = c.tx2, maxLines = 1)
                }
                Btn(stringResource(R.string.send), { b.clipSendCurrent() }, height = 36.dp, radius = 18.dp, bg = c.clipT, color = c.clip, fontSize = 13.sp, padding = 14.dp)
            }
            if (s.settings.featAudio) GroupRow(padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
                IconTile("graphic_eq", c.audioT, c.audio)
                val on = s.audio.on || s.audio.incoming != null
                Column(Modifier.weight(1f).clickable { nav.go("audio") }) {
                    Txt(stringResource(R.string.audio), size = 14.sp, weight = 700)
                    Txt(audioStateText(s), size = 12.sp, color = c.tx2, maxLines = 1)
                }
                Toggle(on, c.audio) {
                    when {
                        s.audio.on -> b.audioToggle()
                        s.audio.incoming != null || s.audio.listening.isNotEmpty() -> b.audioListenStop()
                        nav.audioMode == "tx" -> b.audioToggle()
                        else -> {
                            val dev = s.audio.listening.ifEmpty { currentTarget(s)?.id ?: "" }
                            if (dev.isNotEmpty()) b.audioListen(dev) else nav.go("audio")
                        }
                    }
                }
            }
        }

        // nearby
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHead(stringResource(R.string.nearby), stringResource(R.string.all_devices)) { nav.go("devices") }
            if (online.isEmpty()) Empty(stringResource(R.string.no_nearby), boxed = true)
            else Row(
                Modifier.fillMaxWidth().bleed(16.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (d in online) {
                    val shape = RoundedCornerShape(14.dp)
                    Column(
                        Modifier.width(112.dp).clip(shape).background(c.surf).border(1.dp, c.line2, shape)
                            .clickable { nav.sheet = d.id }.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        IconTile(d.icon, c.surf2, c.tx2, dot = c.ok)
                        Txt(d.name, Modifier.heightIn(min = 32.dp), size = 13.sp, weight = 700, maxLines = 2, lineHeight = 16.sp)
                    }
                }
            }
        }

        // recent
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHead(stringResource(R.string.recent), stringResource(R.string.history)) { nav.push("history") }
            if (s.history.isEmpty()) Empty(stringResource(R.string.no_recent), boxed = true)
            else Group { for (h in s.history.take(4)) RecentRow(s, h) }
        }
    }
}

@Composable
private fun RecentRow(s: AppState, h: HistoryItem) {
    val c = Ld.c
    val f = rememberFmt()
    val ft = feature(h.type)
    GroupRow {
        IconTile(if (h.err) "error" else ft.icon, if (h.err) c.badT else ft.tint, if (h.err) c.bad else ft.color, size = 38.dp, iconSize = 20.dp)
        Column(Modifier.weight(1f)) {
            Txt(h.title, size = 14.sp, weight = 600, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Ic(dirIcon(h.dir), size = 14.dp, color = c.tx3)
                Txt(devName(s, h.dev) + " · " + f.hhmm(h.ts), size = 12.sp, color = c.tx3, maxLines = 1)
            }
        }
    }
}

/** Horizontal device chips for choosing where files go (Home and Files). */
@Composable
fun TargetChips(s: AppState, modifier: Modifier = Modifier) {
    val c = Ld.c
    val b = LocalBackend.current
    val targets = sendTargets(s)
    val cur = currentTarget(s)
    if (targets.isEmpty()) return
    Row(modifier.fillMaxWidth().bleed(16.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (d in targets) Chip(d.name, d.id == cur?.id, c.files, c.filesT, { b.settings { it.copy(sendTarget = d.id) } }, icon = d.icon)
    }
}

@Composable
fun audioStateText(s: AppState): String {
    val f = rememberFmt()
    return when {
        s.audio.incoming != null -> stringResource(R.string.audio_listening, devName(s, s.audio.incoming.dev))
        s.audio.on -> stringResource(R.string.audio_streaming, f.pl(R.plurals.n_devices, s.settings.audioTargets.count { it.on }))
        else -> stringResource(R.string.audio_off)
    }
}
