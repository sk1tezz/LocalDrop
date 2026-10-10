package com.skz.localdrop.ui

import android.app.Activity
import android.net.Uri
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.skz.localdrop.ui.components.PressIndication
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.LocalIndication
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skz.localdrop.R
import com.skz.localdrop.core.AppState
import com.skz.localdrop.core.Backend
import com.skz.localdrop.core.Toast
import com.skz.localdrop.core.UiEvent
import com.skz.localdrop.ui.components.Ic
import com.skz.localdrop.ui.components.Txt
import com.skz.localdrop.ui.screens.AudioScreen
import com.skz.localdrop.ui.screens.ClipScreen
import com.skz.localdrop.ui.screens.DeviceSheet
import com.skz.localdrop.ui.screens.DevicesScreen
import com.skz.localdrop.ui.screens.FilesScreen
import com.skz.localdrop.ui.screens.HistoryScreen
import com.skz.localdrop.ui.screens.HomeScreen
import com.skz.localdrop.ui.screens.Onboarding
import com.skz.localdrop.ui.screens.PairSheet
import com.skz.localdrop.ui.screens.QrScanner
import com.skz.localdrop.ui.screens.SettingsScreen
import com.skz.localdrop.ui.theme.Ld
import com.skz.localdrop.ui.theme.LdTheme
import kotlinx.coroutines.delay

/** Where the user is: tab, sub-screen and the overlays on top. */
class Nav {
    var tab by mutableStateOf("home")
    var sub by mutableStateOf<String?>(null)          // history | settings
    var sheet by mutableStateOf<String?>(null)        // device id of the open device sheet
    var qr by mutableStateOf(false)
    var devTab by mutableStateOf("nearby")            // nearby | trusted | add
    var audioMode by mutableStateOf("rx")             // rx | tx
    var onboarding by mutableStateOf(-1)              // step, -1 = closed

    fun go(t: String) { tab = t; sub = null; sheet = null }
    fun push(s: String) { sub = s; sheet = null }
}

/** System pickers; files go to the device passed in. */
class Pickers(val files: (String) -> Unit, val media: (String) -> Unit, val folder: (String) -> Unit, val saveDir: () -> Unit)

val LocalNav = staticCompositionLocalOf { Nav() }
val LocalBackend = staticCompositionLocalOf<Backend> { error("no backend") }
val LocalPickers = staticCompositionLocalOf { Pickers({}, {}, {}, {}) }

@Composable
fun LocalDropUi(backend: Backend, systemDark: Boolean) {
    val s by backend.state.collectAsStateWithLifecycle()
    val nav = remember { Nav() }
    val toasts = remember { mutableStateListOf<Pair<Long, Toast>>() }
    val dark = when (s.settings.theme) { "light" -> false; "system" -> systemDark; else -> true }

    LaunchedEffect(backend) {
        backend.events.collect { e ->
            when (e) {
                is UiEvent.ShowToast -> if (s.settings.notify || e.toast.kind == "bad") {
                    val id = System.nanoTime()
                    while (toasts.size >= 2) toasts.removeAt(0)
                    toasts.add(id to e.toast)
                }
                is UiEvent.Navigate -> nav.go(e.screen)
            }
        }
    }
    LaunchedEffect(s.settings.onboarded, s.ready) {
        if (s.ready && !s.settings.onboarded && nav.onboarding < 0) nav.onboarding = 0
    }

    var pickFor by remember { mutableStateOf("") }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) backend.sendUris(pickFor, uris)
    }
    val media = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) backend.sendUris(pickFor, uris)
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) backend.sendUris(pickFor, listOf(uri), folder = true)
    }
    val saveDir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) backend.setSaveDir(uri)
    }
    val pickers = remember {
        Pickers(
            files = { pickFor = it; files.launch(arrayOf("*/*")) },
            media = { pickFor = it; media.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            folder = { pickFor = it; folder.launch(null) },
            saveDir = { saveDir.launch(null) },
        )
    }

    val view = LocalView.current
    SideEffect {
        val w = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(w, view).run {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    LdTheme(dark, s.settings.featureColors) {
        CompositionLocalProvider(
            LocalNav provides nav, LocalBackend provides backend, LocalPickers provides pickers,
            LocalIndication provides PressIndication(if (dark) Color.White else Color.Black),
        ) {
            Box(Modifier.fillMaxSize().background(Ld.c.bg)) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).statusBarsPadding().imePadding()) { Screen(s, nav) }
                    if (nav.sub == null) BottomNav(s, nav)
                    else Box(Modifier.navigationBarsPadding())
                }
                val sheetDev = nav.sheet?.let { s.device(it) }
                DeviceSheet(sheetDev, s) { nav.sheet = null }
                PairSheet(s.pair)
                if (nav.qr) QrScanner { nav.qr = false }
                if (nav.onboarding >= 0) Onboarding(s)
                ToastHost(toasts) { id -> toasts.removeAll { it.first == id } }
            }
        }
    }
}

@Composable
private fun Screen(s: AppState, nav: Nav) {
    if (nav.sub != null) BackHandler { nav.sub = null }
    else if (nav.tab != "home") BackHandler { nav.go("home") }
    when (nav.sub ?: nav.tab) {
        "history" -> HistoryScreen(s)
        "settings" -> SettingsScreen(s)
        "devices" -> DevicesScreen(s)
        "files" -> FilesScreen(s)
        "clip" -> ClipScreen(s)
        "audio" -> AudioScreen(s)
        else -> HomeScreen(s)
    }
}

private data class NavItem(val id: String, val icon: String, val label: Int)

@Composable
private fun BottomNav(s: AppState, nav: Nav) {
    val items = listOf(
        NavItem("home", "home", R.string.nav_home), NavItem("devices", "devices", R.string.nav_devices),
        NavItem("files", "swap_vert", R.string.nav_files), NavItem("clip", "content_paste", R.string.nav_clip),
        NavItem("audio", "graphic_eq", R.string.nav_audio),
    )
    val c = Ld.c
    val activeTransfers = s.transfers.count { !it.final }
    val audioOn = s.audio.on || s.audio.incoming != null
    Column(Modifier.fillMaxWidth().background(c.side)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line2))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
            for (it in items) {
                val on = nav.tab == it.id
                val (fg, tint) = when (it.id) {
                    "files" -> c.files to c.filesT
                    "clip" -> c.clip to c.clipT
                    "audio" -> c.audio to c.audioT
                    else -> c.acc to c.accT
                }
                val badge = when {
                    it.id == "files" && activeTransfers > 0 -> activeTransfers.toString()
                    it.id == "audio" && audioOn -> "•"
                    else -> ""
                }
                Column(
                    Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(12.dp)).clickable { nav.go(it.id) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                ) {
                    Box(Modifier.size(56.dp, 30.dp).clip(RoundedCornerShape(15.dp)).background(if (on) tint else Color.Transparent), contentAlignment = Alignment.Center) {
                        Ic(it.icon, color = if (on) fg else c.tx2, filled = on)
                        if (badge.isNotEmpty()) {
                            Box(
                                Modifier.align(Alignment.TopEnd).offset((-6).dp, (-3).dp).height(17.dp).widthIn(min = 17.dp)
                                    .clip(RoundedCornerShape(9.dp)).background(c.side).padding(2.dp).clip(RoundedCornerShape(9.dp)).background(fg)
                                    .padding(horizontal = 3.dp),
                                contentAlignment = Alignment.Center,
                            ) { Txt(badge, size = 9.sp, weight = 800, color = c.onAcc, lineHeight = 10.sp) }
                        }
                    }
                    Txt(stringResource(it.label), size = 11.sp, weight = if (on) 800 else 600, color = if (on) c.tx else c.tx2, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ToastHost(toasts: List<Pair<Long, Toast>>, remove: (Long) -> Unit) {
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 10.dp, end = 10.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((id, t) in toasts) {
            LaunchedEffect(id) { delay(4000); remove(id) }
            key(id) { ToastCard(t) { remove(id) } }
        }
    }
}

@Composable
private fun key(id: Long, content: @Composable () -> Unit) = androidx.compose.runtime.key(id) { content() }

@Composable
private fun ToastCard(t: Toast, onClick: () -> Unit) {
    val c = Ld.c
    val (color, tint, icon) = when (t.kind) {
        "files" -> Triple(c.files, c.filesT, "folder_open")
        "clip" -> Triple(c.clip, c.clipT, "content_paste")
        "audio" -> Triple(c.audio, c.audioT, "graphic_eq")
        "ok" -> Triple(c.ok, c.okT, "check_circle")
        "bad" -> Triple(c.bad, c.badT, "error")
        else -> Triple(c.acc, c.accT, "devices")
    }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    AnimatedVisibility(shown, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
        val shape = RoundedCornerShape(22.dp)
        Row(
            Modifier.fillMaxWidth().shadow(16.dp, shape).clip(shape).background(c.surf).border(1.dp, c.line2, shape)
                .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(tint), contentAlignment = Alignment.Center) { Ic(icon, size = 20.dp, color = color) }
            Column(Modifier.weight(1f)) {
                Txt(stringResource(R.string.toast_head), size = 11.sp, weight = 600, color = c.tx3)
                Txt(t.title, size = 14.sp, weight = 700)
                if (t.text.isNotEmpty()) Txt(t.text, size = 13.sp, color = c.tx2, maxLines = 1)
            }
        }
    }
}
