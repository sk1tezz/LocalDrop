package com.skz.localdrop.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.skz.localdrop.R
import com.skz.localdrop.core.AppState
import com.skz.localdrop.core.Device
import com.skz.localdrop.core.Transfer
import com.skz.localdrop.ui.theme.Ld

/** Shared derived values for screens (the design's dv/tv/histView helpers). */

@Composable
fun devName(s: AppState, id: String): String =
    if (id == "me" || id.isEmpty()) stringResource(R.string.this_phone) else s.device(id)?.name ?: "?"

fun devIcon(s: AppState, id: String): String = if (id == "me") "smartphone" else s.device(id)?.icon ?: "devices_other"

/** Online, paired and trusted devices: where files and audio can go right now. */
fun sendTargets(s: AppState): List<Device> = s.devices.filter { it.online && it.paired && it.trusted }

/** The device chosen on Home / Files, falling back to the first available one. */
fun currentTarget(s: AppState): Device? {
    val t = sendTargets(s)
    return t.firstOrNull { it.id == s.settings.sendTarget } ?: t.firstOrNull()
}

data class Feature(val color: Color, val tint: Color, val icon: String)

@Composable
fun feature(type: String): Feature {
    val c = Ld.c
    return when (type) {
        "clip" -> Feature(c.clip, c.clipT, "content_paste")
        "audio" -> Feature(c.audio, c.audioT, "graphic_eq")
        else -> Feature(c.files, c.filesT, "description")
    }
}

fun kindIcon(kind: String): String = when (kind) {
    "video" -> "movie"; "folder" -> "folder"; "doc" -> "description"; "archive" -> "folder_zip"
    "image" -> "image"; "audio" -> "music_note"; else -> "article"
}

fun dirIcon(dir: String) = if (dir == "out") "arrow_upward" else "arrow_downward"

/** "248 файлов · 1,1 ГБ" or "1,2 ГБ". */
fun transferMeta(f: Fmt, t: Transfer): String =
    (if (t.kind == "folder") f.pl(R.plurals.n_files, t.files) + " · " else "") + f.size(t.size)

fun transferPct(t: Transfer): Float = if (t.size <= 0) (if (t.status == "done") 1f else 0f) else (t.done.toFloat() / t.size).coerceIn(0f, 1f)

/** Right side of the progress line: speed and ETA, or the state. */
fun transferState(f: Fmt, t: Transfer): String = when (t.status) {
    "active" -> if (t.speed > 0) f.rate(t.speed) + " · " + f.eta((t.size - t.done) / t.speed) else f.s(R.string.st_starting)
    "paused" -> f.s(R.string.st_paused)
    "queued" -> f.s(R.string.st_queued)
    "offline" -> f.s(R.string.st_offline)
    "ask" -> f.s(R.string.st_ask)
    "waiting" -> f.s(R.string.st_waiting)
    "error" -> f.s(R.string.st_error)
    "declined" -> f.s(R.string.st_declined)
    "cancelled" -> f.s(R.string.st_cancelled)
    else -> f.s(R.string.st_done)
}

fun totalSpeed(s: AppState): Double = s.transfers.filter { it.status == "active" }.sumOf { it.speed }
