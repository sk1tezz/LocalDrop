package com.skz.localdrop.system

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider as GColor
import com.skz.localdrop.LocalDropApp
import com.skz.localdrop.MainActivity
import com.skz.localdrop.R
import com.skz.localdrop.core.AppState
import com.skz.localdrop.service.ActionReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Home screen widget: status, the current transfer and Send / Clipboard / Audio buttons. */
class LocalDropWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val core = (context.applicationContext as LocalDropApp).core
        core.start()
        provideContent {
            val s by core.state.collectAsState()
            Content(context, s)
        }
    }

    private fun c(day: Long, night: Long): GColor = ColorProvider(Color(day), Color(night))

    @Composable
    private fun Content(ctx: Context, s: AppState) {
        val core = (ctx.applicationContext as LocalDropApp).core
        val surf = c(0xFFFFFFFF, 0xFF232427)
        val tx = c(0xFF18191B, 0xFFEDEDEF)
        val tx2 = c(0xFF5C5E64, 0xFFA4A6AC)
        val acc = c(0xFF00849C, 0xFF3BC4E0)
        val online = s.devices.count { it.online && it.paired }
        val audioOn = s.audio.on || s.audio.incoming != null
        Column(GlanceModifier.fillMaxWidth().background(surf).cornerRadius(26.dp).padding(16.dp)) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(GlanceModifier.size(30.dp).background(acc).cornerRadius(9.dp), contentAlignment = Alignment.Center) {
                    Image(ImageProvider(R.drawable.ic_stat), null, GlanceModifier.size(18.dp), colorFilter = androidx.glance.ColorFilter.tint(c(0xFFFFFFFF, 0xFF0D1A1D)))
                }
                Spacer(GlanceModifier.width(10.dp))
                Text("LocalDrop", GlanceModifier.defaultWeight(), style = TextStyle(color = tx, fontSize = 15.sp, fontWeight = FontWeight.Bold))
                Text(core.str(R.string.w_online, online), style = TextStyle(color = tx2, fontSize = 12.sp))
            }
            val cur = s.transfers.firstOrNull { it.status == "active" }
            if (cur != null) {
                Spacer(GlanceModifier.height(12.dp))
                val dev = s.device(cur.dev)?.name ?: ""
                val pct = if (cur.size > 0) (cur.done * 100 / cur.size).toInt() else 0
                Text("${cur.name} ${if (cur.dir == "out") "→" else "←"} $dev · $pct%", style = TextStyle(color = tx, fontSize = 12.sp), maxLines = 1)
                Spacer(GlanceModifier.height(6.dp))
                LinearProgressIndicator(pct / 100f, GlanceModifier.fillMaxWidth().height(5.dp), color = acc, backgroundColor = c(0xFFF1F1F3, 0xFF2B2C30))
            }
            Spacer(GlanceModifier.height(14.dp))
            Row(GlanceModifier.fillMaxWidth()) {
                Button(core.str(R.string.send), R.drawable.ic_w_upload, c(0x1C00849C, 0x243BC4E0), acc,
                    actionStartActivity(Intent(ctx, MainActivity::class.java).putExtra("screen", "files").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))
                Spacer(GlanceModifier.width(8.dp))
                Button(core.str(R.string.nav_clip), R.drawable.ic_w_clip, c(0x1C7966AE, 0x26B7A1F5), c(0xFF7966AE, 0xFFB7A1F5),
                    actionStartActivity(Intent(ctx, ClipSendActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))
                Spacer(GlanceModifier.width(8.dp))
                Button(core.str(R.string.audio), R.drawable.ic_w_audio,
                    if (audioOn) c(0xFFA66B29, 0xFFE2A355) else c(0x1FA66B29, 0x24E2A355),
                    if (audioOn) c(0xFFFFFFFF, 0xFF0D1A1D) else c(0xFFA66B29, 0xFFE2A355),
                    actionSendBroadcast(Intent(ctx, ActionReceiver::class.java).setAction(ActionReceiver.AUDIO_TOGGLE)))
            }
        }
    }

    @Composable
    private fun androidx.glance.layout.RowScope.Button(label: String, icon: Int, bg: GColor, fg: GColor, action: Action) {
        Column(
            GlanceModifier.defaultWeight().height(64.dp).background(bg).cornerRadius(16.dp).clickable(action),
            horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(ImageProvider(icon), null, GlanceModifier.size(22.dp), colorFilter = androidx.glance.ColorFilter.tint(fg))
            Text(label, style = TextStyle(color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold), maxLines = 1)
        }
    }

    companion object {
        private var job: Job? = null
        private var last = ""

        /** Refresh the widgets when what they show changes (at most every 2 s). */
        fun refresh(ctx: Context, s: AppState) {
            val cur = s.transfers.firstOrNull { it.status == "active" }
            val key = "${s.devices.count { it.online && it.paired }}|${cur?.id}|${cur?.let { it.done * 20 / maxOf(1, it.size) }}|${s.audio.on || s.audio.incoming != null}|${s.settings.lang}"
            if (key == last || job?.isActive == true) return
            last = key
            job = CoroutineScope(Dispatchers.Default).launch {
                runCatching { LocalDropWidget().updateAll(ctx) }
                delay(2000)
            }
        }
    }
}

class LocalDropWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LocalDropWidget()
}
