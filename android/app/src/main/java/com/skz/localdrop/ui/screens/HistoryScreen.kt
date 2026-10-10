package com.skz.localdrop.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skz.localdrop.R
import com.skz.localdrop.core.AppState
import com.skz.localdrop.core.HistoryItem
import com.skz.localdrop.ui.LocalBackend
import com.skz.localdrop.ui.LocalNav
import com.skz.localdrop.ui.components.BackHead
import com.skz.localdrop.ui.components.Chip
import com.skz.localdrop.ui.components.Empty
import com.skz.localdrop.ui.components.Group
import com.skz.localdrop.ui.components.GroupRow
import com.skz.localdrop.ui.components.Ic
import com.skz.localdrop.ui.components.IconTile
import com.skz.localdrop.ui.components.Label
import com.skz.localdrop.ui.components.RoundBtn
import com.skz.localdrop.ui.components.SearchField
import com.skz.localdrop.ui.components.Txt
import com.skz.localdrop.ui.devName
import com.skz.localdrop.ui.dirIcon
import com.skz.localdrop.ui.feature
import com.skz.localdrop.ui.rememberFmt
import com.skz.localdrop.ui.theme.Ld

@Composable
fun HistoryScreen(s: AppState) {
    val c = Ld.c
    val f = rememberFmt()
    val nav = LocalNav.current
    val b = LocalBackend.current
    var q by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("all") }
    val names = s.devices.associate { it.id to it.name }
    val qq = q.trim().lowercase()
    val items = s.history.filter { h ->
        (filter == "all" || h.type == filter) &&
            (qq.isEmpty() || "${h.title} ${h.sub} ${names[h.dev] ?: ""}".lowercase().contains(qq))
    }
    val groups = items.groupBy { f.dayLabel(it.ts) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        BackHead(stringResource(R.string.history), { nav.sub = null }) { RoundBtn("delete_sweep", { b.historyClear() }) }
        SearchField(q, { q = it }, stringResource(R.string.hist_search))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((id, label) in listOf("all" to R.string.f_all, "files" to R.string.f_files, "clip" to R.string.f_clip, "audio" to R.string.f_audio)) {
                Chip(stringResource(label), filter == id, c.acc, c.accT, { filter = id }, height = 34.dp)
            }
        }
        if (items.isEmpty()) Empty(stringResource(R.string.hist_empty))
        for ((day, list) in groups) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label(day)
                Group { for (h in list) HistRow(s, h) }
            }
        }
    }
}

@Composable
private fun HistRow(s: AppState, h: HistoryItem) {
    val c = Ld.c
    val f = rememberFmt()
    val b = LocalBackend.current
    val ft = feature(h.type)
    GroupRow(onClick = { b.historyAction(h.id) }) {
        IconTile(if (h.err) "error" else ft.icon, if (h.err) c.badT else ft.tint, if (h.err) c.bad else ft.color, size = 38.dp, iconSize = 20.dp)
        Column(Modifier.weight(1f)) {
            Txt(h.title, size = 14.sp, weight = 600, maxLines = 1)
            if (h.sub.isNotEmpty()) Txt(h.sub, size = 12.sp, color = if (h.err) c.bad else c.tx2, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Ic(dirIcon(h.dir), size = 13.dp, color = c.tx3)
                Txt(devName(s, h.dev), size = 11.sp, color = c.tx3, maxLines = 1)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Txt(f.hhmm(h.ts), size = 11.sp, color = c.tx3, mono = true)
            Ic(if (h.err) "refresh" else "chevron_right", size = 18.dp, color = c.tx3)
        }
    }
}
