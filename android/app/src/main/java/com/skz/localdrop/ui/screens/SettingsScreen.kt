package com.skz.localdrop.ui.screens

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import com.skz.localdrop.BuildConfig
import com.skz.localdrop.R
import com.skz.localdrop.core.AppSettings
import com.skz.localdrop.core.AppState
import com.skz.localdrop.ui.LocalBackend
import com.skz.localdrop.ui.LocalNav
import com.skz.localdrop.ui.LocalPickers
import com.skz.localdrop.ui.components.BackHead
import com.skz.localdrop.ui.components.Btn
import com.skz.localdrop.ui.components.Card
import com.skz.localdrop.ui.components.Group
import com.skz.localdrop.ui.components.GroupRow
import com.skz.localdrop.ui.components.Ic
import com.skz.localdrop.ui.components.Input
import com.skz.localdrop.ui.components.Label
import com.skz.localdrop.ui.components.LinkBtn
import com.skz.localdrop.ui.components.Seg
import com.skz.localdrop.ui.components.SegItem
import com.skz.localdrop.ui.components.Texts
import com.skz.localdrop.ui.components.ToggleRow
import com.skz.localdrop.ui.components.Txt
import com.skz.localdrop.ui.rememberPermActions
import com.skz.localdrop.ui.theme.Ld

fun applyLanguage(lang: String) {
    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(lang))
}

@Composable
fun SettingsScreen(s: AppState) {
    val c = Ld.c
    val nav = LocalNav.current
    val b = LocalBackend.current
    val pick = LocalPickers.current
    val st = s.settings
    fun set(f: (AppSettings) -> AppSettings) = b.settings(f)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BackHead(stringResource(R.string.settings), { nav.sub = null })

        Label(stringResource(R.string.this_device))
        Card(padding = PaddingValues(14.dp), gap = 8.dp) {
            Txt(stringResource(R.string.net_name), size = 12.sp, color = c.tx2)
            var name by remember(st.deviceName) { mutableStateOf(st.deviceName) }
            Input(name, { v -> name = v.take(40); if (name.isNotBlank()) set { it.copy(deviceName = name.trim()) } }, size = 15.sp, weight = 600, height = 46.dp, modifier = Modifier.fillMaxWidth())
            Txt(stringResource(R.string.ip_port, s.me.ip.ifEmpty { "—" }, s.me.port), size = 12.sp, color = c.tx3)
        }

        Label(stringResource(R.string.appearance), Modifier.padding(top = 6.dp))
        Card(padding = PaddingValues(14.dp)) {
            Seg(
                listOf(
                    SegItem("dark", stringResource(R.string.th_dark), "dark_mode"), SegItem("light", stringResource(R.string.th_light), "light_mode"),
                    SegItem("system", stringResource(R.string.th_system), "contrast"),
                ),
                st.theme, { v -> set { it.copy(theme = v) } }, height = 40.dp, fontSize = 12.sp, iconSize = 17.dp,
            )
            ToggleRow(stringResource(R.string.section_colors), stringResource(R.string.section_colors_sub), st.featureColors) { set { it.copy(featureColors = !it.featureColors) } }
            Txt(stringResource(R.string.language), size = 14.sp, weight = 600)
            Seg(
                listOf(SegItem("ru", "Русский"), SegItem("en", "English")),
                st.lang, { v -> set { it.copy(lang = v) }; applyLanguage(v) }, height = 40.dp, fontSize = 12.sp,
            )
        }

        Group(R.string.features) {
            Row(stringResource(R.string.btn_files), stringResource(R.string.feat_files_sub), st.featFiles) { set { it.copy(featFiles = !it.featFiles) } }
            Row(stringResource(R.string.clipboard), stringResource(R.string.feat_clip_sub), st.featClip) { set { it.copy(featClip = !it.featClip) } }
            Row(stringResource(R.string.audio), stringResource(R.string.feat_audio_sub), st.featAudio) { set { it.copy(featAudio = !it.featAudio) } }
        }
        Group(R.string.network) {
            Row(stringResource(R.string.visible), stringResource(R.string.visible_sub), st.visible) { set { it.copy(visible = !it.visible) } }
            Row(stringResource(R.string.wifi_only), stringResource(R.string.wifi_only_sub), st.wifiOnly) { set { it.copy(wifiOnly = !it.wifiOnly) } }
        }
        Group(R.string.btn_files) {
            Row(stringResource(R.string.auto_accept), stringResource(R.string.auto_accept_sub), st.autoAccept) { set { it.copy(autoAccept = !it.autoAccept) } }
            Row(stringResource(R.string.keep_awake), stringResource(R.string.keep_awake_sub), st.keepAwake) { set { it.copy(keepAwake = !it.keepAwake) } }
        }
        Group(R.string.notifications) {
            Row(stringResource(R.string.notify), stringResource(R.string.notify_sub), st.notify) { set { it.copy(notify = !it.notify) } }
            Row(stringResource(R.string.sounds), stringResource(R.string.sounds_sub), st.sounds) { set { it.copy(sounds = !it.sounds) } }
        }

        Label(stringResource(R.string.save_folder), Modifier.padding(top = 6.dp))
        Card(padding = PaddingValues(14.dp), onClick = { pick.saveDir() }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Ic("folder", color = c.files)
                Txt(saveDirLabel(s), Modifier.weight(1f), size = 12.sp, mono = true)
                if (st.saveDir.isNotEmpty()) LinkBtn(stringResource(R.string.reset), { b.setSaveDir(null) }, c.tx2)
                Txt(stringResource(R.string.change), size = 13.sp, weight = 700, color = c.acc)
            }
        }

        Label(stringResource(R.string.permissions), Modifier.padding(top = 6.dp))
        PermList(s)

        Label(stringResource(R.string.quick_access), Modifier.padding(top = 6.dp))
        Group {
            GroupRow {
                Ic("toggle_on", color = c.tx2)
                Texts(stringResource(R.string.tile), stringResource(R.string.tile_sub), titleWeight = 600)
                if (s.perms.tile) Btn(stringResource(R.string.added), {}, height = 36.dp, radius = 18.dp, bg = c.surf2, color = c.tx2, fontSize = 13.sp, padding = 14.dp)
                else Btn(stringResource(R.string.add), pick.addTile, height = 36.dp, radius = 18.dp, bg = c.accT, color = c.acc, fontSize = 13.sp, padding = 14.dp)
            }
            GroupRow {
                Ic("widgets", color = c.tx2)
                Texts(stringResource(R.string.widget), stringResource(R.string.widget_sub), titleWeight = 600)
                Btn(stringResource(R.string.add), pick.addWidget, height = 36.dp, radius = 18.dp, bg = c.accT, color = c.acc, fontSize = 13.sp, padding = 14.dp)
            }
        }
        Txt(stringResource(R.string.version_line, BuildConfig.VERSION_NAME), Modifier.fillMaxWidth().padding(top = 10.dp), size = 12.sp, color = c.tx3, mono = true, align = TextAlign.Center)
    }
}

@Composable
private fun Group(title: Int, content: @Composable () -> Unit) {
    Label(stringResource(title), Modifier.padding(top = 6.dp))
    com.skz.localdrop.ui.components.Group { content() }
}

@Composable
private fun Row(title: String, desc: String, on: Boolean, onClick: () -> Unit) {
    ToggleRow(title, desc, on, padding = PaddingValues(14.dp), bg = Ld.c.surf, onClick = onClick)
}

/** Permission rows with "Allow" buttons (Settings and onboarding). */
@Composable
fun PermList(s: AppState, tinted: Boolean = false) {
    val c = Ld.c
    val b = LocalBackend.current
    val ask = rememberPermActions { b.refreshPerms() }
    val rows = listOf(
        Triple("notify", R.string.perm_notify to R.string.perm_notify_sub, "notifications") to s.perms.notify,
        Triple("battery", R.string.perm_battery to R.string.perm_battery_sub, "battery_charging_full") to s.perms.battery,
        Triple("mic", R.string.perm_mic to R.string.perm_mic_sub, "mic") to s.perms.mic,
        Triple("camera", R.string.perm_camera to R.string.perm_camera_sub, "photo_camera") to s.perms.camera,
    )
    com.skz.localdrop.ui.components.Group {
        for ((row, granted) in rows) {
            val (id, texts, icon) = row
            GroupRow(padding = PaddingValues(if (tinted) 14.dp else 14.dp, 12.dp)) {
                if (tinted) com.skz.localdrop.ui.components.IconTile(icon, c.accT, c.acc, size = 38.dp, iconSize = 20.dp)
                else Ic(icon, color = c.tx2)
                Texts(stringResource(texts.first), stringResource(texts.second), titleWeight = if (tinted) 700 else 600, wrap = true)
                if (granted) Ic("check_circle", color = c.ok)
                else Btn(stringResource(R.string.allow), { ask.ask(id) }, height = 36.dp, radius = 18.dp, bg = c.accT, color = c.acc, fontSize = 13.sp, padding = 14.dp)
            }
        }
    }
}
