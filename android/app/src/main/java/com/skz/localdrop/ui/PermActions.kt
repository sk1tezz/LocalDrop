package com.skz.localdrop.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.skz.localdrop.core.Perms

/** Current permission state, read from the system. */
fun readPerms(ctx: Context, tile: Boolean = false): Perms {
    fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    val pm = ctx.getSystemService(PowerManager::class.java)
    return Perms(
        notify = Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS),
        battery = pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true,
        mic = has(Manifest.permission.RECORD_AUDIO),
        camera = has(Manifest.permission.CAMERA),
        tile = tile,
    )
}

/** Asks for one permission by the ids used in the UI: notify | battery | mic | camera. */
class PermActions(val ask: (String) -> Unit)

@SuppressLint("BatteryLife")
@Composable
fun rememberPermActions(onResult: () -> Unit): PermActions {
    val ctx = LocalContext.current
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onResult() }
    val battery = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onResult() }
    return remember {
        PermActions { kind ->
            when (kind) {
                "notify" -> if (Build.VERSION.SDK_INT >= 33) runtime.launch(Manifest.permission.POST_NOTIFICATIONS) else openAppSettings(ctx)
                "mic" -> runtime.launch(Manifest.permission.RECORD_AUDIO)
                "camera" -> runtime.launch(Manifest.permission.CAMERA)
                "battery" -> battery.launch(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName))
                )
            }
        }
    }
}

fun openAppSettings(ctx: Context) {
    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
