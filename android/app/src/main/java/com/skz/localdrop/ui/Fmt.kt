package com.skz.localdrop.ui

import android.content.Context
import android.content.res.Resources
import com.skz.localdrop.protocol.net.json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.skz.localdrop.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt

/** Sizes, speeds, times and plurals, as in ui/core.js on the desktop. */
class Fmt(private val res: Resources) {
    constructor(ctx: Context) : this(ctx.resources)

    val ru: Boolean = res.configuration.locales[0].language == "ru"

    fun s(id: Int, vararg args: Any): String = res.getString(id, *args)
    fun pl(id: Int, n: Int): String = res.getQuantityString(id, n, n)

    private fun num(x: Double, digits: Int): String {
        val s = String.format(Locale.US, "%.${digits}f", x)
        return if (ru) s.replace('.', ',') else s
    }

    fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> num(bytes / 1073741824.0, 1) + " " + s(R.string.u_gb)
        bytes >= 1L shl 20 -> (bytes / 1048576.0).roundToInt().toString() + " " + s(R.string.u_mb)
        bytes >= 1024 -> (bytes / 1024.0).roundToInt().toString() + " " + s(R.string.u_kb)
        else -> "$bytes " + s(R.string.u_b)
    }

    fun rate(bps: Double): String {
        val mb = bps / 1048576
        return s(R.string.rate_mb, if (mb >= 10) mb.roundToInt().toString() else num(mb, 1))
    }

    fun eta(sec: Double): String = when {
        !sec.isFinite() -> ""
        sec >= 3600 -> s(R.string.eta_h, (sec / 3600).toInt(), (sec % 3600 / 60).toInt())
        sec >= 60 -> s(R.string.eta_m, (sec / 60).toInt(), (sec % 60).roundToInt())
        else -> s(R.string.eta_s, maxOf(1, sec.roundToInt()))
    }

    fun duration(sec: Long): String {
        val h = sec / 3600
        val m = sec % 3600 / 60
        return if (h > 0) s(R.string.dur_hm, h.toInt(), m.toString().padStart(2, '0')) else s(R.string.dur_m, maxOf(1, m.toInt()))
    }

    fun hhmm(ts: Double): String = SimpleDateFormat("HH:mm", Locale.US).format(Date((ts * 1000).toLong()))

    private fun dayStart(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun dayLabel(ts: Double): String {
        val today = dayStart(System.currentTimeMillis())
        val day = dayStart((ts * 1000).toLong())
        return when {
            day == today -> s(R.string.today)
            today - day in 82_800_000..90_000_000 -> s(R.string.yesterday)
            else -> SimpleDateFormat("d MMMM", res.configuration.locales[0]).format(Date((ts * 1000).toLong()))
        }
    }

    fun ago(ts: Double): String {
        val sec = System.currentTimeMillis() / 1000.0 - ts
        val label = dayLabel(ts)
        return when {
            sec < 60 -> s(R.string.just_now)
            sec < 3600 -> s(R.string.min_ago, floor(sec / 60).toInt())
            label == s(R.string.today) -> s(R.string.h_ago, floor(sec / 3600).toInt())
            label == s(R.string.yesterday) -> s(R.string.yesterday_lc)
            else -> label
        }
    }

    fun greeting(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return s(when {
            h < 5 -> R.string.greet_night
            h < 12 -> R.string.greet_morning
            h < 18 -> R.string.greet_day
            else -> R.string.greet_evening
        })
    }

    /** History lines and errors are stored as {"k": key, "a": [args]} so they follow the UI language. */
    fun sub(raw: String): String {
        val o = parse(raw) ?: return raw
        val key = (o["k"] as? JsonPrimitive)?.content ?: return raw
        val args = (o["a"] as? JsonArray)?.map { arg(it) } ?: emptyList()
        val id = res.getIdentifier(key, "string", PKG)
        return if (id != 0) res.getString(id, *args.toTypedArray()) else key
    }

    /** Error codes from the file engine or the other device. */
    fun err(raw: String): String {
        if (raw.isEmpty()) return ""
        val o = parse(raw)
        val key = o?.let { (it["k"] as? JsonPrimitive)?.content } ?: raw
        val args = (o?.get("a") as? JsonArray)?.map { arg(it) } ?: emptyList()
        val id = ERR[key] ?: return if (args.isEmpty()) key else key.replace("{0}", args[0])
        return res.getString(id, *args.toTypedArray())
    }

    private fun parse(raw: String): JsonObject? =
        if (raw.startsWith("{")) runCatching { json.parseToJsonElement(raw) as JsonObject }.getOrNull() else null

    private fun arg(e: kotlinx.serialization.json.JsonElement): String = when (e) {
        is JsonObject -> when {
            "size" in e -> size((e["size"] as JsonPrimitive).content.toDouble().toLong())
            "files" in e -> pl(R.plurals.n_files, (e["files"] as JsonPrimitive).content.toDouble().toInt())
            "dur" in e -> duration((e["dur"] as JsonPrimitive).content.toDouble().toLong())
            else -> ""
        }
        is JsonPrimitive -> e.content.let {
            when {
                it.startsWith("{") -> err(it)
                it.startsWith("q_") -> res.getIdentifier(it, "string", PKG).takeIf { id -> id != 0 }?.let { id -> res.getString(id) } ?: it
                else -> it
            }
        }
        else -> e.toString()
    }

    companion object {
        private const val PKG = "com.skz.localdrop"
        private val ERR = mapOf(
            "disabled" to R.string.e_disabled, "space" to R.string.e_space, "unknown" to R.string.e_unknown,
            "bad offer" to R.string.e_bad_offer, "error" to R.string.e_error, "read failed" to R.string.e_read,
            "changed" to R.string.e_changed, "hash" to R.string.e_hash, "list mismatch" to R.string.e_list,
            "no fin" to R.string.e_fin, "bad path" to R.string.e_path, "bad resume offset" to R.string.e_resume,
            "cannot write {0}" to R.string.e_write, "no free file name" to R.string.e_name, "data without file" to R.string.e_proto,
            "bad message" to R.string.e_proto, "empty offer" to R.string.e_bad_offer, "paused" to R.string.e_paused,
            "declined" to R.string.st_declined, "cancelled" to R.string.st_cancelled,
        )
    }
}

@Composable
fun rememberFmt(): Fmt {
    val ctx = LocalContext.current
    val conf = LocalConfiguration.current
    return remember(ctx, conf) { Fmt(ctx) }
}
