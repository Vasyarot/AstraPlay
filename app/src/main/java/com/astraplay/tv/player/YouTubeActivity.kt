package com.astraplay.tv.player

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.astraplay.tv.ui.theme.ThemeManager

/**
 * Трейлеры YouTube через официальный IFrame-плеер — единственный
 * разрешённый способ встраивания (извлечение потока в свой плеер
 * нарушает условия YouTube и не используется).
 */
class YouTubeActivity : AppCompatActivity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        val videoId = intent.getStringExtra("videoId") ?: ""
        if (videoId.isBlank()) { finish(); return }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = WebViewClient()
            // WebChromeClient обязателен для полноэкранного html5-видео
            webChromeClient = WebChromeClient()
            setBackgroundColor(0xFF000000.toInt())
        }
        setContentView(web, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        web.loadUrl("https://www.youtube.com/embed/$videoId?autoplay=1&rel=0")
    }

    @Deprecated("Back compatibility for Android TV 7+")
    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::web.isInitialized) {
            (web.parent as? ViewGroup)?.removeView(web)
            web.stopLoading()
            web.destroy()
        }
        super.onDestroy()
    }
}
