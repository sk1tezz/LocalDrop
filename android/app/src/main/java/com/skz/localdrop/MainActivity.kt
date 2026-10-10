package com.skz.localdrop

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import com.skz.localdrop.ui.LocalDropUi
import com.skz.localdrop.ui.demo.DemoBackend

class MainActivity : AppCompatActivity() {
    private val backend get() = (application as LocalDropApp).backend

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { LocalDropUi(backend, isSystemInDarkTheme()) }
        intent?.getStringExtra("demo")?.let { (backend as? DemoBackend)?.demo(it) }
    }
}
