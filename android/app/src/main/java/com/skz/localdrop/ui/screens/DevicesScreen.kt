package com.skz.localdrop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skz.localdrop.R
import com.skz.localdrop.core.AppState
import com.skz.localdrop.core.Device
import com.skz.localdrop.ui.LocalBackend
import com.skz.localdrop.ui.LocalNav
import com.skz.localdrop.ui.components.Badge
import com.skz.localdrop.ui.components.Btn
import com.skz.localdrop.ui.components.Card
import com.skz.localdrop.ui.components.Empty
import com.skz.localdrop.ui.components.Group
import com.skz.localdrop.ui.components.GroupRow
import com.skz.localdrop.ui.components.Ic
import com.skz.localdrop.ui.components.IconTile
import com.skz.localdrop.ui.components.Input
import com.skz.localdrop.ui.components.Label
import com.skz.localdrop.ui.components.RoundBtn
import com.skz.localdrop.ui.components.ScanDot
import com.skz.localdrop.ui.components.ScreenHead
import com.skz.localdrop.ui.components.SearchField
import com.skz.localdrop.ui.components.Seg
import com.skz.localdrop.ui.components.SegItem
import com.skz.localdrop.ui.components.Texts
import com.skz.localdrop.ui.components.Txt
import com.skz.localdrop.ui.theme.Ld

@Composable
fun DevicesScreen(s: AppState) {
    val c = Ld.c
    val nav = LocalNav.current
    val b = LocalBackend.current
    var q by rememberSaveable { mutableStateOf("") }
    val trusted = s.devices.filter { it.paired }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHead(stringResource(R.string.nav_devices)) {
            val shape = RoundedCornerShape(20.dp)
            Row(
                Modifier.clip(shape).background(c.surf).border(1.dp, c.line, shape).clickable { b.rescan() }
                    .padding(start = 12.dp, end = 14.dp).heightIn(min = 40.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ScanDot()
                Txt(stringResource(if (s.scanning) R.string.searching else R.string.refresh), size = 13.sp, weight = 700)
            }
        }
        if (nav.devTab != "add") SearchField(q, { q = it }, stringResource(R.string.search_ph))
        Seg(
            listOf(
                SegItem("nearby", stringResource(R.string.nearby), count = s.devices.size.toString()),
                SegItem("trusted", stringResource(R.string.tab_trusted), count = trusted.size.toString()),
                SegItem("add", stringResource(R.string.tab_add)),
            ),
            nav.devTab, { nav.devTab = it },
        )
        if (nav.devTab == "add") AddDevice(s)
        else {
            val qq = q.trim().lowercase()
            val list = (if (nav.devTab == "trusted") trusted else s.devices)
                .filter { qq.isEmpty() || "${it.name} ${it.ip} ${it.os}".lowercase().contains(qq) }
            if (list.isEmpty()) Empty(stringResource(R.string.nothing_found))
            else Group { for (d in list) DeviceRow(d) { nav.sheet = d.id } }
        }
    }
}

@Composable
private fun DeviceRow(d: Device, onClick: () -> Unit) {
    val c = Ld.c
    val a = if (d.online) 1f else 0.5f
    GroupRow(Modifier.heightIn(min = 68.dp), onClick = onClick) {
        Box(Modifier.alpha(a)) { IconTile(d.icon, c.surf2, c.tx2, size = 42.dp, radius = 11.dp, dot = if (d.online) c.ok else c.tx3) }
        Column(Modifier.weight(1f).alpha(a)) {
            Txt(d.name, size = 15.sp, weight = 700, maxLines = 1)
            Txt(listOf(d.os, d.ip).filter { it.isNotEmpty() }.joinToString(" · "), size = 12.sp, color = c.tx2, maxLines = 1)
        }
        if (!d.paired) Badge(stringResource(R.string.badge_new), c.acc, c.accT)
        Ic("chevron_right", color = c.tx3)
    }
}

@Composable
private fun AddDevice(s: AppState) {
    val c = Ld.c
    val nav = LocalNav.current
    val b = LocalBackend.current
    var code by rememberSaveable { mutableStateOf("") }
    var ip by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("53317") }
    val digits = code.filter { it.isDigit() }

    Card(onClick = { nav.qr = true }, padding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconTile("qr_code_scanner", c.accT, c.acc, size = 48.dp, radius = 12.dp, iconSize = 26.dp)
            Texts(stringResource(R.string.scan_qr), stringResource(R.string.scan_qr_hint), titleSize = 15.sp, wrap = true)
            Ic("chevron_right", color = c.tx3)
        }
    }
    Card(gap = 10.dp) {
        Label(stringResource(R.string.my_code), Modifier.padding(horizontal = 0.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Txt(s.me.code.ifEmpty { "··· ···" }, Modifier.weight(1f), size = 32.sp, weight = 500, mono = true, letterSpacing = 1.9.sp)
            RoundBtn("refresh", { b.newCode() }, bg = c.surf2)
        }
        Txt(stringResource(R.string.my_code_hint), size = 12.sp, color = c.tx3)
    }
    Card(gap = 10.dp) {
        Label(stringResource(R.string.other_code))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Input(code, { v -> code = v.filter { it.isDigit() || it == ' ' }.take(7) }, "000 000", Modifier.weight(1f), mono = true, size = 18.sp, keyboard = KeyboardType.Number, letterSpacing = 1.4.sp)
            Btn(stringResource(R.string.done), { b.pairCode(digits); code = "" }, height = 48.dp, enabled = digits.length == 6)
        }
    }
    Card(gap = 10.dp) {
        Label(stringResource(R.string.by_ip))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Input(ip, { v -> ip = v.filter { it.isDigit() || it == '.' }.take(15) }, "192.168.1.10", Modifier.weight(1f), mono = true, keyboard = KeyboardType.Decimal)
            Input(port, { v -> port = v.filter { it.isDigit() }.take(5) }, "53317", Modifier.width(84.dp), mono = true, keyboard = KeyboardType.Number)
        }
        Btn(
            stringResource(R.string.connect), { b.connectIp(ip.trim(), port.toIntOrNull() ?: 53317); ip = "" },
            Modifier.fillMaxWidth(), height = 48.dp, bg = c.surf2, color = c.tx, border = c.line, enabled = ip.count { it == '.' } == 3,
        )
    }
}
