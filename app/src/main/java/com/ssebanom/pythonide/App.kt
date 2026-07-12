package com.ssebanom.pythonide

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class App : Application() {

    companion object {
        /** Non-null if Python failed to start (e.g. native libs can't load).
         *  MainActivity shows this instead of letting the app crash silently. */
        @Volatile
        var startupError: String? = null
    }

    override fun onCreate() {
        super.onCreate()

        // Persist any uncaught crash so it can be shown/shared after restart,
        // instead of the app just disappearing.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            runCatching {
                File(filesDir, "last_crash.txt").writeText(
                    "Crash on thread ${thread.name}:\n" + stackToString(ex)
                )
            }
            previous?.uncaughtException(thread, ex)
        }

        try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(this))
            }
            val sitePackages = File(filesDir, "site-packages")
            Python.getInstance().getModule("ide_runner")
                .callAttr("init", sitePackages.absolutePath, cacheDir.absolutePath)
        } catch (e: Throwable) {
            // Loading the embedded CPython can fail (e.g. incompatible page
            // size / ABI). Capture it so the UI can explain what happened.
            val text = stackToString(e)
            startupError = text
            runCatching { File(filesDir, "last_crash.txt").writeText(text) }
        }
    }

    private fun stackToString(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }
}
