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
        setContentView(scroll)

        buildHeader()
        sectionsHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(sectionsHost)
        loadSections()
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
            text = SimpleDateFormat("EEE, d MMM HH:mm", Locale.getDefault()).format(Date())
        })
        content.addView(header)

        // Быстрые действия
        val actions = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        content.addView(actions)

        fun action(icon: Int, label: String, click: () -> Unit) {
            val card = PosterCard(c)
            card.applyMode(PosterCard.MODE_SQUARE)
            card.layoutParams = LinearLayout.LayoutParams(card.layoutParams.width, card.layoutParams.height).apply {
                leftMargin = 16.dp(c)
            }
            card.setIcon(icon, ThemeManager.accentColor(c))
            card.label.text = label
            card.setOnClickListener { click() }
            actions.addView(card)
        }

        action(R.drawable.ic_search, getString(R.string.search)) { Nav.openSearch(c) }
        action(R.drawable.ic_tv, getString(R.string.section_iptv)) { Nav.openIptv(c) }
        action(R.drawable.ic_movie, getString(R.string.section_catalog)) { Nav.openCatalog(c) }
        action(R.drawable.ic_magnet, getString(R.string.section_torrents)) { Nav.openTorrent(c) }
        action(R.drawable.ic_folder, getString(R.string.section_local)) { Nav.openLocal(c) }
        action(R.drawable.ic_star, getString(R.string.favorites)) { Nav.openUserData(c, false) }
        action(R.drawable.ic_history, getString(R.string.history)) { Nav.openUserData(c, true) }
        action(R.drawable.ic_settings, getString(R.string.settings)) { Nav.openSettings(c) }
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
            val positions = withContext(Dispatchers.IO) { store.positions() }
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

        // 5. Приложения
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { loadTvApps() }
            if (stale()) return@launch
            val row = addRow<AppRef>(getString(R.string.section_apps))
            if (apps.isEmpty()) { row.gone(); return@launch }
            row.setData(apps.map { AppRef(it) }, PosterCard.MODE_SQUARE,
                binder = { card, ref ->
                    card.label.text = ref.info.loadLabel(packageManager).toString()
                    card.showBadge(null)
                    card.image.setImageDrawable(ref.info.loadIcon(packageManager))
                    card.image.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                    card.image.setPadding(20.dp(this@HomeActivity), 20.dp(this@HomeActivity), 20.dp(this@HomeActivity), 8.dp(this@HomeActivity))
                    card.image.colorFilter = null
                },
                click = { ref ->
                    val intent = packageManager.getLaunchIntentForPackage(ref.info.packageName)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                    }
                })
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
    data class AppRef(val info: android.content.pm.ActivityInfo)

    private fun loadTvApps(): List<android.content.pm.ActivityInfo> {
        val pm = packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val resolve = pm.queryIntentActivities(main, 0)
        val out = ArrayList<android.content.pm.ActivityInfo>(resolve.size)
        resolve.forEach {
            if (it.activityInfo.packageName != packageName) out += it.activityInfo
        }
        if (out.isEmpty()) {
            // fallback: обычные лаунчеры
            val touch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(touch, 0).forEach {
                if (it.activityInfo.packageName != packageName) out += it.activityInfo
            }
        }
        return out.sortedBy { it.loadLabel(pm).toString().lowercase(Locale.getDefault()) }
    }
}
