package com.skz.localdrop.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.skz.localdrop.ui.theme.JetBrainsMono
import com.skz.localdrop.ui.theme.Ld
import com.skz.localdrop.ui.theme.Manrope
import com.skz.localdrop.ui.theme.Symbols
import com.skz.localdrop.ui.theme.SymbolsFilled

// ---- text and icons ---------------------------------------------------------

@Composable
fun Txt(
    text: String, modifier: Modifier = Modifier, size: TextUnit = 14.sp, weight: Int = 400, color: Color = Ld.c.tx,
    mono: Boolean = false, maxLines: Int = Int.MAX_VALUE, align: TextAlign? = null, lineHeight: TextUnit = TextUnit.Unspecified,
    letterSpacing: TextUnit = TextUnit.Unspecified,
) {
    BasicText(
        text, modifier,
        style = TextStyle(
            color = color, fontSize = size, fontWeight = FontWeight(weight),
            fontFamily = if (mono) JetBrainsMono else Manrope, textAlign = align ?: TextAlign.Unspecified,
            lineHeight = if (lineHeight == TextUnit.Unspecified) size * 1.35f else lineHeight, letterSpacing = letterSpacing,
        ),
        maxLines = maxLines, overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

/** Material Symbols Rounded icon by ligature name. */
@Composable
fun Ic(name: String, modifier: Modifier = Modifier, size: Dp = 22.dp, color: Color = Ld.c.tx, filled: Boolean = false) {
    val fs = with(LocalDensity.current) { size.toSp() }
    BasicText(
        name, modifier,
        style = TextStyle(color = color, fontSize = fs, lineHeight = fs, fontFamily = if (filled) SymbolsFilled else Symbols),
        maxLines = 1, softWrap = false,
    )
}

// ---- building blocks --------------------------------------------------------

fun Modifier.tap(onClick: (() -> Unit)?): Modifier = if (onClick == null) this else this.clickable(onClick = onClick)

/** Rounded surface with the hairline border used for cards. */
@Composable
fun Card(
    modifier: Modifier = Modifier, radius: Dp = 16.dp, bg: Color = Ld.c.surf, border: Color? = Ld.c.line2,
    padding: PaddingValues = PaddingValues(16.dp), gap: Dp = 14.dp, onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Column(
        modifier.fillMaxWidth().clip(shape).background(bg, shape)
            .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
            .tap(onClick).padding(padding),
        verticalArrangement = Arrangement.spacedBy(gap), content = content,
    )
}

/** Rows separated by 1dp lines inside one rounded container. */
@Composable
fun Group(modifier: Modifier = Modifier, radius: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(radius)
    Column(
        modifier.fillMaxWidth().clip(shape).background(Ld.c.line2, shape).border(1.dp, Ld.c.line2, shape),
        verticalArrangement = Arrangement.spacedBy(1.dp), content = content,
    )
}

/** One row of a [Group]. */
@Composable
fun GroupRow(
    modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, padding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    gap: Dp = 12.dp, content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth().background(Ld.c.surf).tap(onClick).padding(padding),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap), content = content,
    )
}

/** Square tile with an icon: device and feature badges. */
@Composable
fun IconTile(icon: String, tint: Color, color: Color, size: Dp = 40.dp, radius: Dp = 10.dp, iconSize: Dp = 22.dp, dot: Color? = null, dotBorder: Color = Ld.c.surf, filled: Boolean = false) {
    Box(Modifier.size(size)) {
        Box(Modifier.size(size).clip(RoundedCornerShape(radius)).background(tint), contentAlignment = Alignment.Center) {
            Ic(icon, size = iconSize, color = color, filled = filled)
        }
        if (dot != null) {
            Box(
                Modifier.align(Alignment.BottomEnd).offset(3.dp, 3.dp).size(12.dp).clip(CircleShape)
                    .background(dotBorder).padding(2.dp).clip(CircleShape).background(dot)
            )
        }
    }
}

@Composable
fun Dot(color: Color, size: Dp = 7.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))

/** Pulsing green dot while scanning. */
@Composable
fun ScanDot() {
    val t = rememberInfiniteTransition(label = "scan")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Restart), label = "pulse")
    val ok = Ld.c.ok
    Canvas(Modifier.size(8.dp)) {
        drawCircle(ok.copy(alpha = 0.35f * (1 - p)), radius = size.minDimension / 2 + 7.dp.toPx() * p)
        drawCircle(ok)
    }
}

// ---- controls ---------------------------------------------------------------

@Composable
fun Toggle(on: Boolean, color: Color = Ld.c.acc, onClick: () -> Unit) {
    val knob by animateDpAsState(if (on) 21.dp else 3.dp, tween(150), label = "knob")
    Box(
        Modifier.size(46.dp, 28.dp).clip(RoundedCornerShape(14.dp)).background(if (on) color else Ld.c.tgOff)
            .clickable(onClick = onClick)
    ) {
        Box(Modifier.offset(knob, 3.dp).size(22.dp).shadow(1.dp, CircleShape).clip(CircleShape).background(Color.White))
    }
}

data class SegItem(val id: String, val label: String, val icon: String? = null, val count: String? = null)

/** Segmented control: grey track with a raised white (surf) pill on the chosen item. */
@Composable
fun Seg(items: List<SegItem>, value: String, onChange: (String) -> Unit, height: Dp = 38.dp, fontSize: TextUnit = 13.sp, iconSize: Dp = 18.dp) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ld.c.surf2).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (it in items) {
            val on = it.id == value
            Row(
                Modifier.weight(1f).height(height)
                    .then(if (on) Modifier.shadow(1.dp, RoundedCornerShape(9.dp)) else Modifier)
                    .clip(RoundedCornerShape(9.dp)).background(if (on) Ld.c.surf else Color.Transparent)
                    .clickable { onChange(it.id) },
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
            ) {
                val c = if (on) Ld.c.tx else Ld.c.tx2
                if (it.icon != null) Ic(it.icon, size = iconSize, color = c)
                Txt(it.label, size = fontSize, weight = 700, color = c, maxLines = 1)
                if (it.count != null) Txt(it.count, size = 11.sp, color = Ld.c.tx3, mono = true)
            }
        }
    }
}

/** Pill-shaped choice chip (device chips, filters). */
@Composable
fun Chip(
    label: String, on: Boolean, color: Color, tint: Color, onClick: () -> Unit, icon: String? = null,
    height: Dp = 36.dp, fontSize: TextUnit = 13.sp, iconSize: Dp = 18.dp, modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(height / 2)
    Row(
        modifier.height(height).clip(shape).background(if (on) tint else Color.Transparent, shape)
            .border(1.dp, if (on) Color.Transparent else Ld.c.line, shape).clickable(onClick = onClick)
            .padding(start = if (icon != null) 10.dp else 14.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val c = if (on) color else Ld.c.tx2
        if (icon != null) Ic(icon, size = iconSize, color = c)
        Txt(label, size = fontSize, weight = 700, color = c, maxLines = 1)
    }
}

/** Radio card: ring + title + description + optional trailing icon. */
@Composable
fun RadioCard(title: String, desc: String, on: Boolean, onClick: () -> Unit, icon: String? = null, color: Color = Ld.c.audio, tint: Color = Ld.c.audioT) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(if (on) tint else Ld.c.surf, shape)
            .border(1.dp, if (on) color else Ld.c.line2, shape).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(20.dp).clip(CircleShape).border(2.dp, if (on) color else Ld.c.line, CircleShape), contentAlignment = Alignment.Center) {
            if (on) Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        }
        Column(Modifier.weight(1f)) {
            Txt(title, size = 14.sp, weight = 700)
            Txt(desc, size = 12.sp, color = Ld.c.tx2)
        }
        if (icon != null) Ic(icon, color = Ld.c.tx3)
    }
}

/** Round icon button (44dp touch target by default). */
@Composable
fun RoundBtn(icon: String, onClick: () -> Unit, size: Dp = 44.dp, iconSize: Dp = 22.dp, color: Color = Ld.c.tx2, bg: Color = Color.Transparent) {
    Box(Modifier.size(size).clip(CircleShape).background(bg).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Ic(icon, size = iconSize, color = color)
    }
}

/** Filled button; [bg] decides the style (accent, tint, surface). */
@Composable
fun Btn(
    label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: String? = null, bg: Color = Ld.c.acc,
    color: Color = Ld.c.onAcc, border: Color? = null, height: Dp = 52.dp, radius: Dp = 12.dp, fontSize: TextUnit = 14.sp,
    iconSize: Dp = 22.dp, vertical: Boolean = false, enabled: Boolean = true, padding: Dp = 16.dp,
) {
    val shape = RoundedCornerShape(radius)
    val m = modifier.height(height).alpha(if (enabled) 1f else 0.45f).clip(shape).background(bg, shape)
        .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = padding)
    if (vertical) {
        Column(m, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
            if (icon != null) Ic(icon, size = iconSize, color = color)
            Txt(label, size = fontSize, weight = 700, color = color, maxLines = 1)
        }
    } else {
        Row(m, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            if (icon != null) Ic(icon, size = iconSize, color = color)
            Txt(label, size = fontSize, weight = 700, color = color, maxLines = 1)
        }
    }
}

/** Accent-colored text button ("Все устройства", "Очистить"). */
@Composable
fun LinkBtn(label: String, onClick: () -> Unit, color: Color = Ld.c.acc) {
    Box(Modifier.height(36.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
        Txt(label, size = 13.sp, weight = 700, color = color)
    }
}

@Composable
fun ProgressBar(fraction: Float, color: Color, height: Dp = 6.dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(height / 2)
    Box(modifier.fillMaxWidth().height(height).clip(shape).background(Ld.c.surf2)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height).clip(shape).background(color))
    }
}

/** Search field: pill with a magnifier. */
@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp)).background(Ld.c.surf2).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Ic("search", color = Ld.c.tx3)
        Field(value, onChange, placeholder, Modifier.weight(1f), size = 14.sp)
    }
}

/** Bare single-line text field with a placeholder. */
@Composable
fun Field(
    value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, size: TextUnit = 14.sp,
    mono: Boolean = false, weight: Int = 400, keyboard: KeyboardType = KeyboardType.Text, letterSpacing: TextUnit = TextUnit.Unspecified,
) {
    val style = TextStyle(color = Ld.c.tx, fontSize = size, fontWeight = FontWeight(weight), fontFamily = if (mono) JetBrainsMono else Manrope, letterSpacing = letterSpacing)
    BasicTextField(
        value, onChange, modifier, singleLine = true, textStyle = style, cursorBrush = SolidColor(Ld.c.acc),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) BasicText(placeholder, style = style.copy(color = Ld.c.tx3), maxLines = 1)
                inner()
            }
        },
    )
}

/** Boxed input (code, IP, device name). */
@Composable
fun Input(
    value: String, onChange: (String) -> Unit, placeholder: String = "", modifier: Modifier = Modifier, mono: Boolean = false,
    size: TextUnit = 14.sp, weight: Int = 400, height: Dp = 48.dp, keyboard: KeyboardType = KeyboardType.Text, letterSpacing: TextUnit = TextUnit.Unspecified,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.height(height).clip(shape).background(Ld.c.surf2, shape).border(1.dp, Ld.c.line, shape).padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Field(value, onChange, placeholder, Modifier.fillMaxWidth(), size = size, mono = mono, weight = weight, keyboard = keyboard, letterSpacing = letterSpacing)
    }
}

/** Range slider 0..100 drawn like the design's range input. */
@Composable
fun Slider(value: Int, onChange: (Int) -> Unit, color: Color = Ld.c.audio, modifier: Modifier = Modifier, onDone: (() -> Unit)? = null) {
    var width by remember { mutableFloatStateOf(1f) }
    val track = Ld.c.surf2
    val line = Ld.c.line
    fun at(x: Float) = ((x / width) * 100).toInt().coerceIn(0, 100)
    Canvas(
        modifier.height(28.dp).onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) { detectTapGestures { onChange(at(it.x)); onDone?.invoke() } }
            .pointerInput(Unit) {
                detectDragGestures(onDragEnd = { onDone?.invoke() }) { change, _ -> onChange(at(change.position.x)) }
            }
    ) {
        val h = 4.dp.toPx()
        val r = 9.dp.toPx()
        val y = size.height / 2
        val x = (size.width - 2 * r) * value / 100f + r
        drawRoundRect(track, Offset(r, y - h / 2), Size(size.width - 2 * r, h), CornerRadius(h / 2))
        drawRoundRect(color, Offset(r, y - h / 2), Size(x - r, h), CornerRadius(h / 2))
        drawCircle(line, r + 0.5f, Offset(x, y))
        drawCircle(color, r, Offset(x, y))
        drawCircle(Color.White, r - 3.dp.toPx(), Offset(x, y))
    }
}

// ---- headers ----------------------------------------------------------------

/** Tab screen header: big title + trailing actions, 60dp tall. */
@Composable
fun ScreenHead(title: String, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt(title, Modifier.weight(1f), size = 24.sp, weight = 800, letterSpacing = (-0.48).sp, maxLines = 1)
        trailing()
    }
}

/** Sub-screen header with a back arrow. */
@Composable
fun BackHead(title: String, onBack: () -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(60.dp).offset((-8).dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        RoundBtn("arrow_back", onBack, color = Ld.c.tx)
        Txt(title, Modifier.weight(1f), size = 22.sp, weight = 800, letterSpacing = (-0.22).sp, maxLines = 1)
        trailing()
    }
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier) = Txt(text, modifier.padding(horizontal = 2.dp), size = 12.sp, weight = 700, color = Ld.c.tx2)

@Composable
fun SectionHead(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Txt(title, Modifier.weight(1f), size = 16.sp, weight = 800)
        if (action != null) LinkBtn(action, onAction)
    }
}

@Composable
fun Empty(text: String, boxed: Boolean = false) {
    val m = if (boxed) Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ld.c.surf).border(1.dp, Ld.c.line2, RoundedCornerShape(14.dp)).padding(vertical = 22.dp, horizontal = 16.dp)
    else Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 16.dp)
    Box(m, contentAlignment = Alignment.Center) { Txt(text, size = 13.sp, color = Ld.c.tx3, align = TextAlign.Center) }
}

/** Info line with a leading icon (hints under cards). */
@Composable
fun Hint(text: String, icon: String = "info") {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Ic(icon, size = 16.dp, color = Ld.c.tx3)
        Txt(text, Modifier.weight(1f), size = 12.sp, color = Ld.c.tx3, lineHeight = 17.sp)
    }
}

/** Two-line text block that takes the remaining row width. */
@Composable
fun RowScope.Texts(title: String, sub: String?, titleSize: TextUnit = 14.sp, titleWeight: Int = 700, subColor: Color = Ld.c.tx2, subMono: Boolean = false, wrap: Boolean = false) {
    Column(Modifier.weight(1f)) {
        Txt(title, size = titleSize, weight = titleWeight, maxLines = if (wrap) Int.MAX_VALUE else 1)
        if (!sub.isNullOrEmpty()) Txt(sub, size = 12.sp, color = subColor, mono = subMono, maxLines = if (wrap) Int.MAX_VALUE else 1)
    }
}

/** Row: texts on the left, a switch on the right. */
@Composable
fun ToggleRow(title: String, desc: String?, on: Boolean, color: Color = Ld.c.acc, padding: PaddingValues = PaddingValues(0.dp), bg: Color = Color.Transparent, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(bg).padding(padding), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Txt(title, size = 14.sp, weight = 600)
            if (!desc.isNullOrEmpty()) Txt(desc, size = 12.sp, color = Ld.c.tx2)
        }
        Toggle(on, color, onClick)
    }
}

// ---- overlays ---------------------------------------------------------------

/** Bottom sheet with a dimmed backdrop; tapping outside or Back closes it. */
@Composable
fun Sheet(visible: Boolean, onDismiss: () -> Unit, dim: Float = 0.5f, content: @Composable ColumnScope.() -> Unit) {
    if (visible) BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            )
        }
        AnimatedVisibility(
            visible, Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(220)) { it }, exit = slideOutVertically(tween(180)) { it },
        ) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(Ld.c.surf)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Box(Modifier.size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Ld.c.line).align(Alignment.CenterHorizontally))
                content()
            }
        }
    }
}

@Composable
fun BoxScope.Fill(color: Color) = Box(Modifier.matchParentSize().background(color))

@Composable
fun VGap(h: Dp) = Spacer(Modifier.height(h))

@Composable
fun HGap(w: Dp) = Spacer(Modifier.width(w))

/** Dashed rounded outline ("Или «Поделиться»…", "Добавить устройство"). */
fun Modifier.dashed(color: Color, radius: Dp): Modifier = this.drawBehind {
    val w = 1.dp.toPx()
    drawRoundRect(
        color, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(radius.toPx()),
        style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
    )
}

@Composable
fun Badge(text: String, color: Color, tint: Color) {
    Box(Modifier.clip(RoundedCornerShape(999.dp)).background(tint).padding(horizontal = 9.dp, vertical = 4.dp)) {
        Txt(text, size = 11.sp, weight = 800, color = color)
    }
}

@Composable
fun Centered(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) =
    Box(modifier.fillMaxWidth().widthIn(max = 640.dp), contentAlignment = Alignment.Center, content = content)

/** Press feedback: a soft overlay while the finger is down (no Material dependency). */
class PressIndication(private val color: Color) : androidx.compose.foundation.IndicationNodeFactory {
    override fun create(interactionSource: androidx.compose.foundation.interaction.InteractionSource): androidx.compose.ui.node.DelegatableNode =
        PressNode(interactionSource, color)

    override fun equals(other: Any?) = other is PressIndication && other.color == color
    override fun hashCode() = color.hashCode()
}

private class PressNode(
    private val source: androidx.compose.foundation.interaction.InteractionSource, private val color: Color,
) : androidx.compose.ui.Modifier.Node(), androidx.compose.ui.node.DrawModifierNode {
    private val alpha = androidx.compose.animation.core.Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            var pressed = 0
            source.interactions.collect { i ->
                when (i) {
                    is androidx.compose.foundation.interaction.PressInteraction.Press -> pressed++
                    is androidx.compose.foundation.interaction.PressInteraction.Release,
                    is androidx.compose.foundation.interaction.PressInteraction.Cancel -> pressed = maxOf(0, pressed - 1)
                }
                launch { alpha.animateTo(if (pressed > 0) 0.08f else 0f, tween(if (pressed > 0) 60 else 220)) }
            }
        }
    }

    override fun androidx.compose.ui.graphics.drawscope.ContentDrawScope.draw() {
        drawContent()
        if (alpha.value > 0f) drawRect(color.copy(alpha = alpha.value))
    }
}
