package com.ssebanom.pythonide

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        val sitePackages = File(filesDir, "site-packages")
        Python.getInstance().getModule("ide_runner")
            .callAttr("init", sitePackages.absolutePath, cacheDir.absolutePath)
    }
}
