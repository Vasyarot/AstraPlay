package com.astraplay.tv.ui.search

import android.content.Intent
import android.os.Bundle
import android.app.SearchManager
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.astraplay.tv.R
import com.astraplay.tv.core.ServiceLocator
import com.astraplay.tv.data.db.Kinds
import com.astraplay.tv.data.db.MediaEntry
import com.astraplay.tv.data.tmdb.TmdbItem
import com.astraplay.tv.ui.common.Dialogs
import com.astraplay.tv.ui.common.PosterCard
import com.astraplay.tv.ui.common.dp
import com.astraplay.tv.ui.common.makeTitle
import com.astraplay.tv.ui.nav.Nav
import com.astraplay.tv.ui.theme.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Поиск: запрос из глобального/голосового поиска TV или ручной ввод.
 * Ищет одновременно в TMDB и в каналах всех плейлистов.
 */
class SearchActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var grid: RecyclerView

    private var query: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(root)

        root.addView(makeTitle(this, getString(R.string.search)))

        val searchBtn = TextView(this).apply {
            textSize = 15f
            isFocusable = true
            isClickable = true
            setPadding(16.dp(this@SearchActivity), 10.dp(this@SearchActivity), 16.dp(this@SearchActivity), 10.dp(this@SearchActivity))
            setTextColor(ThemeManager.colorOf(this@SearchActivity, android.R.attr.textColorPrimary))
            text = getString(R.string.search_hint)
            background = com.astraplay.tv.ui.common.roundedBg(this@SearchActivity,
                ThemeManager.cardColor(this@SearchActivity), 10f,
                ThemeManager.strokeColor(this@SearchActivity), 1f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(16.dp(this@SearchActivity), 0, 16.dp(this@SearchActivity), 4.dp(this@SearchActivity)) }
            setOnClickListener { askQuery() }
        }
        root.addView(searchBtn)

        status = TextView(this).apply {
            textSize = 13f
            setPadding(16.dp(this@SearchActivity), 8.dp(this@SearchActivity), 16.dp(this@SearchActivity), 8.dp(this@SearchActivity))
            setTextColor(ThemeManager.colorOf(this@SearchActivity, android.R.attr.textColorSecondary))
        }
        root.addView(status)

        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@SearchActivity, 6)
            setHasFixedSize(true)
        }
        root.addView(grid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // запрос из intent (глобальный поиск TV кладёт в QUERY)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val data = intent.getStringExtra(android.app.SearchManager.EXTRA_DATA_KEY)
        when (intent.action) {
            com.astraplay.tv.tv.SearchProvider.IntentKeys.OPEN_ENTRY -> {
                data?.let { d -> openEntryJson(d) }
                return
            }
            com.astraplay.tv.tv.SearchProvider.IntentKeys.OPEN_TMDB -> {
                data?.let { d ->
                    val p = d.split("|")
                    if (p.size >= 3) {
                        Nav.openDetails(this, com.astraplay.tv.data.tmdb.TmdbItem(
                            id = p[1].toIntOrNull() ?: return,
                            mediaType = p[0], title = p.getOrElse(2) { "" },
                            overview = "", posterPath = null, backdropPath = null,
                            rating = 0.0, year = ""))
                    }
                }
                return
            }
            com.astraplay.tv.tv.SearchProvider.IntentKeys.OPEN_DATA -> {
                Nav.openUserData(this, false)
                return
            }
        }
        val q = intent.getStringExtra(android.app.SearchManager.QUERY)
            ?: intent.getStringExtra("query") ?: ""
        if (q.isBlank() && query.isBlank()) askQuery() else if (q.isNotBlank()) {
            query = q
            runSearch(q)
        }
    }

    /** Разбор JSON-записи из подсказки глобального поиска. */
    private fun openEntryJson(json: String) {
        runCatching {
            val o = org.json.JSONObject(json)
            val e = com.astraplay.tv.data.db.MediaEntry(
                kind = o.getString("k"), itemId = o.getString("i"),
                title = o.getString("t"), poster = o.optString("p").ifEmpty { null },
                url = o.optString("u").ifEmpty { null },
                subtitle = o.optString("s").ifEmpty { null })
            if (e.url != null) {
                lifecycleScope.launch(Dispatchers.IO) { ServiceLocator.store.addHistory(e) }
                Nav.play(this, e)
            } else {
                Nav.openUserData(this, false)
            }
        }.onFailure { finish() }
    }

    private fun askQuery() {
        Dialogs.input(this, getString(R.string.search), getString(R.string.search_hint), query) { q ->
            query = q
            runSearch(q)
        }
    }

    private fun runSearch(q: String) {
        status.text = getString(R.string.loading)
        val tmdb = ServiceLocator.tmdb
        val store = ServiceLocator.store

        lifecycleScope.launch {
            val moviesDeferred = if (tmdb.available) {
                withContext(Dispatchers.IO) { tmdb.search(q).getOrDefault(emptyList()) }
            } else emptyList()

            val channels = withContext(Dispatchers.IO) {
                // по всем плейлистам
                val lower = q.lowercase(Locale.getDefault())
                store.playlists().flatMap { pl -> store.channels(pl.id) }
                    .filter { it.name.lowercase(Locale.getDefault()).contains(lower) }
                    .take(12)
                    .map { ch ->
                        MediaEntry(kind = Kinds.CHANNEL, itemId = ch.key, title = ch.name,
                            poster = ch.logo, url = ch.url, subtitle = ch.group)
                    }
            }

            val results = SearchResult(
                channels = channels,
                movies = moviesDeferred.take(24),
                history = withContext(Dispatchers.IO) { store.history().filter { it.title.lowercase(Locale.getDefault()).contains(q.lowercase(Locale.getDefault())) }.take(12) },
            )

            if (results.channels.isEmpty() && results.movies.isEmpty() && results.history.isEmpty()) {
                status.text = getString(R.string.no_results)
                grid.adapter = null
                return@launch
            }
            status.text = ""
            grid.adapter = SearchAdapter(results)
        }
    }

    private data class SearchResult(
        val channels: List<MediaEntry>,
        val movies: List<TmdbItem>,
        val history: List<MediaEntry>,
    )

    private inner class SearchAdapter(private val res: SearchResult) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val TYPE_CHANNEL = 0
        private val TYPE_MOVIE = 1
        private val TYPE_HIST = 2

        private fun flatCount() = res.channels.size + res.movies.size + res.history.size

        override fun getItemViewType(position: Int): Int = when {
            position < res.channels.size -> TYPE_CHANNEL
            position < res.channels.size + res.movies.size -> TYPE_MOVIE
            else -> TYPE_HIST
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val card = PosterCard(this@SearchActivity)
            card.applyMode(if (viewType == TYPE_MOVIE) PosterCard.MODE_POSTER else PosterCard.MODE_WIDE)
            card.layoutParams = GridLayoutManager.LayoutParams(
                (if (viewType == TYPE_MOVIE) 132 else 220).dp(this@SearchActivity),
                (if (viewType == TYPE_MOVIE) 240 else 170).dp(this@SearchActivity)
            ).apply {
                rightMargin = 12.dp(this@SearchActivity)
                bottomMargin = 12.dp(this@SearchActivity)
            }
            return VH(card)
        }

        override fun getItemCount() = flatCount()

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val card = (holder as VH).card
            when (getItemViewType(position)) {
                TYPE_CHANNEL -> {
                    val e = res.channels[position]
                    card.loadPoster(e.poster)
                    card.label.text = e.title
                    card.showBadge("TV")
                    card.setOnClickListener {
                        lifecycleScope.launch(Dispatchers.IO) { ServiceLocator.store.addHistory(e) }
                        Nav.play(this@SearchActivity, e)
                    }
                }
                TYPE_MOVIE -> {
                    val item = res.movies[position - res.channels.size]
                    card.loadPoster(ServiceLocator.tmdb.posterUrl(item.posterPath))
                    card.label.text = item.title
                    card.showBadge(if (item.rating > 0) String.format(Locale.US, "%.1f", item.rating) else null)
                    card.setOnClickListener { Nav.openDetails(this@SearchActivity, item) }
                }
                else -> {
                    val e = res.history[position - res.channels.size - res.movies.size]
                    card.loadPoster(e.poster)
                    card.label.text = e.title
                    card.showBadge(null)
                    card.setOnClickListener { Nav.openUserData(this@SearchActivity, true) }
                }
            }
        }
    }

    private class VH(val card: PosterCard) : RecyclerView.ViewHolder(card)
}
