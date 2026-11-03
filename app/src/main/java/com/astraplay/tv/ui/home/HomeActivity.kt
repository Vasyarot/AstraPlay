package com.astraplay.tv.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.astraplay.tv.R
import com.astraplay.tv.core.ServiceLocator
import com.astraplay.tv.data.db.Kinds
import com.astraplay.tv.data.db.MediaEntry
import com.astraplay.tv.data.iptv.Playlist
import com.astraplay.tv.data.tmdb.CatalogSection
import com.astraplay.tv.ui.common.PosterCard
import com.astraplay.tv.ui.common.dp
import com.astraplay.tv.ui.common.formatDuration
import com.astraplay.tv.ui.common.gone
import com.astraplay.tv.ui.nav.Nav
import com.astraplay.tv.ui.theme.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Главный экран: быстрые действия + вертикальный список секций-рядов.
 * Все секции грузятся параллельно; каждая не блокирует остальные.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout
    private lateinit var sectionsHost: LinearLayout

    /** Счётчик загрузок: устаревшие корутины не должны добавлять ряды. */
    private var loadGen = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8.dp(this@HomeActivity), 0, 24.dp(this@HomeActivity))
        }
        scroll.addView(content)
        val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val sidebarScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val sidebar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10.dp(this@HomeActivity), 20.dp(this@HomeActivity), 10.dp(this@HomeActivity), 12.dp(this@HomeActivity))
            setBackgroundColor(ThemeManager.panelColor(this@HomeActivity))
        }
        sidebar.addView(TextView(this).apply {
            text = "ASTRA / PLAY"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ThemeManager.accentColor(this@HomeActivity))
            setPadding(10.dp(this@HomeActivity), 0, 0, 20.dp(this@HomeActivity))
        })
        var firstDestination: TextView? = null
        fun destination(label: String, icon: Int, action: () -> Unit) {
            val button = com.astraplay.tv.ui.common.tvButton(this, label, icon, action)
            sidebar.addView(button)
            if (firstDestination == null) firstDestination = button
        }
        destination("Фильмы и сериалы", R.drawable.ic_movie) { Nav.openSources(this) }
        destination("Поиск", R.drawable.ic_search) { Nav.openSearch(this) }
        destination("Телеканалы", R.drawable.ic_tv) { Nav.openIptv(this) }
        destination("Избранное", R.drawable.ic_star) { Nav.openUserData(this) }
        destination("История", R.drawable.ic_history) { Nav.openUserData(this, true) }
        destination("Торренты", R.drawable.ic_magnet) { startActivity(Intent(this, com.astraplay.tv.ui.torrent.TorrentPlayerActivity::class.java)) }
        destination("Мои видео", R.drawable.ic_folder) { Nav.openLocal(this) }
        destination("Сетевые папки", R.drawable.ic_network) { Nav.openSmb(this) }
        destination("Настройки", R.drawable.ic_settings) { Nav.openSettings(this) }
        sidebarScroll.addView(sidebar)
        root.addView(sidebarScroll, LinearLayout.LayoutParams(196.dp(this), ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        setContentView(root)

        buildHeader()
        sectionsHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(sectionsHost)
        if (savedInstanceState == null) firstDestination?.post { firstDestination?.requestFocus() }
    }

    override fun onResume() {
        super.onResume()
        // данные могли измениться (настройки/избранное) — пересобираем динамику
        loadSections()
    }

    // ---------- шапка ----------

    private fun buildHeader() {
        val c = this
        val header = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dp(c), 8.dp(c), 16.dp(c), 8.dp(c))
        }
        val title = TextView(c).apply {
            text = getString(R.string.app_name)
            textSize = 30f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ThemeManager.accentColor(c))
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(c).apply {
            textSize = 14f
            setTextColor(ThemeManager.colorOf(c, android.R.attr.textColorSecondary))
            text = SimpleDateFormat("EEE, d MMM HH:mm", Locale("ru")).format(Date())
        })
        content.addView(header)

        val hero = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = com.astraplay.tv.ui.common.roundedBg(c, ThemeManager.panelColor(c), 20f)
            setPadding(24.dp(c), 22.dp(c), 24.dp(c), 22.dp(c))
        }
        hero.addView(TextView(c).apply {
            text = "Ваш вечер. Ваш выбор."
            textSize = 30f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ThemeManager.colorOf(c, android.R.attr.textColorPrimary))
        })
        hero.addView(TextView(c).apply {
            text = "Фильмы, сериалы и любимые телеканалы в одном месте"
            textSize = 14f
            setTextColor(ThemeManager.colorOf(c, android.R.attr.textColorSecondary))
            setPadding(0, 8.dp(c), 0, 16.dp(c))
        })
        hero.addView(com.astraplay.tv.ui.common.tvButton(c, "Выбрать источник", R.drawable.ic_play) { Nav.openSources(c) })
        content.addView(hero, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(20.dp(c), 8.dp(c), 20.dp(c), 16.dp(c))
        })
        val quick = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        quick.addView(com.astraplay.tv.ui.common.tvButton(c, "Каталог TMDB", R.drawable.ic_movie) { Nav.openCatalog(c) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        quick.addView(com.astraplay.tv.ui.common.tvButton(c, "Свободное кино", R.drawable.ic_play) { Nav.openTorrent(c) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(quick, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(16.dp(c), 0, 16.dp(c), 12.dp(c)) })
    }

    // ---------- секции ----------

    private fun <T : Any> addRow(title: CharSequence): com.astraplay.tv.ui.common.MediaRow<T> {
        val row = com.astraplay.tv.ui.common.MediaRow<T>(this)
        row.setTitle(title)
        row.gone() // появится, когда данные загружены (setData управляет видимостью)
        sectionsHost.addView(row, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return row
    }

    private fun loadSections() {
        val store = ServiceLocator.store
        // пересборка при возврате на экран: чистим старые секции
        sectionsHost.removeAllViews()
        val gen = ++loadGen
        fun stale() = gen != loadGen

        // 1. Продолжить просмотр
        lifecycleScope.launch {
            val positions = withContext(Dispatchers.IO) { store.positions().filter { it.positionMs > 5_000 && (it.durationMs <= 0 || it.positionMs < it.durationMs - 30_000) } }
            if (stale()) return@launch
            val row = addRow<MediaEntry>(getString(R.string.section_continue))
            if (positions.isEmpty()) {
                row.gone()
            } else {
                row.setData(positions, PosterCard.MODE_WIDE,
                    binder = { card, e ->
                        card.loadPoster(e.poster)
                        card.label.text = e.title
                        card.showBadge(
                            if (e.durationMs > 0) formatDuration(e.durationMs - e.positionMs) + " ост." else null
                        )
                    },
                    click = { e -> Nav.play(this@HomeActivity, e) },
                    longClick = { e ->
                        com.astraplay.tv.ui.common.Dialogs.confirm(
                            this@HomeActivity,
                            e.title,
                            getString(R.string.remove_from_fav) + "?"
                        ) {
                            ServiceLocator.store.deletePosition(e.itemId)
                            loadSections()
                        }
                        true
                    })
            }
        }

        // 2. Избранное
        lifecycleScope.launch {
            val favs = withContext(Dispatchers.IO) { store.favorites() }
            if (stale()) return@launch
            val row = addRow<MediaEntry>(getString(R.string.section_favorites))
            if (favs.isEmpty()) { row.gone(); return@launch }
            row.setData(favs.take(30), PosterCard.MODE_POSTER,
                binder = { card, e ->
                    card.loadPoster(e.poster)
                    card.label.text = e.title
                },
                click = { e -> openEntry(this@HomeActivity, e) })
        }

        // 3. IPTV-плейлисты
        lifecycleScope.launch {
            val lists = withContext(Dispatchers.IO) { ServiceLocator.store.playlists() }
            if (stale()) return@launch
            val row = addRow<PlaylistRef>(getString(R.string.section_iptv))
            if (lists.isEmpty()) { row.gone(); return@launch }
            row.setData(lists.map { PlaylistRef(it) }, PosterCard.MODE_SQUARE,
                binder = { card, ref ->
                    card.label.text = ref.p.name
                    card.showBadge(null)
                    card.image.setColorFilter(ThemeManager.accentColor(this@HomeActivity))
                    card.image.setImageResource(R.drawable.ic_tv)
                    card.image.scaleType = android.widget.ImageView.ScaleType.CENTER
                    card.image.setPadding(24.dp(this@HomeActivity), 24.dp(this@HomeActivity), 24.dp(this@HomeActivity), 12.dp(this@HomeActivity))
                },
                click = { ref -> Nav.openIptv(this@HomeActivity, ref.p.id) })
        }

        // 4. Каталог (тренды) — только если есть ключ TMDB
        if (ServiceLocator.tmdb.available) {
            lifecycleScope.launch {
                val items = withContext(Dispatchers.IO) {
                    ServiceLocator.tmdb.section(CatalogSection.TRENDING).getOrNull().orEmpty()
                }
                if (stale()) return@launch
                val row = addRow<TmdbRef>(getString(CatalogSection.TRENDING.titleRes))
                if (items.isEmpty()) { row.gone(); return@launch }
                row.setData(items.map { TmdbRef(it) }, PosterCard.MODE_POSTER,
                    binder = { card, ref ->
                        card.loadPoster(ServiceLocator.tmdb.posterUrl(ref.item.posterPath))
                        card.label.text = ref.item.title
                        card.showBadge(if (ref.item.rating > 0) String.format(Locale.US, "%.1f", ref.item.rating) else null)
                    },
                    click = { ref -> Nav.openDetails(this@HomeActivity, ref.item) })
            }
        }

    }

    /** Куда открывать запись из избранного — по типу. */
    private fun openEntry(home: HomeActivity, e: MediaEntry) {
        when (e.kind) {
            Kinds.MOVIE, Kinds.SERIES -> {
                if (e.itemId.startsWith("tmdb_")) {
                    val parts = e.itemId.split("_")
                    val id = parts.getOrNull(2)?.toIntOrNull()
                    if (parts.size >= 3 && id != null) {
                        Nav.openDetails(home, com.astraplay.tv.data.tmdb.TmdbItem(
                            id = id, mediaType = parts[1], title = e.title,
                            overview = "", posterPath = e.poster, backdropPath = null,
                            rating = 0.0, year = e.subtitle ?: ""))
                        return
                    }
                }
                Nav.play(home, e)
            }
            else -> Nav.play(home, e)
        }
    }

    // ---------- данные-обёртки ----------

    data class PlaylistRef(val p: Playlist)
    data class TmdbRef(val item: com.astraplay.tv.data.tmdb.TmdbItem)
}
