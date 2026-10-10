package com.skz.localdrop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skz.localdrop.R
import com.skz.localdrop.core.AppState
import com.skz.localdrop.core.AudioTarget
import com.skz.localdrop.ui.LocalBackend
import com.skz.localdrop.ui.LocalNav
import com.skz.localdrop.ui.components.Btn
import com.skz.localdrop.ui.components.Card
import com.skz.localdrop.ui.components.Chip
import com.skz.localdrop.ui.components.Group
import com.skz.localdrop.ui.components.Hint
import com.skz.localdrop.ui.components.Ic
import com.skz.localdrop.ui.components.IconTile
import com.skz.localdrop.ui.components.Label
import com.skz.localdrop.ui.components.RadioCard
import com.skz.localdrop.ui.components.ScreenHead
import com.skz.localdrop.ui.components.Seg
import com.skz.localdrop.ui.components.SegItem
import com.skz.localdrop.ui.components.Slider
import com.skz.localdrop.ui.components.Texts
import com.skz.localdrop.ui.components.Toggle
import com.skz.localdrop.ui.components.Txt
import com.skz.localdrop.ui.components.dashed
import com.skz.localdrop.ui.devName
import com.skz.localdrop.ui.rememberFmt
import com.skz.localdrop.ui.sendTargets
import com.skz.localdrop.ui.theme.Ld

private val LATENCY = mapOf("low" to 25, "balance" to 60, "hq" to 120)
private val BITRATE = mapOf("low" to "Opus 96", "balance" to "Opus 192", "hq" to "PCM 1536")

@Composable
fun AudioScreen(s: AppState) {
    val c = Ld.c
    val nav = LocalNav.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHead(stringResource(R.string.audio))
        Seg(
            listOf(SegItem("rx", stringResource(R.string.listen_pc), "headphones"), SegItem("tx", stringResource(R.string.stream), "cast")),
            nav.audioMode, { nav.audioMode = it }, height = 40.dp,
        )
        if (nav.audioMode == "rx") Receive(s) else Transmit(s)
        if (nav.audioMode == "tx") {
            Label(stringResource(R.string.quality), Modifier.padding(top = 4.dp))
            val b = LocalBackend.current
            for ((id, t, d) in listOf(
                Triple("low", R.string.q_low, R.string.q_low_sub), Triple("balance", R.string.q_balance, R.string.q_balance_sub),
                Triple("hq", R.string.q_hq, R.string.q_hq_sub),
            )) RadioCard(stringResource(t), stringResource(d), s.settings.audioQuality == id, { b.settings { it.copy(audioQuality = id) } }, color = c.audio, tint = c.audioT)
        }
    }
}

@Composable
private fun Hero(on: Boolean, title: String, sub: String, btn: String, onClick: () -> Unit) {
    val c = Ld.c
    Card(bg = if (on) c.audioT else c.surf, border = if (on) Color.Transparent else c.line2) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(if (on) "graphic_eq" else "volume_off", c.audioT, c.audio, size = 44.dp, radius = 12.dp)
            Texts(title, sub, titleSize = 15.sp, wrap = true)
        }
        Btn(
            btn, onClick, Modifier.fillMaxWidth(), icon = if (on) "stop_circle" else "play_circle",
            bg = if (on) c.surf else c.audio, color = if (on) c.tx else c.onAcc, border = if (on) c.line else null,
        )
    }
}

@Composable
private fun Receive(s: AppState) {
    val c = Ld.c
    val b = LocalBackend.current
    val sources = sendTargets(s)
    val inc = s.audio.incoming
    var pick by rememberSaveable { mutableStateOf("") }
    val chosen = s.audio.listening.ifEmpty { inc?.dev ?: "" }.ifEmpty { pick }.ifEmpty { sources.firstOrNull()?.id ?: "" }
    val on = inc != null || s.audio.listening.isNotEmpty()
    val name = devName(s, inc?.dev ?: chosen)
    val lat = s.audio.latency.takeIf { it > 0 } ?: LATENCY[s.settings.audioQuality] ?: 60

    Hero(
        on,
        if (on) stringResource(R.string.rx_on_title, name) else stringResource(R.string.rx_off_title),
        when {
            inc != null && inc.note.isNotEmpty() -> inc.note
            inc?.connected == true -> stringResource(R.string.latency_sub, lat, inc.bitrate.ifEmpty { "—" })
            on -> stringResource(R.string.rx_connecting)
            else -> stringResource(R.string.rx_off_sub)
        },
        stringResource(if (on) R.string.stop else R.string.listen),
    ) { if (on) b.audioListenStop() else if (chosen.isNotEmpty()) b.audioListen(chosen) }

    Label(stringResource(R.string.sound_source), Modifier.padding(top = 4.dp))
    if (sources.isEmpty()) Hint(stringResource(R.string.no_audio_sources))
    else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (d in sources) Chip(d.name, d.id == chosen, c.audio, c.audioT, {
            pick = d.id
            if (on && d.id != chosen) b.audioListen(d.id)
        }, icon = d.icon)
    }

    Label(stringResource(R.string.vol_out), Modifier.padding(top = 4.dp))
    Card(padding = PaddingValues(14.dp), gap = 12.dp) {
        var vol by remember { mutableIntStateOf(inc?.vol ?: 65) }
        LaunchedEffect(inc?.vol) { inc?.vol?.let { vol = it } }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Ic("volume_down", color = c.tx2)
            Slider(vol, { vol = it; b.audioVolume(it) }, c.audio, Modifier.weight(1f))   // local: change the volume while dragging
            Txt("$vol%", Modifier.width(40.dp), size = 12.sp, color = c.tx2, mono = true, align = TextAlign.End)
        }
        val current = inc?.out ?: ""
        for (o in s.audio.outputs) {
            val (sub, icon) = when (o.type) {
                "bt" -> stringResource(R.string.out_bt) to "headphones"
                "wired" -> stringResource(R.string.out_wired) to "headphones"
                "usb" -> stringResource(R.string.out_usb) to "usb"
                else -> stringResource(R.string.out_builtin) to "volume_up"
            }
            val title = if (o.type == "speaker") stringResource(R.string.out_speaker) else o.name
            RadioCard(title, sub, current == o.id || (current.isEmpty() && o.type == "speaker"), { b.audioOutput(o.id) }, icon = icon, color = c.audio, tint = c.audioT)
        }
    }
}

@Composable
private fun Transmit(s: AppState) {
    val c = Ld.c
    val f = rememberFmt()
    val b = LocalBackend.current
    val on = s.audio.on
    val targets = s.settings.audioTargets.filter { s.device(it.dev)?.paired == true }
    val nOn = targets.count { it.on }
    val q = s.settings.audioQuality

    Hero(
        on,
        if (on) stringResource(R.string.tx_on_title, f.pl(R.plurals.n_devices, nOn)) else stringResource(R.string.tx_off_title),
        if (on) stringResource(R.string.latency_sub, s.audio.latency.takeIf { it > 0 } ?: LATENCY[q] ?: 60, (BITRATE[q] ?: "") + " " + stringResource(R.string.kbps))
        else stringResource(R.string.tx_off_sub),
        stringResource(if (on) R.string.stop else R.string.start_stream),
    ) { b.audioToggle() }
    if (s.audio.error.isNotEmpty()) Hint(s.audio.error, "error")

    Label(stringResource(R.string.where), Modifier.padding(top = 4.dp))
    if (targets.isNotEmpty()) Group { for (t in targets) TargetRow(s, t) }
    val addable = sendTargets(s).filter { d -> targets.none { it.dev == d.id } }
    if (addable.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).dashed(c.line, 12.dp).clickable { b.audioTarget(addable.first().id, on = true) },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            Ic("add", size = 18.dp, color = c.tx2)
            Txt(stringResource(R.string.add_device), size = 13.sp, weight = 700, color = c.tx2)
        }
    }
}

@Composable
private fun TargetRow(s: AppState, t: AudioTarget) {
    val c = Ld.c
    val b = LocalBackend.current
    val d = s.device(t.dev) ?: return
    val remote = s.audio.remote[t.dev]
    Column(Modifier.fillMaxWidth().background(c.surf).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.alpha(if (t.on) 1f else 0.45f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(d.icon, c.surf2, c.tx2)
            val status = when {
                !d.online -> d.os + " · " + stringResource(R.string.not_online_suffix)
                remote?.error?.isNotEmpty() == true -> remote.error
                else -> d.os
            }
            Texts(d.name, status, subColor = if (remote?.error?.isNotEmpty() == true) c.bad else c.tx2)
            Toggle(t.on, c.audio) { b.audioTarget(t.dev, on = !t.on) }
        }
        var vol by remember { mutableIntStateOf(t.vol) }
        LaunchedEffect(t.vol) { vol = t.vol }
        Row(Modifier.alpha(if (t.on) 1f else 0.45f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Ic("volume_up", size = 18.dp, color = c.tx3)
            Slider(vol, { vol = it }, c.audio, Modifier.weight(1f), onDone = { b.audioTarget(t.dev, vol = vol) })
            Txt("$vol%", Modifier.width(40.dp), size = 12.sp, color = c.tx2, mono = true, align = TextAlign.End)
        }
        val outs = remote?.outputs.orEmpty()
        if (t.on && outs.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(stringResource(R.string.out_default), t.out.isEmpty(), c.audio, c.audioT, { b.audioTarget(t.dev, out = "") }, height = 30.dp, fontSize = 12.sp)
            for (o in outs) Chip(o, t.out == o, c.audio, c.audioT, { b.audioTarget(t.dev, out = o) }, height = 30.dp, fontSize = 12.sp)
        }
    }
}
