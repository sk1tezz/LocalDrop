package com.skz.localdrop

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import com.skz.localdrop.core.Backend
import com.skz.localdrop.service.LocalDropService
import com.skz.localdrop.ui.LocalDropUi
import com.skz.localdrop.ui.demo.DemoBackend

class MainActivity : AppCompatActivity() {
    private val core get() = (application as LocalDropApp).core

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // debug builds can show the mockup's data: adb shell am start ... -e demo onboarding|pair_in|pair_out|ask|on
        val demo = if (BuildConfig.DEBUG) intent?.getStringExtra("demo") else null
        val backend: Backend = if (demo != null) DemoBackend(this).also { it.demo(demo) } else core
        if (demo == null) {
            LocalDropService.start(this)
            core.start()
        }
        setContent { LocalDropUi(backend, isSystemInDarkTheme()) }
    }

    override fun onStart() {
        super.onStart()
        core.appVisible = true
        core.refreshPerms()
    }

    override fun onStop() {
        core.appVisible = false
        super.onStop()
    }
}
