package com.astraplay.tv.ui.movies

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.astraplay.tv.R
import com.astraplay.tv.data.sources.OnlineSources
import com.astraplay.tv.ui.common.*
import com.astraplay.tv.ui.theme.ThemeManager

/** Standard website player with full-screen HTML5 support; no private APIs or access bypass. */
class SourceWebActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private lateinit var status: TextView
    private var fullscreen: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("url") ?: ""
        if (!OnlineSources.validWebUrl(url)) { finish(); return }
        root = FrameLayout(this)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(tvButton(this, "Назад", R.drawable.ic_arrow_back) { onBackPressed() })
        actions.addView(tvButton(this, "Обновить", R.drawable.ic_refresh) { if (::web.isInitialized) web.reload() })
        actions.addView(tvButton(this, "Адрес", R.drawable.ic_search) {
            Dialogs.input(this, "Открыть страницу", "https://…", if (::web.isInitialized) web.url ?: url else url) { value ->
                if (OnlineSources.validWebUrl(value) && ::web.isInitialized) web.loadUrl(value)
                else Toast.makeText(this, "Нужен адрес http:// или https://", Toast.LENGTH_LONG).show()
            }
        })
        actions.addView(tvButton(this, "Войти") {
            if (::web.isInitialized) {
                val current = Uri.parse(web.url ?: url)
                val login = if (current.host.orEmpty().endsWith("lostfilm.tv")) current.buildUpon().path("/login").clearQuery().fragment(null).build().toString()
                    else current.buildUpon().path("/").clearQuery().fragment(null).build().toString()
                web.loadUrl(login)
            }
        })
        actions.addView(tvButton(this, "Сбросить входы") {
            Dialogs.confirm(this, "Выйти со всех сайтов?", "Сессии всех источников будут удалены. После этого можно войти заново.") {
                CookieManager.getInstance().removeAllCookies { if (::web.isInitialized) web.post { web.reload() } }
                CookieManager.getInstance().flush()
            }
        })
        actions.addView(tvButton(this, "Во внешнем браузере") {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(if (::web.isInitialized) web.url ?: url else url))) }
                .onFailure { Toast.makeText(this, "На устройстве нет браузера", Toast.LENGTH_LONG).show() }
        })
        toolbar.addView(actions)
        column.addView(toolbar)
        status = TextView(this).apply {
            textSize = 12f
            setPadding(12.dp(this@SourceWebActivity), 4.dp(this@SourceWebActivity), 12.dp(this@SourceWebActivity), 4.dp(this@SourceWebActivity))
            setTextColor(ThemeManager.colorOf(this@SourceWebActivity, android.R.attr.textColorSecondary))
        }
        column.addView(status)
        try { web = WebView(this) } catch (error: Exception) {
            column.addView(TextView(this).apply { text = "Для просмотра сайтов установите или обновите Android System WebView." })
            root.addView(column)
            setContentView(root)
            return
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
        }
        web.setDownloadListener { target, _, _, _, _ ->
            if (com.astraplay.tv.data.torrent.TorrServerClient.validLink(target)) startActivity(Intent(this, com.astraplay.tv.ui.torrent.TorrentPlayerActivity::class.java).putExtra("link", target))
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val target = request.url.toString()
                if (com.astraplay.tv.data.torrent.TorrServerClient.validLink(target)) {
                    startActivity(Intent(this@SourceWebActivity, com.astraplay.tv.ui.torrent.TorrentPlayerActivity::class.java).putExtra("link", target))
                    return true
                }
                return !OnlineSources.validWebUrl(target)
            }

            override fun onPageFinished(view: WebView, url: String) {
                CookieManager.getInstance().flush()
                status.text = "${Uri.parse(url).host} • ОК — выбрать • Назад — предыдущая страница"
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) status.text = "Сайт недоступен: ${error.description}. Можно изменить адрес источника."
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, progress: Int) {
                if (progress < 100) status.text = "Загрузка… $progress%"
            }
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
        column.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(column)
        setContentView(root)
        if (savedInstanceState == null) web.loadUrl(url) else web.restoreState(savedInstanceState)
    }

    private fun hideFullscreen() {
        fullscreen?.let { root.removeView(it) }
        fullscreen = null
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::web.isInitialized) web.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() { super.onResume(); if (::web.isInitialized) web.onResume() }
    override fun onPause() {
        if (::web.isInitialized) { web.onPause(); CookieManager.getInstance().flush() }
        super.onPause()
    }

    @Deprecated("Back compatibility for Android TV 7+")
    override fun onBackPressed() {
        if (fullscreen != null) hideFullscreen()
        else if (::web.isInitialized && web.canGoBack()) web.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        hideFullscreen()
        if (::web.isInitialized) {
            (web.parent as? ViewGroup)?.removeView(web)
            web.stopLoading()
            web.destroy()
        }
        super.onDestroy()
    }
}
