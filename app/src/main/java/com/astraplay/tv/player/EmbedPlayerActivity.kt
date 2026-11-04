package com.astraplay.tv.player

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.astraplay.tv.R
import com.astraplay.tv.data.sources.OnlineSources
import com.astraplay.tv.ui.theme.ThemeManager

/**
 * Полноэкранный официальный плеер источника (кнопка «Смотреть» у EMBED-серий):
 * загружается только сам плеер без страницы с рекламой, автозапуск видео,
 * ОК — пауза/плей. Файлы/торрент-ссылки передаются дальше по назначению.
 */
class EmbedPlayerActivity : AppCompatActivity() {
    private lateinit var root: FrameLayout
    private var web: WebView? = null
    private var fullscreen: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var positionMs = 0L
    private var durationMs = 0L
    private var completed = false
    private var snapshotAvailable = false
    private val poll = object : Runnable {
        override fun run() {
            val resume = intent.getLongExtra("pos", 0).coerceAtLeast(0) / 1000.0
            // Observe only an accessible HTML5 video. No JavaScript interface is exposed to websites.
            web?.evaluateJavascript("""(function(){var v=document.querySelector('video');
                if(!v)return null;
                if(!v.dataset.astraResume&&v.readyState>=1){v.dataset.astraResume='1';
                    if($resume>5&&isFinite(v.duration)&&$resume<v.duration-10)v.currentTime=$resume;}
                return {position:Math.max(0,Math.floor(v.currentTime*1000)),
                    duration:isFinite(v.duration)?Math.floor(v.duration*1000):0,completed:v.ended};})()""".trimIndent()) { raw ->
                runCatching { org.json.JSONObject(raw) }.getOrNull()?.let { data ->
                    snapshotAvailable = true
                    positionMs = data.optLong("position", positionMs).coerceAtLeast(0)
                    durationMs = data.optLong("duration", durationMs).coerceAtLeast(0)
                    if (data.optBoolean("completed") && !completed) {
                        completed = true; reportResult(); finish()
                    }
                }
            }
            if (!completed) root.postDelayed(this, 2000)
        }
    }

    private fun reportResult() {
        setResult(RESULT_OK, android.content.Intent().putExtra("position", positionMs)
            .putExtra("duration", durationMs).putExtra("completed", completed).putExtra("snapshotAvailable", snapshotAvailable))
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("url") ?: ""
        if (!OnlineSources.validWebUrl(url)) { finish(); return }
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val view = try { WebView(this) } catch (error: Exception) {
            Toast.makeText(this, "Для просмотра установите или обновите Android System WebView", Toast.LENGTH_LONG).show()
            finish(); return
        }
        web = view
        CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(view, true) }
        view.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = true
            // встраиваемый плеер должен стартовать сам: пользователь уже нажал «Смотреть»
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val target = request.url.toString()
                if (com.astraplay.tv.data.torrent.TorrServerClient.validLink(target)) {
                    startActivity(android.content.Intent(this@EmbedPlayerActivity,
                        com.astraplay.tv.ui.torrent.TorrentPlayerActivity::class.java).putExtra("link", target))
                    return true
                }
                // плеер живёт на своём домене: наружу не уходим
                return !OnlineSources.validWebUrl(target)
            }

            override fun onPageFinished(view: WebView, url: String) {
                CookieManager.getInstance().flush()
                // страховка автозапуска: некоторые плееры ждут «жест»
                view.evaluateJavascript(
                    "(function(){var v=document.querySelector('video');if(v&&v.paused){v.play().catch(function(){})}})()", null)
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (fullscreen != null) { callback.onCustomViewHidden(); return }
                fullscreen = view
                fullscreenCallback = callback
                view.setBackgroundColor(Color.BLACK)
                root.addView(view, FrameLayout.LayoutParams(-1, -1))
                view.requestFocus()
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            override fun onHideCustomView() { hideFullscreen() }
        }
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        view.requestFocus()
        if (savedInstanceState == null) view.loadUrl(url) else view.restoreState(savedInstanceState)
    }

    /** ОК — пауза/плей официального плеера. У части источников видео лежит
     *  в cross-origin iframe, куда JS верхнего документа не заглядывает, —
     *  тогда шлём пробел прямо в WebView: web-плееры понимают его как паузу. */
    private fun toggleVideo() {
        web?.evaluateJavascript(
            "(function(){var v=document.querySelector('video');if(v){if(v.paused){v.play().catch(function(){})}else{v.pause()}return 'video'}return 'none'})()"
        ) { result ->
            if (result?.contains("video") != true) {
                // видео в cross-origin iframe: клавиши идут в WebView напрямую
                web?.requestFocus()
                web?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                web?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                web?.post {
                    web?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE))
                    web?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE))
                }
            }
        }
    }

    private fun hideFullscreen() {
        fullscreen?.let { root.removeView(it) }
        fullscreen = null
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event?.action == KeyEvent.ACTION_DOWN) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    toggleVideo(); return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Back compatibility for Android TV 7+")
    override fun onBackPressed() {
        if (fullscreen != null) hideFullscreen() else super.onBackPressed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        web?.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() { super.onResume(); web?.onResume(); if (::root.isInitialized) root.post(poll) }

    override fun onPause() {
        if (::root.isInitialized) root.removeCallbacks(poll)
        reportResult()
        web?.onPause(); CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        hideFullscreen()
        web?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.stopLoading()
            it.destroy()
        }
        web = null
        super.onDestroy()
    }
}
