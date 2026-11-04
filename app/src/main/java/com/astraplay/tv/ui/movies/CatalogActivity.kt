package com.astraplay.tv.ui.movies

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.astraplay.tv.R
import com.astraplay.tv.core.LastScreen
import com.astraplay.tv.core.ServiceLocator
import com.astraplay.tv.data.tmdb.CatalogSection
import com.astraplay.tv.data.tmdb.TmdbItem
import com.astraplay.tv.ui.common.PosterCard
import com.astraplay.tv.ui.common.dp
import com.astraplay.tv.ui.common.makeTitle
import com.astraplay.tv.ui.common.roundedBg
import com.astraplay.tv.ui.common.withAlpha
import com.astraplay.tv.ui.nav.Nav
import com.astraplay.tv.ui.theme.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Каталог фильмов/сериалов TMDB: вкладки-разделы + сетка постеров
 * с догрузкой страниц при прокрутке.
 */
class CatalogActivity : AppCompatActivity() {

    private lateinit var tabs: RecyclerView
    private lateinit var grid: RecyclerView
    private lateinit var status: TextView

    private var section: CatalogSection = CatalogSection.TRENDING
    private var page = 1
    private var items = mutableListOf<TmdbItem>()
    private var loading = false
    private var hasMore = true
    private var loadJob: Job? = null
    /** Поколение запроса: ответ старого раздела не должен попасть в новый. */
    private var generation = 0

    private val tmdb get() = ServiceLocator.tmdb

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(root)

        root.addView(makeTitle(this, getString(R.string.section_catalog)))

        tabs = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@CatalogActivity, LinearLayoutManager.HORIZONTAL, false)
            setPadding(16.dp(this@CatalogActivity), 4.dp(this@CatalogActivity), 16.dp(this@CatalogActivity), 4.dp(this@CatalogActivity))
            clipToPadding = false
        }
        root.addView(tabs, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        status = TextView(this).apply {
            textSize = 14f
            setPadding(16.dp(this@CatalogActivity), 8.dp(this@CatalogActivity), 16.dp(this@CatalogActivity), 8.dp(this@CatalogActivity))
        }
        root.addView(status)

        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@CatalogActivity, 6)
            setHasFixedSize(true)
            setItemViewCacheSize(10)
            itemAnimator = null // анимации вставки на слабых приставках дают рывки прокрутки
        }
        root.addView(grid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as GridLayoutManager
                if (!loading && hasMore &&
                    lm.findLastVisibleItemPosition() >= items.size - 12) {
                    loadPage()
                }
            }
        })

        tabs.adapter = TabsAdapter(CatalogSection.values().toList()) { sel ->
            if (sel != section) {
                section = sel
                reload()
            }
        }

        if (!tmdb.available) {
            status.text = getString(R.string.tmdb_key_desc)
        } else {
            reload()
        }
    }

    override fun onResume() {
        super.onResume()
        // процесс могли убить в фоне — запоминаем каталог для восстановления ([[LastScreen]])
        LastScreen.save(this, CatalogActivity::class.java)
    }

    private fun reload() {
        generation++
        loadJob?.cancel()
        items.clear()
        page = 1
        hasMore = true
        loading = false
        grid.adapter?.notifyDataSetChanged()
        loadPage()
    }

    private fun loadPage() {
        if (loading || !tmdb.available) return
        loading = true
        status.text = getString(R.string.loading)
        val gen = generation
        loadJob = lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) { tmdb.section(section, page) }
            if (gen != generation) return@launch // раздел сменился, пока грузился ответ
            loading = false
            res.fold(
                onSuccess = { list ->
                    if (gen != generation) return@launch
                    status.text = ""
                    items.addAll(list)
                    hasMore = list.size >= 20
                    page++
                    if (grid.adapter == null) {
                        grid.adapter = CatalogAdapter(items)
                    } else {
                        grid.adapter?.notifyItemRangeInserted(items.size - list.size, list.size)
                    }
                },
                onFailure = { if (gen == generation) status.text = getString(R.string.network_error) }
            )
        }
    }

    // ---------- адаптеры ----------

    private class TabVH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    private inner class TabsAdapter(
        private val sections: List<CatalogSection>,
        private val onSelect: (CatalogSection) -> Unit,
    ) : RecyclerView.Adapter<TabVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabVH {
            val tv = TextView(this@CatalogActivity).apply {
                textSize = 14f
                isFocusable = true
                isClickable = true
                setPadding(14.dp(this@CatalogActivity), 8.dp(this@CatalogActivity), 14.dp(this@CatalogActivity), 8.dp(this@CatalogActivity))
                background = roundedBg(this@CatalogActivity, ThemeManager.cardColor(this@CatalogActivity), 8f)
            }
            tv.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = 8.dp(this@CatalogActivity) }
            return TabVH(tv)
        }
        override fun getItemCount() = sections.size
        override fun onBindViewHolder(holder: TabVH, position: Int) {
            val s = sections[position]
            holder.tv.text = getString(s.titleRes)
            val selected = s == section
            holder.tv.setTextColor(
                if (selected) ThemeManager.accentColor(this@CatalogActivity)
                else ThemeManager.colorOf(this@CatalogActivity, android.R.attr.textColorPrimary))
            holder.tv.setOnClickListener { onSelect(s) }
        }
    }

    private class CardVH(val card: PosterCard) : RecyclerView.ViewHolder(card)

    private inner class CatalogAdapter(private val list: List<TmdbItem>) :
        RecyclerView.Adapter<CardVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardVH {
            val card = PosterCard(this@CatalogActivity)
            card.applyMode(PosterCard.MODE_POSTER)
            card.layoutParams = GridLayoutManager.LayoutParams(132.dp(this@CatalogActivity), 240.dp(this@CatalogActivity)).apply {
                rightMargin = 12.dp(this@CatalogActivity)
                bottomMargin = 12.dp(this@CatalogActivity)
            }
            return CardVH(card)
        }
        override fun getItemCount() = list.size
        override fun onBindViewHolder(holder: CardVH, position: Int) {
            val item = list[position]
            holder.card.loadPoster(tmdb.posterUrl(item.posterPath))
            holder.card.label.text = item.title
            holder.card.showBadge(
                if (item.rating > 0) String.format(Locale.US, "%.1f", item.rating) else null
            )
            holder.card.setOnClickListener { Nav.openDetails(this@CatalogActivity, item) }
            holder.card.setOnLongClickListener {
                val entry = com.astraplay.tv.data.db.MediaEntry(
                    kind = if (item.mediaType == "tv") com.astraplay.tv.data.db.Kinds.SERIES
                    else com.astraplay.tv.data.db.Kinds.MOVIE,
                    itemId = item.key, title = item.title,
                    poster = tmdb.posterUrl(item.posterPath),
                )
                lifecycleScope.launch(Dispatchers.IO) { ServiceLocator.store.addFavorite(entry) }
                true
            }
        }
    }
}
