package com.astraplay.tv.ui.movies

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.astraplay.tv.R
import com.astraplay.tv.core.ServiceLocator
import com.astraplay.tv.data.sources.*
import com.astraplay.tv.ui.common.*
import com.astraplay.tv.ui.theme.ThemeManager
import kotlinx.coroutines.*
import okhttp3.Request
import org.jsoup.Jsoup

class SourcesActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private lateinit var sources: OnlineSources
    private var job: Job? = null
    private var selected: OnlineSource? = null
    private var mediaType = "movies"

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        sources = OnlineSources(this)
        showSources()
    }

    private fun screen(title: String, subtitle: String): LinearLayout {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(this@SourcesActivity), 8.dp(this@SourcesActivity), 16.dp(this@SourcesActivity), 12.dp(this@SourcesActivity))
        }
        setContentView(root)
        root.addView(makeTitle(this, title))
        root.addView(TextView(this).apply {
            text = subtitle
            textSize = 13f
            setTextColor(ThemeManager.colorOf(this@SourcesActivity, android.R.attr.textColorSecondary))
            setPadding(16.dp(this@SourcesActivity), 0, 16.dp(this@SourcesActivity), 12.dp(this@SourcesActivity))
        })
        return root
    }

    private fun showSources() {
        job?.cancel()
        selected = null
        screen("Фильмы и сериалы", "Выберите источник • удерживайте ОК, чтобы изменить адрес")
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabs.addView(tvButton(this, if (mediaType == "movies") "● Фильмы" else "Фильмы") { mediaType = "movies"; showSources() })
        tabs.addView(tvButton(this, if (mediaType == "series") "● Сериалы" else "Сериалы") { mediaType = "series"; showSources() })
        tabs.addView(tvButton(this, "Торренты", R.drawable.ic_magnet) {
            startActivity(Intent(this, com.astraplay.tv.ui.torrent.TorrentPlayerActivity::class.java))
        })
        root.addView(tabs)
        val scroll = ScrollView(this)
        val grid = GridLayout(this).apply { columnCount = if (resources.configuration.screenWidthDp < 700) 2 else 3 }
        for (source in sources.all().filter { mediaType == "series" || it.id != "seasonvar" }) {
            val card = tvButton(this, source.name + "\n" + if (source.address == null) "Нужен адрес" else source.note, R.drawable.ic_movie) {
                if (source.address == null) edit(source) else showCatalog(source)
            }.apply {
                minHeight = 92.dp(this@SourcesActivity)
                textSize = 15f
                setOnLongClickListener { edit(source); true }
            }
            grid.addView(card, GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(6.dp(this@SourcesActivity), 6.dp(this@SourcesActivity), 6.dp(this@SourcesActivity), 6.dp(this@SourcesActivity))
            })
        }
        scroll.addView(grid)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun edit(source: OnlineSource) {
        Dialogs.input(this, "Адрес • ${source.name}", "https://…", source.address ?: "") { value ->
            runCatching { sources.setAddress(source.id, value) }.fold(
                onSuccess = { showSources() },
                onFailure = { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() })
        }
    }

    private fun showCatalog(source: OnlineSource) {
        job?.cancel()
        selected = source
        screen(source.name, "Выберите фильм или сериал. Воспроизведение доступно на странице источника.")
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(tvButton(this, "Источники", R.drawable.ic_arrow_back) { showSources() })
        actions.addView(tvButton(this, "Открыть сайт / поиск", R.drawable.ic_search) { openPage(source.name, source.address!!) })
        actions.addView(tvButton(this, "Войти на сайт") {
            val home = android.net.Uri.parse(source.address!!)
            val login = if (source.id == "lostfilm") home.buildUpon().path("/login").clearQuery().fragment(null).build().toString() else source.address!!
            openPage("Вход • ${source.name}", login)
        })
        actions.addView(tvButton(this, "Обновить", R.drawable.ic_refresh) { showCatalog(source) })
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(actions) })
        val status = TextView(this).apply {
            text = "Загружаем каталог…"
            setPadding(16.dp(this@SourcesActivity), 12.dp(this@SourcesActivity), 16.dp(this@SourcesActivity), 12.dp(this@SourcesActivity))
        }
        root.addView(status)
        val grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@SourcesActivity, (resources.configuration.screenWidthDp / 160).coerceIn(2, 7))
            setPadding(8.dp(this@SourcesActivity), 4.dp(this@SourcesActivity), 8.dp(this@SourcesActivity), 12.dp(this@SourcesActivity))
            clipToPadding = false
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        val cookieManager = android.webkit.CookieManager.getInstance().apply { setAcceptCookie(true) }
        val sessionClient = ServiceLocator.http.client.newBuilder().cookieJar(SourceSession(cookieManager)).build()
        val browserAgent = runCatching { android.webkit.WebSettings.getDefaultUserAgent(this) }.getOrDefault("AstraPlay/1.4")
        job = lifecycleScope.launch {
            try {
                val catalog = SourceCatalogLoader(sessionClient, browserAgent).load(source, mediaType)
                if (catalog.entries.isEmpty()) {
                    status.text = "Открываем каталог во встроенном браузере…"
                    openPage(source.name, catalog.address)
                } else {
                    status.text = "Доступно: ${catalog.entries.size} • ${if (mediaType == "movies") "Фильмы" else "Сериалы"}"
                    grid.adapter = TitlesAdapter(catalog.entries)
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (error is kotlinx.coroutines.TimeoutCancellationException && isActive) {
                    status.text = "Каталог не ответил вовремя. Открываем сайт…"
                    openPage(source.name, SourceCatalogLoader.categoryAddress(source.id, source.address!!, mediaType))
                } else throw error
            } catch (error: Exception) {
                status.text = "Не удалось получить карточки. Открываем сайт внутри приложения…"
                openPage(source.name, SourceCatalogLoader.categoryAddress(source.id, source.address!!, mediaType))
            }
        }
    }

    private fun openPage(title: String, url: String) {
        job?.cancel()
        lifecycleScope.launch(Dispatchers.IO) { ServiceLocator.store.addHistory(
            com.astraplay.tv.data.db.MediaEntry(kind = com.astraplay.tv.data.db.Kinds.WEB,
                itemId = "web_${url.hashCode()}", title = title, url = url)) }
        startActivity(Intent(this, SourceWebActivity::class.java).putExtra("url", url).putExtra("title", title))
    }

    private class Holder(val card: PosterCard) : RecyclerView.ViewHolder(card)
    private inner class TitlesAdapter(val entries: List<OnlineTitle>) : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = entries.size
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): Holder = Holder(PosterCard(this@SourcesActivity).apply {
            applyMode(PosterCard.MODE_POSTER)
            layoutParams = RecyclerView.LayoutParams(-1, 248.dp(this@SourcesActivity)).apply {
                setMargins(6.dp(this@SourcesActivity), 6.dp(this@SourcesActivity), 6.dp(this@SourcesActivity), 12.dp(this@SourcesActivity))
            }
        })
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = entries[position]
            holder.card.label.text = entry.title
            holder.card.loadPoster(entry.poster)
            holder.card.setOnClickListener { openPage(entry.title, entry.url) }
            holder.card.setOnLongClickListener {
                lifecycleScope.launch(Dispatchers.IO) { ServiceLocator.store.addFavorite(
                    com.astraplay.tv.data.db.MediaEntry(kind = com.astraplay.tv.data.db.Kinds.WEB,
                        itemId = "web_${entry.url.hashCode()}", title = entry.title, url = entry.url, poster = entry.poster)) }
                Toast.makeText(this@SourcesActivity, "Добавлено в избранное", Toast.LENGTH_SHORT).show()
                true
            }
        }
    }

    @Deprecated("Back compatibility for Android TV 7+")
    override fun onBackPressed() {
        if (selected != null) showSources() else super.onBackPressed()
    }
}
