package com.astraplay.tv.ui.movies

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
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
import androidx.recyclerview.widget.ConcatAdapter
import coil.load
import com.astraplay.tv.R
import com.astraplay.tv.core.LastScreen
import com.astraplay.tv.core.ServiceLocator
import com.astraplay.tv.data.db.Kinds
import com.astraplay.tv.data.db.MediaEntry
import com.astraplay.tv.data.sources.SourceDetails
import com.astraplay.tv.data.sources.SourceDetailsLoader
import com.astraplay.tv.data.sources.SourceDetailsParsers
import com.astraplay.tv.data.sources.SourceEpisode
import com.astraplay.tv.data.sources.SourcePlayKind
import com.astraplay.tv.data.sources.SourceSeason
import com.astraplay.tv.ui.common.dp
import com.astraplay.tv.ui.common.gone
import com.astraplay.tv.ui.common.roundedBg
import com.astraplay.tv.ui.common.tvButton
import com.astraplay.tv.ui.common.visible
import com.astraplay.tv.ui.common.withAlpha
import com.astraplay.tv.ui.nav.Nav
import com.astraplay.tv.ui.theme.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import com.astraplay.tv.player.VideoItem

/**
 * Нативная карточка фильма/сериала из выбранного источника: данные конкретной
 * страницы разбираются в фоне и показываются стандартными компонентами.
 * WebView здесь не создаётся; JavaScript и реклама сайта не выполняются.
 * Официальный плеер источника загружается только после явного «Смотреть».
 */
class SourceDetailsActivity : AppCompatActivity() {

    private var url: String = ""
    private var sourceId: String = ""
    private var fallbackTitle: String = ""
    private var fallbackPoster: String = ""

    private var details: SourceDetails? = null
    private var loadJob: Job? = null
    private var seasonsJob: Job? = null
    private var currentSeason: SourceSeason? = null
    private var seasonAdapter: SeasonsAdapter? = null
    private var seasonGeneration = 0
    private var selectedSeasonNumber = 0
    private var playbackJob: Job? = null
    private lateinit var headerContent: LinearLayout
    private var favorite = false
    private var watched = false
    private var userPositions: Map<String, MediaEntry> = emptyMap()
    private var userWatched: Set<String> = emptySet()
    private var playButton: TextView? = null

    private lateinit var status: TextView
    private lateinit var header: LinearLayout
    private lateinit var actions: LinearLayout
    private lateinit var poster: ImageView
    private lateinit var titleView: TextView
    private lateinit var metaView: TextView
    private lateinit var descriptionView: TextView
    private lateinit var extraView: TextView
    private lateinit var noteView: TextView
    private lateinit var seasonsTitle: TextView
    private lateinit var seasonsList: RecyclerView

    private var favBtn: TextView? = null
    private var watchedBtn: TextView? = null

    private val store get() = ServiceLocator.store
    // процессный загрузчик: кэш карточек переживает пересоздание активности (сворачивание/разворачивание)
    private val loader: SourceDetailsLoader get() = ServiceLocator.sourceDetailsLoader

    private val itemId: String get() = "web_${url.hashCode()}"
    private val entry: MediaEntry
        get() = MediaEntry(
            kind = Kinds.WEB, itemId = itemId, title = details?.title ?: fallbackTitle,
            poster = details?.poster ?: fallbackPoster.ifEmpty { null }, url = url)

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        url = intent.getStringExtra("url") ?: ""
        fallbackTitle = intent.getStringExtra("title") ?: ""
        fallbackPoster = intent.getStringExtra("poster") ?: ""
        if (!com.astraplay.tv.data.sources.OnlineSources.validWebUrl(url)) { finish(); return }
        sourceId = SourceDetailsParsers.detect(url) ?: "generic"
        selectedSeasonNumber = savedInstanceState?.getInt("season") ?: 0
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(this@SourceDetailsActivity), 12.dp(this@SourceDetailsActivity), 20.dp(this@SourceDetailsActivity), 24.dp(this@SourceDetailsActivity))
        }
        headerContent = root
        headerContent.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(18.dp(context), 18.dp(context), 18.dp(context), 18.dp(context))
            background = roundedBg(context, ThemeManager.panelColor(context), 16f)
        }
        root.addView(header)

        poster = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(150.dp(this@SourceDetailsActivity), 225.dp(this@SourceDetailsActivity))
                .apply { rightMargin = 16.dp(this@SourceDetailsActivity) }
        }
        poster.setImageResource(R.drawable.ic_movie)
        poster.setColorFilter(ThemeManager.accentColor(this))
        poster.scaleType = ImageView.ScaleType.CENTER_INSIDE
        header.addView(poster)

        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
        }
        titleView = TextView(this).apply {
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ThemeManager.colorOf(this@SourceDetailsActivity, android.R.attr.textColorPrimary))
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }
        metaView = TextView(this).apply {
            textSize = 14f
            setTextColor(ThemeManager.colorOf(this@SourceDetailsActivity, android.R.attr.textColorSecondary))
            setPadding(0, 8.dp(this@SourceDetailsActivity), 0, 0)
        }
        titleColumn.addView(titleView)
        titleColumn.addView(metaView)
        header.addView(titleColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        status = TextView(this).apply {
            textSize = 14f
            setTextColor(ThemeManager.colorOf(this@SourceDetailsActivity, android.R.attr.textColorSecondary))
            setPadding(0, 16.dp(this@SourceDetailsActivity), 0, 0)
        }
        root.addView(status)

        actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 14.dp(this@SourceDetailsActivity), 0, 0)
        }
        root.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(actions) })

        descriptionView = TextView(this).apply {
            textSize = 15f
            setTextColor(ThemeManager.colorOf(this@SourceDetailsActivity, android.R.attr.textColorPrimary))
            setPadding(0, 14.dp(this@SourceDetailsActivity), 0, 0)
            maxLines = 5
            ellipsize = TextUtils.TruncateAt.END
            isFocusable = true
            isClickable = true
            contentDescription = getString(R.string.source_card_expand)
            setOnClickListener { maxLines = if (maxLines == 5) 100 else 5 }
            setOnFocusChangeListener { v, focused ->
                v.background = if (focused) roundedBg(context, ThemeManager.cardColor(context), 8f, ThemeManager.strokeColor(context)) else null
            }
            gone()
        }
        root.addView(descriptionView)

        extraView = TextView(this).apply {
            textSize = 13f
            setTextColor(ThemeManager.colorOf(this@SourceDetailsActivity, android.R.attr.textColorSecondary))
            setPadding(0, 10.dp(this@SourceDetailsActivity), 0, 0)
            gone()
        }
        root.addView(extraView)

        noteView = TextView(this).apply {
            textSize = 13f
            setTextColor(ThemeManager.colorOf(this@SourceDetailsActivity, android.R.attr.textColorSecondary))
            setPadding(0, 12.dp(this@SourceDetailsActivity), 0, 0)
            gone()
        }
        root.addView(noteView)

        seasonsTitle = TextView(this).apply {
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ThemeManager.colorOf(this@SourceDetailsActivity, android.R.attr.textColorPrimary))
            setPadding(0, 18.dp(this@SourceDetailsActivity), 0, 6.dp(this@SourceDetailsActivity))
            gone()
        }
        root.addView(seasonsTitle)
        seasonsList = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@SourceDetailsActivity)
            itemAnimator = null
            setItemViewCacheSize(3)
            adapter = HeaderAdapter()
        }
        setContentView(seasonsList)

        // мгновенный отклик: название и постер уже из ленты, остальное — из сети
        titleView.text = fallbackTitle
        if (fallbackPoster.isNotEmpty()) loadPoster(fallbackPoster)
        load(refresh = false)
    }

    override fun onResume() {
        super.onResume()
        // процесс могли убить в фоне — запоминаем карточку для восстановления ([[LastScreen]])
        LastScreen.save(this, SourceDetailsActivity::class.java,
            org.json.JSONObject().put("url", url)
                .put("title", fallbackTitle)
                .put("poster", fallbackPoster))
        if (details != null) lifecycleScope.launch { refreshUserState() }
    }

    private fun load(refresh: Boolean) {
        seasonsJob?.cancel()
        seasonGeneration++
        playbackJob?.cancel()
        if (sourceId.isEmpty()) {
            status.text = getString(R.string.source_details_unsupported)
            return
        }
        status.text = getString(R.string.loading)
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val res = try { Result.success(withContext(Dispatchers.IO) { loader.load(url, sourceId, refresh) }) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { Result.failure(e) }
            res.fold(onSuccess = { d ->
                details = d
                render(d)
                refreshUserState()
            }, onFailure = { error ->
                status.text = when (error) {
                    is com.astraplay.tv.data.sources.SourceDetailsException -> error.message
                    else -> getString(R.string.source_details_network_error)
                }
                showRetryButton()
            })
        }
    }

    private fun showRetryButton() {
        actions.removeAllViews()
        actions.addView(tvButton(this, getString(R.string.retry)) { load(refresh = true) })
        actions.addView(tvButton(this, getString(R.string.open_source_page)) {
            startActivity(Intent(this, SourceWebActivity::class.java)
                .putExtra("url", url).putExtra("title", fallbackTitle))
        })
    }

    private fun render(d: SourceDetails) {
        status.text = ""
        titleView.text = d.title
        d.poster?.let(::loadPoster)

        val meta = buildList {
            add(getString(if (d.isSeries) R.string.source_card_series else R.string.source_card_movie))
            d.year?.let { add(it) }
            if (d.durationMin > 0) add("${d.durationMin} мин")
            d.country?.let { add(it) }
            if (d.genres.isNotEmpty()) add(d.genres.take(3).joinToString(", "))
            d.ageRating?.let { add(it) }
            d.rating?.let { add(it) }
        }.joinToString("  •  ")
        metaView.text = listOf(d.sourceName, meta).filter { it.isNotEmpty() }.joinToString("  •  ")

        d.description?.let { descriptionView.text = it.take(8000); descriptionView.visible() } ?: descriptionView.gone()

        val extra = buildList {
            d.originalTitle?.let { add("${getString(R.string.original_title)}: $it") }
            d.director?.let { add("${getString(R.string.director)}: $it") }
            if (d.actors.isNotEmpty()) add("${getString(R.string.cast)}: ${d.actors.joinToString(", ")}")
        }.joinToString("\n")
        if (extra.isNotEmpty()) { extraView.text = extra; extraView.visible() } else extraView.gone()

        d.playbackNote?.let { noteView.text = it; noteView.visible() } ?: noteView.gone()

        renderActions(d)
        renderSeasons(d)
    }

    private fun renderActions(d: SourceDetails) {
        actions.removeAllViews()
        if (d.play != null) {
            playButton = tvButton(this, d.play.label, R.drawable.ic_play) { startPlayback(d.play) }
            actions.addView(playButton)
            if (d.play.kind != SourcePlayKind.DIRECT) actions.addView(tvButton(this, getString(R.string.video_source_player)) {
                startPlayback(d.play, preferEmbed = true)
            })
        } else if (d.seasons.isNotEmpty()) {
            actions.addView(tvButton(this, getString(R.string.episodes), R.drawable.ic_play) {
                val position = (d.seasons.size + 1).coerceAtMost((seasonsList.adapter?.itemCount ?: 1) - 1)
                seasonsList.scrollToPosition(position)
                seasonsList.post { seasonsList.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus() }
            })
        }
        favBtn = tvButton(this, if (currentFav()) getString(R.string.remove_from_fav) else getString(R.string.add_to_fav)) { toggleFav() }
        actions.addView(favBtn)
        watchedBtn = tvButton(this, if (watched) getString(R.string.unmark_watched) else getString(R.string.mark_watched)) { toggleWatched() }
        actions.addView(watchedBtn)
        actions.addView(tvButton(this, getString(R.string.refresh)) { load(refresh = true) })
        // явное действие пользователя — страница источника
        actions.addView(tvButton(this, getString(R.string.open_source_page)) {
            startActivity(Intent(this, SourceWebActivity::class.java)
                .putExtra("url", url).putExtra("title", details?.title ?: fallbackTitle))
        })
        for (index in 0 until actions.childCount) actions.getChildAt(index).layoutParams =
            LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 8.dp(this@SourceDetailsActivity) }
        // фокус сразу на первой доступной кнопке
        actions.post { if (!seasonsList.hasFocus()) actions.getChildAt(0)?.requestFocus() }
    }

    private fun loadPoster(address: String) {
        poster.colorFilter = null
        poster.scaleType = ImageView.ScaleType.CENTER_CROP
        poster.load(address) { size(300, 450); allowRgb565(true); crossfade(false) }
    }

    private fun renderSeasons(d: SourceDetails) {
        if (!d.isSeries || d.seasons.isEmpty()) {
            seasonsTitle.gone(); seasonsList.adapter = HeaderAdapter(); seasonAdapter = null
            currentSeason = null
            return
        }
        seasonsTitle.text = getString(R.string.seasons)
        seasonsTitle.visible()
        seasonAdapter = SeasonsAdapter(d)
        seasonsList.adapter = ConcatAdapter(HeaderAdapter(), seasonAdapter!!)
        selectSeason(d, d.seasons.firstOrNull { it.number == selectedSeasonNumber } ?: d.seasons.first())
    }

    private fun selectSeason(d: SourceDetails, season: SourceSeason) {
        seasonsJob?.cancel()
        val request = ++seasonGeneration
        selectedSeasonNumber = season.number
        currentSeason = season
        seasonAdapter?.notifyDataSetChanged()
        if (season.episodes.isNotEmpty()) {
            seasonsTitle.text = getString(R.string.source_card_episode_count, season.episodes.size)
            return
        }
        // большие списки серий грузим по требованию, для выбранного сезона
        seasonsJob?.cancel()
        seasonsJob = lifecycleScope.launch {
            seasonsTitle.text = getString(R.string.loading)
            val res = try { Result.success(withContext(Dispatchers.IO) { loader.loadSeason(d, season) }) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { Result.failure(e) }
            res.fold(onSuccess = { loaded ->
                if (request != seasonGeneration) return@fold
                currentSeason = loaded
                // обновляем и модель карточки: повторный выбор сезона не бьёт в сеть
                details = details?.let { current -> current.copy(seasons = current.seasons.map { if (it.number == loaded.number) loaded else it }) }
                seasonsTitle.text = if (loaded.episodes.isEmpty()) getString(R.string.source_card_no_episodes)
                    else getString(R.string.source_card_episode_count, loaded.episodes.size)
                seasonAdapter?.notifyDataSetChanged()
            }, onFailure = {
                if (request != seasonGeneration) return@fold
                seasonsTitle.text = getString(R.string.source_card_season_error)
                Toast.makeText(this@SourceDetailsActivity,
                    getString(R.string.source_details_network_error), Toast.LENGTH_SHORT).show()
            })
        }
    }

    private fun startPlayback(play: com.astraplay.tv.data.sources.SourcePlayOption, allowEmbed: Boolean = true, preferEmbed: Boolean = false) {
        launchQueue(listOf(VideoItem(itemId, details?.title ?: fallbackTitle, play.url, Kinds.WEB,
            details?.sourceName, details?.poster, play.kind, url, play.referer ?: details?.url)), 0, "$url|movie", allowEmbed, preferEmbed)
    }

    private fun episodeItem(ep: SourceEpisode, seasonNumber: Int): VideoItem? = ep.url?.let { address ->
        VideoItem("${itemId}_s${seasonNumber}_e${ep.number}_${ep.title.hashCode()}",
            "${details?.title ?: fallbackTitle} · ${ep.number}. ${ep.title}", address, Kinds.WEB,
            getString(R.string.source_card_season_episode, seasonNumber, ep.number), details?.poster,
            ep.playKind, url, details?.url)
    }

    private fun playEpisode(ep: SourceEpisode, allowEmbed: Boolean = true, preferEmbed: Boolean = false) {
        val season = currentSeason ?: return
        val clicked = episodeItem(ep, season.number)
        if (clicked == null) { Toast.makeText(this, R.string.source_details_no_native_play, Toast.LENGTH_LONG).show(); return }
        val items = season.episodes.mapNotNull { episodeItem(it, season.number) }
        launchQueue(items, items.indexOfFirst { it.itemId == clicked.itemId }, "$url|${season.number}", allowEmbed, preferEmbed)
    }

    private fun launchQueue(items: List<VideoItem>, index: Int, owner: String, allowEmbed: Boolean = true, preferEmbed: Boolean = false) {
        if (index !in items.indices || playbackJob?.isActive == true) return
        playbackJob = lifecycleScope.launch {
            try {
                val key = withContext(Dispatchers.IO) { ServiceLocator.videoQueues.write(owner, items) }
                Nav.playVideoQueue(this@SourceDetailsActivity, key, index, items[index], allowEmbed, preferEmbed)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Toast.makeText(this@SourceDetailsActivity, R.string.source_details_network_error, Toast.LENGTH_LONG).show() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("season", selectedSeasonNumber)
        super.onSaveInstanceState(outState)
    }

    private class HeaderVH(view: android.view.View) : RecyclerView.ViewHolder(view)
    private inner class HeaderAdapter : RecyclerView.Adapter<HeaderVH>() {
        override fun getItemCount() = 1
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = HeaderVH(headerContent)
        override fun onBindViewHolder(holder: HeaderVH, position: Int) = Unit
    }

    private fun currentFav(): Boolean = favorite

    private suspend fun refreshUserState() {
        val state = withContext(Dispatchers.IO) {
            Triple(store.isFavorite(Kinds.WEB, itemId), store.positions().associateBy { it.itemId }, store.watched().map { it.itemId }.toSet())
        }
        favorite = state.first; userPositions = state.second; userWatched = state.third; watched = itemId in userWatched
        favBtn?.text = getString(if (favorite) R.string.remove_from_fav else R.string.add_to_fav)
        watchedBtn?.text = getString(if (watched) R.string.unmark_watched else R.string.mark_watched)
        val saved = userPositions[itemId]?.positionMs?.takeIf { it > 5000 }
        playButton?.text = if (saved != null) getString(R.string.resume_from, com.astraplay.tv.ui.common.formatDuration(saved))
            else details?.play?.label ?: getString(R.string.source_card_watch)
        seasonAdapter?.notifyDataSetChanged()
    }

    private fun toggleFav() {
        lifecycleScope.launch {
            val snapshot = entry
            withContext(Dispatchers.IO) {
                if (store.isFavorite(Kinds.WEB, itemId)) store.removeFavorite(Kinds.WEB, itemId) else store.addFavorite(snapshot)
            }
            refreshUserState()
        }
    }

    private fun toggleWatched() {
        lifecycleScope.launch {
            val next = !watched
            withContext(Dispatchers.IO) { store.setWatched(itemId, Kinds.WEB, details?.title ?: fallbackTitle, next) }
            refreshUserState()
        }
    }

    // ---------- список: сезоны + серии выбранного сезона ----------

    private inner class SeasonsAdapter(val d: SourceDetails) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val season get() = currentSeason

        override fun getItemCount(): Int = d.seasons.size + (season?.episodes?.size ?: 0)

        override fun getItemViewType(position: Int): Int =
            if (position < d.seasons.size) TYPE_SEASON else TYPE_EPISODE

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val row = makeRow()
            return if (viewType == TYPE_SEASON) SeasonVH(row) else EpisodeVH(row).apply {
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                title.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                sourceButton = tvButton(this@SourceDetailsActivity, getString(R.string.video_source_player)) {}
                row.addView(sourceButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is SeasonVH) {
                val s = details?.seasons?.getOrNull(position) ?: d.seasons[position]
                val active = currentSeason?.number == s.number
                holder.title.text = s.name
                holder.title.setTextColor(
                    if (active) ThemeManager.accentColor(this@SourceDetailsActivity)
                    else ThemeManager.colorOf(this@SourceDetailsActivity, android.R.attr.textColorPrimary))
                holder.row.setOnClickListener { selectSeason(d, s) }
            } else if (holder is EpisodeVH) {
                val ep = season?.episodes?.get(position - d.seasons.size) ?: return
                holder.title.text = "${ep.number}. ${ep.title}" +
                    (if (ep.durationMin > 0) "  •  ${ep.durationMin} мин" else "")
                val id = episodeItem(ep, currentSeason?.number ?: 0)?.itemId
                if (id in userWatched) holder.title.append("  ✓")
                else userPositions[id]?.positionMs?.takeIf { it > 5000 }?.let {
                    holder.title.append("  ·  ${getString(R.string.resume_from, com.astraplay.tv.ui.common.formatDuration(it))}")
                }
                holder.row.setOnClickListener {
                    playEpisode(ep)
                }
                holder.sourceButton.visibility = if (ep.url != null && ep.playKind != SourcePlayKind.DIRECT) android.view.View.VISIBLE else android.view.View.GONE
                holder.sourceButton.setOnClickListener { playEpisode(ep, preferEmbed = true) }
            }
        }
    }

    private fun makeRow(): LinearLayout {
        val c = this
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(16.dp(c), 10.dp(c), 16.dp(c), 10.dp(c))
            background = roundedBg(c, Color.TRANSPARENT, 10f)
            setOnFocusChangeListener { v, f ->
                (v as LinearLayout).background = roundedBg(c,
                    if (f) withAlpha(ThemeManager.accentColor(c), 40) else Color.TRANSPARENT,
                    10f, if (f) ThemeManager.strokeColor(c) else null, 2f)
            }
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(TextView(c).apply {
                textSize = 14f
                setTextColor(ThemeManager.colorOf(c, android.R.attr.textColorPrimary))
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
        }
    }

    private class SeasonVH(val row: LinearLayout) : RecyclerView.ViewHolder(row) {
        val title: TextView = row.getChildAt(0) as TextView
    }

    private class EpisodeVH(val row: LinearLayout) : RecyclerView.ViewHolder(row) {
        val title: TextView = row.getChildAt(0) as TextView
        lateinit var sourceButton: TextView
    }

    private companion object {
        const val TYPE_SEASON = 0
        const val TYPE_EPISODE = 1
    }
}
