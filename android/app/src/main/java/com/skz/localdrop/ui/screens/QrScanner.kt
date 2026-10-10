package com.skz.localdrop.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.skz.localdrop.R
import com.skz.localdrop.ui.LocalBackend
import com.skz.localdrop.ui.LocalNav
import com.skz.localdrop.ui.components.Btn
import com.skz.localdrop.ui.components.RoundBtn
import com.skz.localdrop.ui.components.Txt
import com.skz.localdrop.ui.theme.Ld
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Full-screen camera with a frame; finds a LocalDrop QR code (ZXing, works offline). */
@Composable
fun QrScanner(onClose: () -> Unit) {
    val c = Ld.c
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val b = LocalBackend.current
    BackHandler(onBack = onClose)
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var asked by remember { mutableStateOf(false) }
    val req = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; asked = true; b.refreshPerms() }
    LaunchedEffect(Unit) { if (!granted) req.launch(Manifest.permission.CAMERA) }
    var torch by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var bad by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color(0xFF141518))) {
        if (granted) CameraPreview({ camera = it }) { text ->
            if (text.startsWith("localdrop:")) { onClose(); b.qrScanned(text); true } else { bad = true; false }
        }
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RoundBtn("close", onClose, color = Color.White)
                Txt(stringResource(R.string.qr_title), Modifier.weight(1f), size = 17.sp, weight = 700, color = Color.White)
                RoundBtn(if (torch) "flash_on" else "flash_off", {
                    torch = !torch
                    camera?.cameraControl?.enableTorch(torch)
                }, color = Color.White, bg = if (torch) Color.White.copy(alpha = 0.2f) else Color.Transparent)
            }
            Column(Modifier.weight(1f).padding(horizontal = 24.dp).align(Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
                Frame(c.acc)
                val msg = when {
                    !granted && asked -> stringResource(R.string.qr_no_camera)
                    bad -> stringResource(R.string.qr_bad)
                    else -> stringResource(R.string.qr_hint)
                }
                Txt(msg, Modifier.widthIn(max = 280.dp), size = 14.sp, color = Color.White.copy(alpha = 0.8f), align = TextAlign.Center)
            }
            Box(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 44.dp).align(Alignment.CenterHorizontally)) {
                Btn(stringResource(R.string.qr_manual), { onClose(); nav.onboarding = -1; nav.go("devices"); nav.devTab = "add" }, icon = "pin",
                    bg = Color.White.copy(alpha = 0.14f), color = Color.White, height = 48.dp, radius = 24.dp, iconSize = 20.dp, padding = 22.dp)
            }
        }
    }
}

/** Rounded corner brackets with a breathing glow and a scan line sweeping up and down, 250dp square. */
@Composable
private fun Frame(acc: Color) {
    val t = rememberInfiniteTransition(label = "scan")
    val y by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "line")
    val pulse by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "corners")
    Canvas(Modifier.size(250.dp)) {
        val r = 24.dp.toPx()
        val len = 44.dp.toPx()
        val w = 4.dp.toPx()
        val s = size.width
        drawRoundRect(Color.Black.copy(alpha = 0.25f), cornerRadius = CornerRadius(r))
        fun corner(x: Float, yy: Float, dx: Float, dy: Float) {
            val p = Path().apply {
                moveTo(x, yy + dy * len)
                lineTo(x, yy + dy * r)
                quadraticTo(x, yy, x + dx * r, yy)
                lineTo(x + dx * len, yy)
            }
            drawPath(p, acc.copy(alpha = pulse), style = Stroke(w, cap = StrokeCap.Round))
        }
        val h = w / 2
        corner(h, h, 1f, 1f); corner(s - h, h, -1f, 1f); corner(h, s - h, 1f, -1f); corner(s - h, s - h, -1f, -1f)
        // the scan line with a soft trail
        val top = r
        val bottom = s - r
        val ly = top + (bottom - top) * y
        val glow = 36.dp.toPx()
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, acc.copy(alpha = 0.22f), Color.Transparent), ly - glow, ly + glow),
            Offset(r, ly - glow), Size(s - 2 * r, glow * 2),
        )
        drawRoundRect(acc, Offset(r, ly - 1.dp.toPx()), Size(s - 2 * r, 2.dp.toPx()), CornerRadius(1.dp.toPx()))
    }
}

@Composable
private fun CameraPreview(onCamera: (Camera) -> Unit, onText: (String) -> Boolean) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val done = remember { AtomicBoolean(false) }
    val reader = remember {
        MultiFormatReader().apply { setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)) }
    }
    val main = remember { ContextCompat.getMainExecutor(ctx) }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }
    AndroidView(
        factory = { context ->
            val view = PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { img ->
                    val text = decode(reader, img)
                    img.close()
                    if (text != null && !done.get()) main.execute { if (!done.get() && onText(text)) done.set(true) }
                }
                runCatching {
                    provider.unbindAll()
                    onCamera(provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis))
                }
            }, main)
            view
        },
        modifier = Modifier.fillMaxSize(),
    )
}

private fun decode(reader: MultiFormatReader, img: ImageProxy): String? {
    val plane = img.planes[0]
    val buf = plane.buffer
    val data = ByteArray(buf.remaining()).also { buf.get(it) }
    val w = plane.rowStride
    val src = PlanarYUVLuminanceSource(data, w, img.height, 0, 0, img.width, img.height, false)
    return try {
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(src))).text
    } catch (_: Exception) {
        null
    } finally {
        reader.reset()
    }
}
