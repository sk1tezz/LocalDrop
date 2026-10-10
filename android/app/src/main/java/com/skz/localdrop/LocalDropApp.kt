package com.skz.localdrop

import android.app.Application
import com.skz.localdrop.core.Core

class LocalDropApp : Application() {
    /** Lives as long as the process, so activity restarts (language, theme) keep the state. */
    val core: Core by lazy { Core(this) }
}
