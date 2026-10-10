package com.skz.localdrop

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import com.skz.localdrop.ui.theme.Ld
import com.skz.localdrop.ui.theme.LdTheme

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LdTheme(dark = true, sectionColors = true) {
                Box(Modifier.fillMaxSize().background(Ld.c.bg)) { BasicText("LocalDrop") }
            }
        }
    }
}
