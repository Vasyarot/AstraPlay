package com.astraplay.tv.ui.data

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.astraplay.tv.R
import com.astraplay.tv.core.LastScreen
import com.astraplay.tv.core.ServiceLocator
import com.astraplay.tv.data.db.Kinds
import com.astraplay.tv.data.db.MediaEntry
import com.astraplay.tv.ui.common.Dialogs
import com.astraplay.tv.ui.common.PosterCard
import com.astraplay.tv.ui.common.dp
import com.astraplay.tv.ui.common.makeTitle
import com.astraplay.tv.ui.common.roundedBg
import com.astraplay.tv.ui.common.withAlpha
import com.astraplay.tv.ui.nav.Nav
import com.astraplay.tv.ui.theme.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Избранное и история просмотра: два таба, сетка, удаление длинным нажатием. */
class UserDataActivity : AppCompatActivity() {

    private lateinit var favTab: TextView
    private lateinit var histTab: TextView
    private lateinit var grid: RecyclerView
    private lateinit var status: TextView
    private var showHistory = false

    private val store get() = ServiceLocator.store

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        showHistory = intent.getBooleanExtra("history", false)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(root)

        root.addView(makeTitle(this, getString(R.string.favorites)))

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16.dp(this@UserDataActivity), 0, 16.dp(this@UserDataActivity), 4.dp(this@UserDataActivity))
        }
        favTab = tab(tabs, getString(R.string.favorites)) { showHistory = false; refreshTabs(); load() }
        histTab = tab(tabs, getString(R.string.history)) { showHistory = true; refreshTabs(); load() }
        root.addView(tabs)

        status = TextView(this).apply {
            textSize = 14f
            setPadding(16.dp(this@UserDataActivity), 6.dp(this@UserDataActivity), 16.dp(this@UserDataActivity), 6.dp(this@UserDataActivity))
        }
        root.addView(status)

        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@UserDataActivity, 6)
            setHasFixedSize(true)
            setItemViewCacheSize(8)
        }
        root.addView(grid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        refreshTabs()
        load()
    }

    override fun onResume() {
        super.onResume()
        // процесс могли убить в фоне — запоминаем вкладку для восстановления ([[LastScreen]])
        LastScreen.save(this, UserDataActivity::class.java,
            org.json.JSONObject().put("history", showHistory))
    }

    private fun refreshTabs() {
        val accent = ThemeManager.accentColor(this)
        favTab.setTextColor(if (showHistory) ThemeManager.colorOf(this, android.R.attr.textColorPrimary) else accent)
        histTab.setTextColor(if (showHistory) accent else ThemeManager.colorOf(this, android.R.attr.textColorPrimary))
        if (showHistory) {
            status.text = getString(R.string.clear_history)
            status.isFocusable = true
            status.isClickable = true
            status.setOnClickListener {
                Dialogs.confirm(this, getString(R.string.clear_history),
                    getString(R.string.clear_history_q)) {
                    lifecycleScope.launch(Dispatchers.IO) { store.clearHistory() }
                    load()
                }
            }
        } else {
            // удаление неочевидно с пульта — подсказываем прямо на экране
            status.text = "ОК — открыть • удержание ОК — меню удаления"
            status.isClickable = false
        }
    }

    private fun tab(parent: LinearLayout, label: String, onClick: () -> Unit): TextView {
        val tv = TextView(this).apply {
            text = label
            textSize = 15f
            isFocusable = true
            isClickable = true
            setPadding(14.dp(this@UserDataActivity), 8.dp(this@UserDataActivity), 14.dp(this@UserDataActivity), 8.dp(this@UserDataActivity))
            background = roundedBg(this@UserDataActivity, ThemeManager.cardColor(this@UserDataActivity), 8f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = 8.dp(this@UserDataActivity) }
            setOnClickListener { onClick() }
        }
        parent.addView(tv)
        return tv
    }

    private fun load() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                if (showHistory) store.history() else store.favorites()
            }
            // подсказку про удаление в «Избранном» не затираем — refreshTabs() задаёт её
            status.text = if (!showHistory) {
                if (items.isEmpty()) getString(R.string.empty) else status.text
            } else if (items.isEmpty()) getString(R.string.empty) else ""
            grid.adapter = EntriesAdapter(items)
        }
    }

    /** Открытие записи по типу. */
    private fun open(e: MediaEntry) {
        when {
            e.kind == Kinds.MOVIE || e.kind == Kinds.SERIES -> {
                if (e.itemId.startsWith("tmdb_")) {
                    val parts = e.itemId.split("_")
                    if (parts.size >= 3) {
                        val id = parts[2].toIntOrNull()
                        if (id != null) {
                            lifecycleScope.launch(Dispatchers.IO) { store.addHistory(e) }
                            Nav.openDetails(this,
                                com.astraplay.tv.data.tmdb.TmdbItem(
                                    id = id, mediaType = parts[1], title = e.title,
                                    overview = "", posterPath = e.poster, backdropPath = null,
                                    rating = 0.0, year = e.subtitle ?: ""))
                            return
                        }
                    }
                }
                Nav.play(this, e)
            }
            e.url != null -> {
                lifecycleScope.launch(Dispatchers.IO) { store.addHistory(e) }
                Nav.play(this, e)
            }
            e.kind == Kinds.TORRENT -> Nav.openTorrent(this, e.title)
            else -> Nav.play(this, e)
        }
    }

    private class EntryVH(val card: PosterCard) : RecyclerView.ViewHolder(card)

    private inner class EntriesAdapter(private val items: List<MediaEntry>) :
        RecyclerView.Adapter<EntryVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryVH {
            val card = PosterCard(this@UserDataActivity)
            card.applyMode(PosterCard.MODE_POSTER)
            card.layoutParams = GridLayoutManager.LayoutParams(132.dp(this@UserDataActivity), 240.dp(this@UserDataActivity)).apply {
                rightMargin = 12.dp(this@UserDataActivity)
                bottomMargin = 12.dp(this@UserDataActivity)
            }
            return EntryVH(card)
        }
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: EntryVH, position: Int) {
            val e = items[position]
            holder.card.loadPoster(e.poster)
            holder.card.label.text = e.title
            holder.card.showBadge(
                if (e.kind == Kinds.CHANNEL) "TV"
                else if (e.kind == Kinds.SERIES) "SER"
                else if (e.kind == Kinds.LOCAL) "MP4"
                else if (e.kind == Kinds.TORRENT) "IA"
                else null
            )
            holder.card.setOnClickListener { open(e) }
            holder.card.setOnLongClickListener {
                // меню вместо мгновенного подтверждения: видно, что можно и открыть, и удалить
                val options = listOf(
                    "Открыть",
                    if (showHistory) "Удалить из истории" else "Удалить из избранного",
                    "Отмена")
                Dialogs.choice(this@UserDataActivity, e.title, options, 0) { idx ->
                    when (idx) {
                        0 -> open(e)
                        1 -> {
                            lifecycleScope.launch(Dispatchers.IO) {
                                if (showHistory) {
                                    ServiceLocator.store.removeHistory(e.kind, e.itemId)
                                } else {
                                    store.removeFavorite(e.kind, e.itemId)
                                }
                            }
                            load()
                        }
                    }
                }
                true
            }
        }
    }
}
