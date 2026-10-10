package com.skz.localdrop.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skz.localdrop.R
import com.skz.localdrop.core.AppState
import com.skz.localdrop.core.PairDialog
import com.skz.localdrop.ui.LocalBackend
import com.skz.localdrop.ui.LocalNav
import com.skz.localdrop.ui.components.Btn
import com.skz.localdrop.ui.components.Group
import com.skz.localdrop.ui.components.GroupRow
import com.skz.localdrop.ui.components.IconTile
import com.skz.localdrop.ui.components.ScanDot
import com.skz.localdrop.ui.components.Sheet
import com.skz.localdrop.ui.components.Texts
import com.skz.localdrop.ui.components.Toggle
import com.skz.localdrop.ui.components.ToggleRow
import com.skz.localdrop.ui.components.Txt
import com.skz.localdrop.ui.feature
import com.skz.localdrop.ui.theme.Ld

/** Pairing request: the same 6-digit code must show on both screens. */
@Composable
fun PairSheet(p: PairDialog?) {
    val c = Ld.c
    val b = LocalBackend.current
    var last by remember { mutableStateOf(p) }
    if (p != null) last = p
    val d = last
    val close = { d?.let { b.pairRespond(it.id, false, false) }; Unit }
    Sheet(p != null, close, dim = 0.55f) {
        if (d == null) return@Sheet
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(d.icon, c.accT, c.acc, size = 64.dp, radius = 18.dp, iconSize = 32.dp)
            Txt(stringResource(if (d.dir == "in") R.string.pair_in_title else R.string.pair_out_title, d.name), size = 19.sp, weight = 800, align = TextAlign.Center)
            Txt(listOf(d.os, d.ip).filter { it.isNotEmpty() }.joinToString(" · "), size = 13.sp, color = c.tx2)
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surf2).padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Txt(stringResource(R.string.pair_code_hint), size = 12.sp, weight = 600, color = c.tx2)
            Txt(d.code, Modifier.padding(top = 6.dp), size = 30.sp, weight = 500, mono = true, letterSpacing = 2.4.sp)
        }
        ToggleRow(stringResource(R.string.pair_trust), stringResource(R.string.pair_trust_sub), d.trust) {
            b.pairOutTrust(d.id, !d.trust)
        }
        if (d.dir == "in") Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn(stringResource(R.string.decline), { b.pairRespond(d.id, false, false) }, Modifier.weight(1f), bg = c.surf2, color = c.tx, border = c.line, radius = 14.dp, fontSize = 15.sp)
            Btn(stringResource(R.string.accept), { b.pairRespond(d.id, true, d.trust) }, Modifier.weight(1f), radius = 14.dp, fontSize = 15.sp)
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                ScanDot()
                Txt(stringResource(R.string.pair_out_wait), size = 13.sp, color = c.tx2)
            }
            Btn(stringResource(R.string.cancel), { b.pairRespond(d.id, false, false) }, Modifier.fillMaxWidth(), bg = c.surf2, color = c.tx, border = c.line, radius = 14.dp, fontSize = 15.sp)
        }
    }
}

/** First start: welcome, permissions, features, devices nearby. */
@Composable
fun Onboarding(s: AppState) {
    val c = Ld.c
    val nav = LocalNav.current
    val b = LocalBackend.current
    val step = nav.onboarding
    val finish = {
        b.settings { it.copy(onboarded = true) }
        nav.onboarding = -1
    }
    BackHandler { if (step > 0) nav.onboarding = step - 1 else finish() }
    LaunchedEffect(step) { if (step == 3) b.rescan() }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp),
            verticalArrangement = if (step == 0) Arrangement.Center else Arrangement.spacedBy(20.dp),
        ) {
            when (step) {
                0 -> Welcome()
                1 -> {
                    Head(stringResource(R.string.onb_perm_title), stringResource(R.string.onb_perm_text))
                    PermList(s, tinted = true)
                }
                2 -> {
                    Head(stringResource(R.string.onb_feat_title), stringResource(R.string.onb_feat_text))
                    val st = s.settings
                    FeatureCard("files", stringResource(R.string.btn_files), stringResource(R.string.feat_files_sub), "folder_open", st.featFiles) { b.settings { it.copy(featFiles = !it.featFiles) } }
                    FeatureCard("clip", stringResource(R.string.clipboard), stringResource(R.string.feat_clip_sub), "content_paste", st.featClip) { b.settings { it.copy(featClip = !it.featClip) } }
                    FeatureCard("audio", stringResource(R.string.audio), stringResource(R.string.feat_audio_sub), "graphic_eq", st.featAudio) { b.settings { it.copy(featAudio = !it.featAudio) } }
                }
                else -> {
                    Column {
                        Txt(stringResource(R.string.onb_dev_title), size = 26.sp, weight = 800, letterSpacing = (-0.52).sp)
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ScanDot()
                            Txt(if (s.me.network.isNotEmpty()) stringResource(R.string.onb_dev_scan, s.me.network) else stringResource(R.string.onb_dev_scan_any), size = 14.sp, color = c.tx2)
                        }
                    }
                    val devs = s.devices.filter { it.online }
                    if (devs.isEmpty()) Txt(stringResource(R.string.onb_dev_none), size = 14.sp, color = c.tx3)
                    else Group {
                        for (d in devs) GroupRow {
                            IconTile(d.icon, c.surf2, c.tx2)
                            Texts(d.name, d.os)
                            if (d.paired) com.skz.localdrop.ui.components.Ic("check_circle", color = c.ok)
                            else Btn(stringResource(R.string.pair_btn), { b.pairRequest(d.id) }, height = 36.dp, radius = 18.dp, bg = c.accT, color = c.acc, fontSize = 13.sp, padding = 14.dp)
                        }
                    }
                    Btn(stringResource(R.string.scan_qr), { nav.qr = true }, Modifier.fillMaxWidth(), icon = "qr_code_scanner", bg = c.surf, color = c.tx, border = c.line, height = 48.dp, radius = 14.dp, iconSize = 20.dp)
                }
            }
        }
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                for (i in 0..3) {
                    val w by animateDpAsState(if (i == step) 22.dp else 6.dp, label = "dot")
                    Box(Modifier.width(w).height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (i == step) c.acc else c.line))
                }
            }
            Btn(
                stringResource(when (step) { 0 -> R.string.onb_start; 3 -> R.string.done; else -> R.string.next }),
                { if (step < 3) nav.onboarding = step + 1 else finish() }, Modifier.fillMaxWidth(), height = 54.dp, radius = 16.dp, fontSize = 15.sp,
            )
            Btn(stringResource(if (step == 0) R.string.skip else R.string.back), { if (step == 0) finish() else nav.onboarding = step - 1 },
                Modifier.fillMaxWidth(), bg = Color.Transparent, color = c.tx2, height = 40.dp)
        }
    }
}

@Composable
private fun Head(title: String, text: String) {
    Column {
        Txt(title, size = 26.sp, weight = 800, letterSpacing = (-0.52).sp)
        Txt(text, Modifier.padding(top = 8.dp), size = 14.sp, color = Ld.c.tx2, lineHeight = 21.sp)
    }
}

@Composable
private fun Welcome() {
    val c = Ld.c
    Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
        IconTile("sync_alt", c.acc, c.onAcc, size = 76.dp, radius = 22.dp, iconSize = 42.dp)
        Column {
            Txt(stringResource(R.string.onb_title), size = 32.sp, weight = 800, letterSpacing = (-0.64).sp, lineHeight = 35.sp)
            Txt(stringResource(R.string.onb_text), Modifier.padding(top = 12.dp), size = 15.sp, color = c.tx2, lineHeight = 22.sp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for ((type, icon, text) in listOf(
                Triple("files", "folder_open", R.string.onb_files), Triple("clip", "content_paste", R.string.onb_clip), Triple("audio", "graphic_eq", R.string.onb_audio),
            )) {
                val f = feature(type)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconTile(icon, f.tint, f.color, size = 36.dp, iconSize = 20.dp)
                    Txt(stringResource(text), size = 14.sp, weight = 600)
                }
            }
        }
    }
}

@Composable
private fun FeatureCard(type: String, title: String, desc: String, icon: String, on: Boolean, onClick: () -> Unit) {
    val c = Ld.c
    val f = feature(type)
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(c.surf).border(1.5.dp, if (on) f.color else c.line2, shape).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconTile(icon, f.tint, f.color, size = 44.dp, radius = 12.dp)
        Texts(title, desc, titleSize = 15.sp)
        Toggle(on, f.color, onClick)
    }
}
