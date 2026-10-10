package com.skz.localdrop.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.skz.localdrop.R

/** Colors of the design (interface/android/LocalDropPhone.dc.html), oklch converted to sRGB. */
@Immutable
data class LdColors(
    val dark: Boolean,
    val page: Color, val bg: Color, val side: Color, val surf: Color, val surf2: Color, val hover: Color,
    val line: Color, val line2: Color, val tgOff: Color, val tx: Color, val tx2: Color, val tx3: Color, val onAcc: Color,
    val acc: Color, val accT: Color, val files: Color, val filesT: Color, val clip: Color, val clipT: Color,
    val audio: Color, val audioT: Color, val ok: Color, val okT: Color, val bad: Color, val badT: Color,
) {
    /** "Section colors" off: everything uses the accent. */
    fun mono(): LdColors = copy(clip = acc, clipT = accT, audio = acc, audioT = accT)
}

val DarkColors = LdColors(
    dark = true,
    page = Color(0xFF101112), bg = Color(0xFF18191B), side = Color(0xFF1E1F22), surf = Color(0xFF232427),
    surf2 = Color(0xFF2B2C30), hover = Color(0xFF313237), line = Color(0xFF36373C), line2 = Color(0xFF2A2B2F),
    tgOff = Color(0xFF44464C), tx = Color(0xFFEDEDEF), tx2 = Color(0xFFA4A6AC), tx3 = Color(0xFF76787E),
    onAcc = Color(0xFF0D1A1D),
    acc = Color(0xFF3BC4E0), accT = Color(0x243BC4E0), files = Color(0xFF3BC4E0), filesT = Color(0x243BC4E0),
    clip = Color(0xFFB7A1F5), clipT = Color(0x26B7A1F5), audio = Color(0xFFE2A355), audioT = Color(0x24E2A355),
    ok = Color(0xFF6DC88F), okT = Color(0x596DC88F), bad = Color(0xFFED756E), badT = Color(0x24ED756E),
)

val LightColors = LdColors(
    dark = false,
    page = Color(0xFFE4E5E8), bg = Color(0xFFF6F6F7), side = Color(0xFFEDEDEF), surf = Color(0xFFFFFFFF),
    surf2 = Color(0xFFF1F1F3), hover = Color(0xFFE8E8EB), line = Color(0xFFDFDFE3), line2 = Color(0xFFE9E9EC),
    tgOff = Color(0xFFCDCED3), tx = Color(0xFF18191B), tx2 = Color(0xFF5C5E64), tx3 = Color(0xFF8A8C92),
    onAcc = Color(0xFFFFFFFF),
    acc = Color(0xFF00849C), accT = Color(0x1C00849C), files = Color(0xFF00849C), filesT = Color(0x1C00849C),
    clip = Color(0xFF7966AE), clipT = Color(0x1C7966AE), audio = Color(0xFFA66B29), audioT = Color(0x1FA66B29),
    ok = Color(0xFF318F5A), okT = Color(0x4C318F5A), bad = Color(0xFFC04442), badT = Color(0x1AC04442),
)

@OptIn(ExperimentalTextApi::class)
private fun manrope(w: Int) = Font(R.font.manrope, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

@OptIn(ExperimentalTextApi::class)
private fun mono(w: Int) = Font(R.font.jbmono, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

val Manrope = FontFamily(manrope(400), manrope(500), manrope(600), manrope(700), manrope(800))
val JetBrainsMono = FontFamily(mono(400), mono(500))

@OptIn(ExperimentalTextApi::class)
private fun symbols(fill: Int) = FontFamily(
    Font(R.font.symbols, variationSettings = FontVariation.Settings(FontVariation.Setting("FILL", fill.toFloat()))))

val Symbols = symbols(0)
val SymbolsFilled = symbols(1)

val LocalLd = staticCompositionLocalOf { DarkColors }

/** Shorthand for the current palette: `Ld.c.acc`. */
object Ld {
    val c: LdColors @Composable get() = LocalLd.current
}

@Composable
fun LdTheme(dark: Boolean, sectionColors: Boolean, content: @Composable () -> Unit) {
    val base = if (dark) DarkColors else LightColors
    CompositionLocalProvider(LocalLd provides if (sectionColors) base else base.mono(), content = content)
}
