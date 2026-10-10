package com.skz.localdrop.ui

import android.content.Context
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
class Fmt(private val ctx: Context) {
    private val res = ctx.resources
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
}

@Composable
fun rememberFmt(): Fmt {
    val ctx = LocalContext.current
    val conf = LocalConfiguration.current
    return remember(ctx, conf) { Fmt(ctx) }
}
