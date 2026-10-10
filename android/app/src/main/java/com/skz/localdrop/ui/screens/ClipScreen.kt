package com.skz.localdrop.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skz.localdrop.R
import com.skz.localdrop.core.AppState
import com.skz.localdrop.core.Clip
import com.skz.localdrop.ui.LocalBackend
import com.skz.localdrop.ui.components.Btn
import com.skz.localdrop.ui.components.Card
import com.skz.localdrop.ui.components.Chip
import com.skz.localdrop.ui.components.Empty
import com.skz.localdrop.ui.components.Hint
import com.skz.localdrop.ui.components.IconTile
import com.skz.localdrop.ui.components.RoundBtn
import com.skz.localdrop.ui.components.ScreenHead
import com.skz.localdrop.ui.components.ToggleRow
import com.skz.localdrop.ui.components.Txt
import com.skz.localdrop.ui.devName
import com.skz.localdrop.ui.rememberFmt
import com.skz.localdrop.ui.theme.Ld
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ClipScreen(s: AppState) {
    val c = Ld.c
    val b = LocalBackend.current
    var filter by rememberSaveable { mutableStateOf("all") }
    val paired = s.devices.filter { it.paired }
    val clips = s.clips.filter { filter == "all" || it.type == filter }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHead(stringResource(R.string.clipboard)) { RoundBtn("delete_sweep", { b.clipClear() }) }
        Card {
            Btn(stringResource(R.string.clip_send_phone), { b.clipSendCurrent() }, Modifier.fillMaxWidth(), icon = "content_paste_go", bg = c.clip)
            ToggleRow(stringResource(R.string.clip_auto), stringResource(R.string.clip_auto_sub), s.settings.clipAuto, c.clip) {
                b.settings { it.copy(clipAuto = !it.clipAuto) }
            }
            if (paired.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (d in paired) {
                    val on = d.id in s.settings.clipDevices
                    Chip(d.name, on, c.clip, c.clipT, {
                        b.settings { st -> st.copy(clipDevices = if (on) st.clipDevices - d.id else st.clipDevices + d.id) }
                    }, icon = if (on) "check" else "add", height = 32.dp, fontSize = 12.sp, iconSize = 16.dp)
                }
            }
            Hint(stringResource(R.string.clip_android_hint))
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((id, label) in listOf("all" to R.string.f_all, "text" to R.string.f_text, "image" to R.string.f_images)) {
                Chip(stringResource(label), filter == id, c.clip, c.clipT, { filter = id }, height = 34.dp)
            }
        }
        if (clips.isEmpty()) Empty(stringResource(R.string.clip_history_empty))
        for (clip in clips) ClipCard(s, clip)
    }
}

@Composable
private fun ClipCard(s: AppState, clip: Clip) {
    val c = Ld.c
    val f = rememberFmt()
    val b = LocalBackend.current
    Card(radius = 14.dp, padding = PaddingValues(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), gap = 8.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconTile(if (clip.type == "image") "image" else "notes", c.clipT, c.clip, size = 30.dp, radius = 8.dp, iconSize = 17.dp)
            Row(Modifier.weight(1f)) {
                Txt(devName(s, clip.dev), size = 12.sp, weight = 700, maxLines = 1)
                Txt(" · " + f.ago(clip.ts), size = 12.sp, color = c.tx2, maxLines = 1)
            }
            RoundBtn("content_copy", { b.clipCopy(clip.id) }, size = 36.dp, iconSize = 19.dp)
            RoundBtn("send", { b.clipSend(clip.id) }, size = 36.dp, iconSize = 19.dp)
            RoundBtn("delete", { b.clipDelete(clip.id) }, size = 36.dp, iconSize = 19.dp, color = c.tx3)
        }
        if (clip.type == "image") {
            Row(Modifier.padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Thumb(clip.image)
                Column(Modifier.weight(1f)) {
                    Txt(clip.text, size = 14.sp, weight = 600)
                    if (clip.meta.isNotEmpty()) Txt(clip.meta, size = 12.sp, color = c.tx3)
                }
            }
        } else {
            val code = looksLikeCode(clip.text)
            Txt(clip.text, Modifier.padding(end = 6.dp), size = if (code) 13.sp else 14.sp, mono = code, maxLines = 3, lineHeight = 20.sp)
        }
    }
}

/** Commands and paths read better in a monospace font. */
private fun looksLikeCode(t: String): Boolean {
    val s = t.trim()
    if (s.length > 200 || s.contains('\n') && !s.contains("  ")) return false
    return Regex("""^(\$ |sudo |pip |npm |git |cd |[A-Za-z]:\\|/[\w.-]+/|[\w.-]+\.(py|kt|js|json|sh)\b)""").containsMatchIn(s) || Regex("""==|&&|\|\||->|::""").containsMatchIn(s)
}

@Composable
private fun Thumb(path: String) {
    val c = Ld.c
    val bmp by produceState<ImageBitmap?>(null, path) {
        value = if (path.isEmpty()) null else withContext(Dispatchers.IO) {
            runCatching {
                val o = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeFile(path, o)?.asImageBitmap()
            }.getOrNull()
        }
    }
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.size(72.dp, 52.dp).clip(shape).background(c.surf2)) {
        val img = bmp
        if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else {
            val stripe = c.hover
            Canvas(Modifier.fillMaxSize()) {
                val step = 14.dp.toPx()
                var x = -size.height
                while (x < size.width) {
                    drawLine(stripe, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 7.dp.toPx())
                    x += step
                }
            }
        }
    }
}
