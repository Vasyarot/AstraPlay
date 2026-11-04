package com.astraplay.tv.ui.movies

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.astraplay.tv.R
import com.astraplay.tv.core.LastScreen
import com.astraplay.tv.core.ServiceLocator
import com.astraplay.tv.data.db.Kinds
import com.astraplay.tv.data.db.MediaEntry
import com.astraplay.tv.data.tmdb.Episode
import com.astraplay.tv.data.tmdb.TmdbDetail
import com.astraplay.tv.ui.common.Dialogs
import com.astraplay.tv.ui.common.dp
import com.astraplay.tv.ui.common.gone
import com.astraplay.tv.ui.common.roundedBg
import com.astraplay.tv.ui.common.visible
import com.astraplay.tv.ui.common.withAlpha
import com.astraplay.tv.ui.nav.Nav
import com.astraplay.tv.ui.theme.ThemeManager
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Детали фильма/сериала: бэкдроп, описание, трейлер, избранное,
 * отметка о просмотре, сезоны/серии, поиск торрентов по названию.
 */
class DetailsActivity : AppCompatActivity() {

    private var itemId: Int = 0
    private var mediaType: String = "movie"
    private var detail: TmdbDetail? = null

    private val tmdb get() = ServiceLocator.tmdb
    private val store get() = ServiceLocator.store

    private lateinit var metaLine: TextView
    private lateinit var overview: TextView
    private lateinit var buttons: LinearLayout
    private lateinit var seasonsList: RecyclerView
    private lateinit var similarTitle: TextView
    private lateinit var similarRow: com.astraplay.tv.ui.common.MediaRow<com.astraplay.tv.data.tmdb.TmdbItem>
    private lateinit var backdrop: ImageView
    private lateinit var titleView: TextView

    private var favBtn: TextView? = null
    private var watchedBtn: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        itemId = intent.getIntExtra("itemId", 0)
        mediaType = intent.getStringExtra("mediaType") ?: "movie"

        val scroll = android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 24.dp(this@DetailsActivity))
        }
        scroll.addView(root)
        setContentView(scroll)

        // Бэкдроп с градиентным переходом в фон и заголовком поверх
        val backdropHost = android.widget.FrameLayout(this)
        backdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.argb(40, 255, 255, 255))
        }
        backdropHost.addView(backdrop, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val scrim = android.view.View(this).apply {
            val bg = ThemeManager.colorOf(this@DetailsActivity, android.R.attr.colorBackground)
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, bg))
        }
        backdropHost.addView(scrim, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 220.dp(this), Gravity.BOTTOM))
        titleView = TextView(this).apply {
            textSize = 28f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ThemeManager.colorOf(this@DetailsActivity, android.R.attr.textColorPrimary))
        }
        backdropHost.addView(titleView, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START
        ).apply { setMargins(20.dp(this@DetailsActivity), 0, 20.dp(this@DetailsActivity), 14.dp(this@DetailsActivity)) })
        root.addView(backdropHost, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 380.dp(this)))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(this@DetailsActivity), 16.dp(this@DetailsActivity), 20.dp(this@DetailsActivity), 0)
        }
        root.addView(body)

        metaLine = TextView(this).apply {
            textSize = 14f
            setTextColor(ThemeManager.colorOf(this@DetailsActivity, android.R.attr.textColorSecondary))
        }
        body.addView(metaLine)

        overview = TextView(this).apply {
            textSize = 15f
            setTextColor(ThemeManager.colorOf(this@DetailsActivity, android.R.attr.textColorPrimary))
            setPadding(0, 10.dp(this@DetailsActivity), 0, 0)
        }
        body.addView(overview)

        buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 14.dp(this@DetailsActivity), 0, 0)
        }
        body.addView(buttons)

        body.addView(TextView(this).apply {
            text = getString(if (mediaType == "tv") R.string.seasons else R.string.details)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 18.dp(this@DetailsActivity), 0, 6.dp(this@DetailsActivity))
            setTextColor(ThemeManager.colorOf(this@DetailsActivity, android.R.attr.textColorPrimary))
        })

        seasonsList = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@DetailsActivity)
        }
        body.addView(seasonsList, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Ряд «Похожее»: заполняется после загрузки детали
        similarTitle = TextView(this).apply {
            text = getString(R.string.similar)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 18.dp(this@DetailsActivity), 0, 6.dp(this@DetailsActivity))
            setTextColor(ThemeManager.colorOf(this@DetailsActivity, android.R.attr.textColorPrimary))
            gone()
        }
        body.addView(similarTitle)
        similarRow = com.astraplay.tv.ui.common.MediaRow(this)
        similarRow.gone()
        body.addView(similarRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        load()
    }

    override fun onResume() {
        super.onResume()
        // процесс могли убить в фоне — запоминаем карточку для восстановления ([[LastScreen]])
        LastScreen.save(this, DetailsActivity::class.java,
            org.json.JSONObject().put("itemId", itemId).put("mediaType", mediaType))
    }

    private fun load() {
        if (!tmdb.available) {
            metaLine.text = getString(R.string.tmdb_key_desc)
            return
        }
        metaLine.text = getString(R.string.loading)
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) { tmdb.detail(itemId, mediaType) }
            res.fold(onSuccess = { d ->
                detail = d
                render(d)
            }, onFailure = {
                metaLine.text = getString(R.string.network_error)
            })
        }
    }

    private fun render(d: TmdbDetail) {
        val item = d.item
        tmdb.backdropUrl(item.backdropPath)?.let { backdrop.load(it) { allowRgb565(true) } }
        titleView.text = item.title

        val genres = d.genres.take(3).joinToString(" • ")
        val runtime = if (d.runtimeMin > 0) " • ${d.runtimeMin} мин" else ""
        metaLine.text = buildString {
            if (item.year.isNotEmpty()) append(item.year)
            if (genres.isNotEmpty()) append(if (isEmpty()) genres else " • $genres")
            append(runtime)
            if (item.rating > 0) append(String.format(Locale.US, " • ★ %.1f", item.rating))
        }
        overview.text = d.item.overview.ifEmpty { getString(R.string.no_results) }

        buttons.removeAllViews()
        btn("Источники просмотра") { Nav.openSources(this) }
        btn(getString(R.string.watch_trailer)) { openTrailer(d) }
        if (mediaType == "tv" && d.seasons.isNotEmpty()) {
            btn(getString(R.string.episodes)) { showSeasonPicker(d) }
        }
        favBtn = btn(favLabel()) { toggleFav(d) }
        watchedBtn = btn(watchedLabel()) { toggleWatched(d) }
        btn(getString(R.string.section_torrents)) { Nav.openTorrent(this, d.item.title) }

        loadSimilar(d)
    }

    /** Официальные рекомендации TMDB: горизонтальный ряд под сезонами. */
    private fun loadSimilar(d: TmdbDetail) {
        lifecycleScope.launch {
            val recs = withContext(Dispatchers.IO) {
                tmdb.recommendations(itemId, mediaType).getOrDefault(emptyList())
            }
            if (recs.isEmpty()) return@launch
            similarTitle.visible()
            similarRow.setData(recs.take(20), com.astraplay.tv.ui.common.PosterCard.MODE_POSTER,
                binder = { card, item ->
                    card.loadPoster(tmdb.posterUrl(item.posterPath))
                    card.label.text = item.title
                    card.showBadge(if (item.rating > 0) String.format(Locale.US, "%.1f", item.rating) else null)
                },
                click = { item -> Nav.openDetails(this@DetailsActivity, item) })
        }
    }

    private fun favLabel(): String =
        if (currentFav()) getString(R.string.remove_from_fav) else getString(R.string.add_to_fav)

    private fun watchedLabel(): String =
        if (store.isWatched(detail?.item?.key ?: ""))
            getString(R.string.unmark_watched) else getString(R.string.mark_watched)

    private fun currentFav(): Boolean =
        detail?.let { store.isFavorite(
            if (mediaType == "tv") Kinds.SERIES else Kinds.MOVIE, it.item.key) } ?: false

    private fun toggleFav(d: TmdbDetail) {
        val kind = if (mediaType == "tv") Kinds.SERIES else Kinds.MOVIE
        if (currentFav()) {
            store.removeFavorite(kind, d.item.key)
            Toast.makeText(this, getString(R.string.removed_from_fav), Toast.LENGTH_SHORT).show()
        } else {
            store.addFavorite(MediaEntry(
                kind = kind, itemId = d.item.key, title = d.item.title,
                poster = tmdb.posterUrl(d.item.posterPath),
                subtitle = d.item.year, url = null))
            Toast.makeText(this, getString(R.string.added_to_fav), Toast.LENGTH_SHORT).show()
        }
        favBtn?.text = favLabel()
    }

    private fun toggleWatched(d: TmdbDetail) {
        val now = store.isWatched(d.item.key)
        store.setWatched(d.item.key, if (mediaType == "tv") Kinds.SERIES else Kinds.MOVIE,
            d.item.title, !now)
        watchedBtn?.text = watchedLabel()
    }

    private fun openTrailer(d: TmdbDetail) {
        val key = d.trailerKey
        if (key.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.no_results), Toast.LENGTH_SHORT).show()
            return
        }
        // официальный IFrame-плеер YouTube внутри приложения
        startActivity(android.content.Intent(this,
            com.astraplay.tv.player.YouTubeActivity::class.java).putExtra("videoId", key))
    }

    private fun showSeasonPicker(d: TmdbDetail) {
        val names = d.seasons.map { it.name }
        Dialogs.choice(this, getString(R.string.seasons), names, 0) { idx ->
            val season = d.seasons[idx]
            Toast.makeText(this, getString(R.string.loading), Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                val res = withContext(Dispatchers.IO) { tmdb.season(itemId, season.number) }
                res.fold(
                    onSuccess = { full ->
                        if (full.episodes.isEmpty()) {
                            Toast.makeText(this@DetailsActivity, getString(R.string.no_results), Toast.LENGTH_SHORT).show()
                        } else {
                            showEpisodes(d, full)
                        }
                    },
                    onFailure = {
                        Toast.makeText(this@DetailsActivity, getString(R.string.network_error), Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }

    private fun showEpisodes(d: TmdbDetail, season: com.astraplay.tv.data.tmdb.Season) {
        val names = season.episodes.map { e ->
            "${e.number}. ${e.name}" + if (e.airDate.isNotEmpty()) "  (${e.airDate})" else ""
        }
        if (names.isEmpty()) return
        Dialogs.choice(this, season.name, names, 0) { idx ->
            val ep: Episode = season.episodes[idx]
            val marker = "S%02dE%02d".format(Locale.US, season.number, ep.number)
            Nav.openTorrent(this, "${d.item.title} $marker")
        }
    }

    // ---------- элементы ----------

    private fun btn(label: String, click: () -> Unit): TextView {
        val c = this
        val tv = TextView(c).apply {
            text = label
            textSize = 14f
            isFocusable = true
            isClickable = true
            setPadding(16.dp(c), 10.dp(c), 16.dp(c), 10.dp(c))
            setTextColor(ThemeManager.colorOf(c, android.R.attr.textColorPrimary))
            background = roundedBg(c, ThemeManager.cardColor(c), 8f,
                ThemeManager.strokeColor(c), 1f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = 10.dp(c) }
            setOnClickListener { click() }
            setOnFocusChangeListener { v, f ->
                (v as TextView).background = roundedBg(
                    c,
                    if (f) withAlpha(ThemeManager.accentColor(c), 50) else ThemeManager.cardColor(c),
                    8f, ThemeManager.strokeColor(c), 1f)
            }
        }
        buttons.addView(tv)
        return tv
    }
}
