package com.notdxbi.dxbi.tv.marquee

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/**
 * A trailer in YouTube's embedded player, full screen inside Marquee: used when the trailer's own
 * addresses can't be read for Marquee's player. The page is given this app as its origin, which
 * YouTube asks embedded players in apps to send (without it the player refuses with error 153).
 */
class TrailerActivity : AppCompatActivity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("yt")?.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.orEmpty()
        if (id.isEmpty()) { finish(); return }
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.domStorageEnabled = true
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
        }
        setContentView(web)
        val origin = "https://$packageName"
        val html = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}iframe{border:0;width:100%;height:100%}</style></head>
            <body><iframe src="https://www.youtube-nocookie.com/embed/$id?autoplay=1&rel=0&modestbranding=1&playsinline=1&enablejsapi=1&origin=$origin"
            allow="autoplay; encrypted-media; fullscreen" referrerpolicy="strict-origin-when-cross-origin" allowfullscreen></iframe></body></html>
        """.trimIndent()
        web.loadDataWithBaseURL("$origin/", html, "text/html", "utf-8", null)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() { if (::web.isInitialized) web.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); if (::web.isInitialized) web.onResume() }
    override fun onDestroy() { if (::web.isInitialized) web.destroy(); super.onDestroy() }
}
