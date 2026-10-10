package com.skz.localdrop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.skz.localdrop.core.Transfer
import com.skz.localdrop.ui.LocalBackend
import com.skz.localdrop.ui.LocalPickers
import com.skz.localdrop.ui.components.Btn
import com.skz.localdrop.ui.components.Card
import com.skz.localdrop.ui.components.Empty
import com.skz.localdrop.ui.components.Group
import com.skz.localdrop.ui.components.GroupRow
import com.skz.localdrop.ui.components.Hint
import com.skz.localdrop.ui.components.Ic
import com.skz.localdrop.ui.components.IconTile
import com.skz.localdrop.ui.components.Label
import com.skz.localdrop.ui.components.ProgressBar
import com.skz.localdrop.ui.components.RoundBtn
import com.skz.localdrop.ui.components.ScreenHead
import com.skz.localdrop.ui.components.SectionHead
import com.skz.localdrop.ui.components.Txt
import com.skz.localdrop.ui.components.dashed
import com.skz.localdrop.ui.currentTarget
import com.skz.localdrop.ui.devName
import com.skz.localdrop.ui.dirIcon
import com.skz.localdrop.ui.kindIcon
import com.skz.localdrop.ui.rememberFmt
import com.skz.localdrop.ui.totalSpeed
import com.skz.localdrop.ui.transferMeta
import com.skz.localdrop.ui.transferPct
import com.skz.localdrop.ui.transferState
import com.skz.localdrop.ui.theme.Ld

@Composable
fun FilesScreen(s: AppState) {
    val c = Ld.c
    val f = rememberFmt()
    val b = LocalBackend.current
    val pick = LocalPickers.current
    val target = currentTarget(s)
    val active = s.transfers.filter { !it.final }.sortedBy { if (it.status == "ask") 0 else 1 }
    val done = s.transfers.filter { it.final }.sortedByDescending { it.`when` }
    val speed = totalSpeed(s)
    val running = s.transfers.any { it.running }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHead(stringResource(R.string.nav_files)) {
            if (speed > 0) Txt(f.rate(speed), size = 12.sp, color = c.tx2, mono = true)
        }
        if (target == null) Hint(stringResource(R.string.no_target_hint))
        else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label(stringResource(R.string.send_to))
                TargetChips(s)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn(stringResource(R.string.pick_files), { pick.files(target.id) }, Modifier.weight(1f), icon = "upload_file", bg = c.files, height = 88.dp, radius = 14.dp, iconSize = 26.dp, vertical = true, padding = 8.dp)
                Btn(stringResource(R.string.btn_media), { pick.media(target.id) }, Modifier.weight(1f), icon = "photo_library", bg = c.filesT, color = c.files, height = 88.dp, radius = 14.dp, iconSize = 26.dp, vertical = true, padding = 8.dp)
            }
            Btn(stringResource(R.string.pick_folder), { pick.folder(target.id) }, Modifier.fillMaxWidth(), icon = "folder", bg = c.surf, color = c.tx, border = c.line2, height = 44.dp, fontSize = 13.sp, iconSize = 18.dp)
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).dashed(c.line, 12.dp).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Ic("share", size = 18.dp, color = c.files)
            Txt(stringResource(R.string.share_hint), Modifier.weight(1f), size = 12.sp, color = c.tx2)
        }

        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(stringResource(R.string.queue), Modifier.weight(1f), size = 16.sp, weight = 800)
            if (active.isNotEmpty()) Btn(
                stringResource(if (running) R.string.pause else R.string.resume), { if (running) b.pauseAll() else b.resumeAll() },
                icon = if (running) "pause" else "play_arrow", bg = c.surf, color = c.tx, border = c.line, height = 36.dp, radius = 18.dp,
                fontSize = 13.sp, iconSize = 18.dp, padding = 12.dp,
            )
        }
        if (active.isEmpty()) Empty(stringResource(R.string.queue_empty), boxed = true)
        for (t in active) ActiveCard(s, t)

        if (done.isNotEmpty()) {
            SectionHead(stringResource(R.string.done_section), stringResource(R.string.clear)) { b.clearDone() }
            Group { for (t in done) DoneRow(s, t) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Ic("folder", size = 16.dp, color = c.tx3)
            Txt(saveDirLabel(s), Modifier.weight(1f), size = 12.sp, color = c.tx3, mono = true)
        }
    }
}

@Composable
fun saveDirLabel(s: AppState): String {
    val d = s.settings.saveDir
    if (d.isEmpty()) return stringResource(R.string.save_default)
    // content://…/tree/primary%3ADocuments%2FInbox -> Documents/Inbox
    return android.net.Uri.decode(d.substringAfterLast("/tree/")).substringAfter(':').ifEmpty { d }
}

@Composable
private fun ActiveCard(s: AppState, t: Transfer) {
    val c = Ld.c
    val f = rememberFmt()
    val b = LocalBackend.current
    val pct = transferPct(t)
    Card(radius = 14.dp, padding = androidx.compose.foundation.layout.PaddingValues(14.dp), gap = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(kindIcon(t.kind), c.filesT, c.files)
            Column(Modifier.weight(1f)) {
                Txt(t.name, size = 14.sp, weight = 700, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Ic(dirIcon(t.dir), size = 14.dp, color = c.tx2)
                    Txt(devName(s, t.dev) + " · " + transferMeta(f, t), size = 12.sp, color = c.tx2, maxLines = 1)
                }
            }
            if (t.status != "ask" && t.status != "waiting") {
                RoundBtn(if (t.status == "paused") "play_arrow" else "pause", { b.transferToggle(t.id) }, size = 40.dp, iconSize = 20.dp, bg = c.surf2)
            }
            RoundBtn("close", { b.transferCancel(t.id) }, size = 40.dp, iconSize = 20.dp, color = c.tx3)
        }
        if (t.status == "ask") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn(stringResource(R.string.decline), { b.offerRespond(t.id, false) }, Modifier.weight(1f), bg = c.surf2, color = c.tx, border = c.line, height = 44.dp)
                Btn(stringResource(R.string.accept), { b.offerRespond(t.id, true) }, Modifier.weight(1f), bg = c.files, height = 44.dp)
            }
        } else {
            ProgressBar(pct, if (t.status == "active") c.files else c.tx3)
            Row {
                Txt("${(pct * 100).toInt()}% · " + stringResource(R.string.of, f.size(t.done), f.size(t.size)), Modifier.weight(1f), size = 12.sp, color = c.tx2, mono = true, maxLines = 1)
                Txt(transferState(f, t), size = 12.sp, color = c.tx2, maxLines = 1)
            }
        }
    }
}

@Composable
private fun DoneRow(s: AppState, t: Transfer) {
    val c = Ld.c
    val f = rememberFmt()
    val b = LocalBackend.current
    val err = t.status == "error"
    GroupRow {
        IconTile(if (err) "error" else kindIcon(t.kind), if (err) c.badT else c.surf2, if (err) c.bad else c.tx2, size = 38.dp, iconSize = 20.dp)
        Column(Modifier.weight(1f)) {
            Txt(t.name, size = 14.sp, weight = 600, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Ic(dirIcon(t.dir), size = 14.dp, color = c.tx3)
                val tail = when (t.status) {
                    "done" -> f.hhmm(t.`when`)
                    "error" -> f.err(t.error).ifEmpty { transferState(f, t) }
                    else -> transferState(f, t)
                }
                Txt(devName(s, t.dev) + " · " + transferMeta(f, t) + " · " + tail, size = 12.sp, color = if (err) c.bad else c.tx3, maxLines = 1)
            }
        }
        when {
            t.status == "done" && t.dir == "in" -> RoundBtn("folder_open", { b.openTransfer(t.id) }, size = 40.dp, iconSize = 20.dp)
            err && t.dir == "out" -> RoundBtn("refresh", { b.transferRetry(t.id) }, size = 40.dp, iconSize = 20.dp)
            else -> {}
        }
    }
}
