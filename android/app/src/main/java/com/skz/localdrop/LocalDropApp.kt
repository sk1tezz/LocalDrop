package com.skz.localdrop

import android.app.Application
import com.skz.localdrop.core.Backend
import com.skz.localdrop.ui.demo.DemoBackend

class LocalDropApp : Application() {
    /** Lives as long as the process, so activity restarts (language, theme) keep the state. */
    val backend: Backend by lazy { DemoBackend(this) }
}
