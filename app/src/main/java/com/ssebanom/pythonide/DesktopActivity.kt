package com.ssebanom.pythonide

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/**
 * Shows the Linux desktop running in the container. The container serves
 * noVNC over http://127.0.0.1:6080 (websockify --web), so this is just a
 * full-screen WebView pointed at localhost — no external apps needed.
 */
class DesktopActivity : AppCompatActivity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        web = WebView(this)
        web.setBackgroundColor(Color.BLACK)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        web.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    view?.loadData(
                        "<html><body style='background:#121317;color:#d8dbe0;" +
                            "font-family:monospace;padding:24px'>" +
                            "<h3>Desktop is not reachable</h3>" +
                            "<p>Start it first: menu → Linux container → " +
                            "<b>Start Desktop</b>, wait ~15 seconds, then " +
                            "reopen this view.</p>" +
                            "<p><a style='color:#4fc3f7' href='http://127.0.0.1:6080/vnc.html" +
                            "?autoconnect=true&resize=scale&reconnect=true'>Retry</a></p>" +
                            "</body></html>",
                        "text/html", "utf-8"
                    )
                }
            }
        }
        setContentView(web)
        hideSystemBars()
        web.loadUrl(
            "http://127.0.0.1:6080/vnc.html" +
                "?autoconnect=true&resize=scale&reconnect=true&reconnect_delay=2000"
        )
    }

    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
