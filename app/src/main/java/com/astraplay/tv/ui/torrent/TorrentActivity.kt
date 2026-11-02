package com.astraplay.tv.ui.torrent

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.astraplay.tv.R
import com.astraplay.tv.core.ServiceLocator
import com.astraplay.tv.data.db.Kinds
import com.astraplay.tv.data.db.MediaEntry
import com.astraplay.tv.data.torrent.ArchiveItem
import com.astraplay.tv.ui.common.Dialogs
import com.astraplay.tv.ui.common.dp
import com.astraplay.tv.ui.common.makeTitle
import com.astraplay.tv.ui.common.roundedBg
import com.astraplay.tv.ui.common.withAlpha
import com.astraplay.tv.ui.nav.Nav
import com.astraplay.tv.ui.theme.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Поиск свободных фильмов в Internet Archive (легальный источник,
 * public domain): прямые ссылки для стрима + .torrent для внешних приложений.
 */
class TorrentActivity : AppCompatActivity() {

    private var query: String = ""
    private var results: List<ArchiveItem> = emptyList()

    private lateinit var searchBtn: TextView
    private lateinit var list: RecyclerView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(root)

        root.addView(makeTitle(this, getString(R.string.section_torrents)))

        searchBtn = TextView(this).apply {
            text = getString(R.string.search_hint)
            textSize = 15f
            isFocusable = true
            isClickable = true
            setPadding(16.dp(this@TorrentActivity), 10.dp(this@TorrentActivity), 16.dp(this@TorrentActivity), 10.dp(this@TorrentActivity))
            setTextColor(ThemeManager.colorOf(this@TorrentActivity, android.R.attr.textColorPrimary))
            background = roundedBg(this@TorrentActivity, ThemeManager.cardColor(this@TorrentActivity), 10f,
                ThemeManager.strokeColor(this@TorrentActivity), 1f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(16.dp(this@TorrentActivity), 0, 16.dp(this@TorrentActivity), 4.dp(this@TorrentActivity)) }
            setOnClickListener { askQuery() }
            setOnFocusChangeListener { v, f ->
                (v as TextView).background = roundedBg(this@TorrentActivity,
                    if (f) withAlpha(ThemeManager.accentColor(this@TorrentActivity), 40)
                    else ThemeManager.cardColor(this@TorrentActivity),
                    10f, ThemeManager.strokeColor(this@TorrentActivity), 1f)
            }
        }
        root.addView(searchBtn)

        status = TextView(this).apply {
            textSize = 13f
            setPadding(16.dp(this@TorrentActivity), 8.dp(this@TorrentActivity), 16.dp(this@TorrentActivity), 8.dp(this@TorrentActivity))
            setTextColor(ThemeManager.colorOf(this@TorrentActivity, android.R.attr.textColorSecondary))
            text = getString(R.string.torrent_note)
        }
        root.addView(status)

        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@TorrentActivity)
            setHasFixedSize(true)
        }
        root.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // если пришли из деталей фильма — сразу ищем по названию
        val preset = intent.getStringExtra("query")
        if (!preset.isNullOrBlank()) {
            query = preset
            searchBtn.text = preset
            doSearch()
        }
    }

    private fun askQuery() {
        Dialogs.input(this, getString(R.string.section_torrents),
            getString(R.string.torrent_search_hint), query) { q ->
            query = q
            searchBtn.text = q
            doSearch()
        }
    }

    private fun doSearch() {
        status.text = getString(R.string.loading)
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) { ServiceLocator.archive.search(query) }
            res.fold(onSuccess = { items ->
                results = items
                status.text = if (items.isEmpty()) getString(R.string.torrent_empty) else ""
                list.adapter = ResultsAdapter(items)
            }, onFailure = {
                status.text = getString(R.string.network_error)
            })
        }
    }

    /** Диалог элемента: смотреть / торренты / в избранное. */
    private fun showActions(item: ArchiveItem) {
        status.text = getString(R.string.loading)
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) {
                ServiceLocator.archive.files(item.identifier).getOrDefault(emptyList())
            }
            status.text = ""
            val playable = files.firstOrNull { it.playUrl != null }
            val torrent = files.firstOrNull { it.torrentUrl != null }

            // action: 0 = стрим, 1 = торрент, 2 = избранное
            val options = mutableListOf<Pair<String, Int>>()
            if (playable != null) options += getString(R.string.stream_direct) to 0
            if (torrent != null) options += getString(R.string.open_torrent_app) to 1
            options += getString(R.string.add_to_fav) to 2

            Dialogs.choice(this@TorrentActivity, item.title +
                    (if (item.year.isNotEmpty()) " (${item.year})" else ""),
                options.map { it.first }, 0) { idx ->
                when (options[idx].second) {
                    0 -> {
                        val entry = MediaEntry(
                            kind = Kinds.TORRENT, itemId = item.key, title = item.title,
                            subtitle = item.year, poster = item.posterUrl,
                            url = playable!!.playUrl)
                        lifecycleScope.launch(Dispatchers.IO) { ServiceLocator.store.addHistory(entry) }
                        Nav.play(this@TorrentActivity, entry)
                    }
                    1 -> openTorrent(item, torrent!!.torrentUrl!!)
                    else -> {
                        lifecycleScope.launch(Dispatchers.IO) {
                            ServiceLocator.store.addFavorite(MediaEntry(
                                kind = Kinds.TORRENT, itemId = item.key, title = item.title,
                                subtitle = item.year, poster = item.posterUrl,
                                url = playable?.playUrl ?: torrent?.torrentUrl))
                        }
                        Toast.makeText(this@TorrentActivity,
                            getString(R.string.added_to_fav), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun openTorrent(item: ArchiveItem, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val resolved = packageManager.queryIntentActivities(intent, 0)
        if (resolved.isEmpty()) {
            // запасной путь: magnet-приложения
            val magnet = Intent(Intent.ACTION_VIEW, Uri.parse("magnet:?xt=urn:btih:${item.identifier}&dn=${Uri.encode(item.title)}"))
            if (packageManager.queryIntentActivities(magnet, 0).isEmpty()) {
                Toast.makeText(this, getString(R.string.no_torrent_app), Toast.LENGTH_LONG).show()
                return
            }
            startActivity(magnet)
            return
        }
        startActivity(intent)
    }

    private class ResultVH(
        val card: com.astraplay.tv.ui.common.PosterCard,
        val title: TextView,
        val desc: TextView,
        val row: LinearLayout,
    ) : RecyclerView.ViewHolder(row)

    private inner class ResultsAdapter(private val items: List<ArchiveItem>) :
        RecyclerView.Adapter<ResultVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultVH {
            val c = this@TorrentActivity
            val row = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                isFocusable = true
                isClickable = true
                setPadding(12.dp(c), 8.dp(c), 12.dp(c), 8.dp(c))
                background = roundedBg(c, android.graphics.Color.TRANSPARENT, 10f)
                setOnFocusChangeListener { v, f ->
                    (v as LinearLayout).background = roundedBg(c,
                        if (f) withAlpha(ThemeManager.accentColor(c), 40) else android.graphics.Color.TRANSPARENT,
                        10f, if (f) ThemeManager.strokeColor(c) else null, 2f)
                }
            }
            val card = com.astraplay.tv.ui.common.PosterCard(c).apply {
                applyMode(com.astraplay.tv.ui.common.PosterCard.MODE_POSTER)
                layoutParams = LinearLayout.LayoutParams(96.dp(c), 160.dp(c)).apply {
                    rightMargin = 14.dp(c)
                }
                isFocusable = false
                isClickable = false
            }
            val texts = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
            }
            val title = TextView(c).apply {
                textSize = 16f
                setTextColor(ThemeManager.colorOf(c, android.R.attr.textColorPrimary))
            }
            val desc = TextView(c).apply {
                textSize = 13f
                setTextColor(ThemeManager.colorOf(c, android.R.attr.textColorSecondary))
                maxLines = 3
            }
            texts.addView(title)
            texts.addView(desc)
            row.addView(card)
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            return ResultVH(card, title, desc, row)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: ResultVH, position: Int) {
            val item = items[position]
            holder.card.loadPoster(item.posterUrl)
            holder.title.text = item.title + (if (item.year.isNotEmpty()) "  (${item.year})" else "")
            holder.desc.text = item.description
            holder.row.setOnClickListener { showActions(item) }
        }
    }
}
