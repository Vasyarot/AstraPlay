package com.astraplay.tv.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.astraplay.tv.R
import com.astraplay.tv.core.LastScreen
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
    private lateinit var contentScroll: ScrollView
    private var clockView: TextView? = null
    private var clockRunnable: Runnable? = null
    private lateinit var sidebarScroll: ScrollView

    /** Кнопки левого меню в порядке сверху вниз — для ручной вертикальной навигации. */
    private val sidebarButtons = mutableListOf<View>()

    /** Якоря вертикальной навигации в центральной колонке: кнопка героя + быстрые кнопки. */
    private var heroButton: View? = null
    private val quickButtons = mutableListOf<View>()

    /** Ряды карточек центральной колонки (пересобираются при каждом onResume). */
    private val contentRows = mutableListOf<com.astraplay.tv.ui.common.MediaRow<*>>()

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
        contentScroll = scroll
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8.dp(this@HomeActivity), 0, 24.dp(this@HomeActivity))
        }
        scroll.addView(content)
        val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sidebarScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
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
            sidebarButtons += button
            if (firstDestination == null) firstDestination = button
        }
        destination("Фильмы и сериалы", R.drawable.ic_movie) { Nav.openSources(this) }
        destination("Поиск", R.drawable.ic_search) { Nav.openSearch(this) }
        destination("Телеканалы", R.drawable.ic_tv) { Nav.openIptv(this) }
        destination("Избранное", R.drawable.ic_star) { Nav.openUserData(this) }
        destination("История", R.drawable.ic_history) { Nav.openUserData(this, true) }
        destination("Торренты", R.drawable.ic_magnet) { startActivity(Intent(this, com.astraplay.tv.ui.torrent.TorrentPlayerActivity::class.java)) }
        destination("Файлы", R.drawable.ic_folder) { Nav.openLocal(this) }
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

        // Холодный старт после убийства фонового процесса: если приложение
        // свернули недавно — открываем последний экран поверх главного.
        if (savedInstanceState == null) tryRestoreLastScreen()
    }

    /**
     * Лаунчер приставки при OK на баннере сбрасывает стек задач и доставляет
     * новый intent в живой HomeActivity через onNewIntent — восстановление
     * должно работать и здесь (onCreate при этом не вызывается).
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        tryRestoreLastScreen()
    }

    private fun tryRestoreLastScreen() {
        val route = LastScreen.pending(this) ?: return
        LastScreen.clear(this)
        lifecycleScope.launch {
            // видео: позицию берём из сохранённого места просмотра — маршрут
            // записан при входе в плеер и не знает, докуда досмотрели
            if (route.cls == com.astraplay.tv.player.PlayerActivity::class.java) {
                val itemId = route.args.optString("itemId")
                if (itemId.isNotBlank()) {
                    val saved = withContext(Dispatchers.IO) { ServiceLocator.store.position(itemId) }
                    saved?.positionMs?.takeIf { it > 5_000 }?.let { route.args.put("pos", it) }
                }
            }
            android.widget.Toast.makeText(
                this@HomeActivity, getString(R.string.restore_last_screen), android.widget.Toast.LENGTH_SHORT).show()
            route.open(this@HomeActivity)
        }
    }

    override fun onResume() {
        super.onResume()
        // пользователь на главном — восстанавливать нечего
        LastScreen.clear(this)
        // данные могли измениться (настройки/избранное) — пересобираем динамику
        loadSections()
        startClock()
    }

    // ---------- живые часы ----------

    /** Часы в шапке тикают: статичная дата на долго открытом экране сбивает с толку. */
    private fun startClock() {
        clockRunnable?.let { content.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                clockView?.text = SimpleDateFormat("EEE, d MMM HH:mm", Locale("ru")).format(Date())
                content.postDelayed(this, 30_000L)
            }
        }
        clockRunnable = r
        content.post(r)
    }

    // ---------- навигация с пульта ----------

    /**
     * Ручная вертикальная навигация в левом меню: при зажатой ВВЕРХ/ВНИЗ
     * системный поиск фокуса «срывается» в центральную колонку — и на краях
     * списка тоже (карточка справа «ниже» последнего пункта по его меркам).
     * Пока фокус в меню — ВВЕРХ/ВНИЗ обрабатываем только мы: по списку кнопок,
     * на краях зажимаем фокус. В центральную колонку — только ВПРАВО.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN &&
            (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN ||
                event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP)
        ) {
            val focus = currentFocus
            if (focus != null && isInsideSidebar(focus)) {
                val idx = sidebarButtons.indexOfFirst { it === focus }
                if (idx >= 0) {
                    val target = idx + if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                    if (target in sidebarButtons.indices) sidebarButtons[target].requestFocus()
                    return true // край списка: зажимаем, не отдаём системному поиску фокуса
                }
            }
            // центральная колонка: симметричная защита — зажатая ВВЕРХ не должна
            // улетать в левое меню, ВНИЗ — за последний ряд
            if (focus != null && isInsideContent(focus)) {
                val dir = if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                if (navigateContent(focus, dir)) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isInsideSidebar(v: View): Boolean {
        var p: android.view.ViewParent? = v.parent
        while (p != null) {
            if (p === sidebarScroll) return true
            p = p.parent
        }
        return false
    }

    private fun isInsideContent(v: View): Boolean {
        var p: android.view.ViewParent? = v.parent
        while (p != null) {
            if (p === contentScroll) return true
            p = p.parent
        }
        return false
    }

    /** Якорь вертикальной навигации, где лежит фокус: кнопка или ряд. -1 — вне якорей. */
    private fun contentAnchorIndex(focus: View): Int {
        val buttons = anchorButtons()
        if (focus === heroButton) return 0
        quickButtons.indexOfFirst { it === focus }.takeIf { it >= 0 }?.let { return 1 + it }
        var p: android.view.ViewParent? = focus.parent
        while (p != null) {
            val rowIdx = contentRows.indexOfFirst { it.cardsView() === p }
            if (rowIdx >= 0) return buttons.size + rowIdx
            p = (p as? View)?.parent
        }
        return -1
    }

    private fun anchorButtons(): List<View> {
        val out = mutableListOf<View>()
        heroButton?.let { out += it }
        out += quickButtons
        return out
    }

    /** Вертикальный шаг внутри центральной колонки; true — нажатие обработано. */
    private fun navigateContent(focus: View, dir: Int): Boolean {
        val from = contentAnchorIndex(focus)
        if (from < 0) return false
        // колонка карточки для сохранения позиции при переходе между рядами
        var col = 0
        var p: android.view.ViewParent? = focus.parent
        while (p != null && p !is RecyclerView) p = p.parent
        if (p is RecyclerView) {
            val pos = p.getChildAdapterPosition(focus)
            if (pos != RecyclerView.NO_POSITION) col = pos
        }

        val buttons = anchorButtons()
        val anchors = buttons + contentRows
        var target = from + dir
        // пропускаем пустые (скрытые) ряды
        while (target in anchors.indices && anchors[target] is com.astraplay.tv.ui.common.MediaRow<*> &&
            !(anchors[target] as com.astraplay.tv.ui.common.MediaRow<*>).isShown
        ) target += dir
        if (target !in anchors.indices) return true // край колонки: зажимаем фокус

        when (val a = anchors[target]) {
            is View -> {
                a.requestFocus()
                // герой-блок (лого + цитата) выше кнопки: минимальный скролл
                // показывает только кнопку, и после прокрутки до низа и обратно
                // начало колонки остаётся за экраном — возвращаемся к самому верху
                if (a === heroButton) contentScroll.smoothScrollTo(0, 0)
            }
            else -> {
                val row = a as com.astraplay.tv.ui.common.MediaRow<*>
                val rv = row.cardsView()
                val count = rv.adapter?.itemCount ?: 0
                if (count <= 0) return true
                val cardIdx = col.coerceIn(0, count - 1)
                rv.scrollToPosition(cardIdx)
                rv.post {
                    (rv.findViewHolderForAdapterPosition(cardIdx)?.itemView
                        ?: rv.getChildAt(0))?.requestFocus()
                }
            }
        }
        return true
    }

    override fun onDestroy() {
        clockRunnable?.let { content.removeCallbacks(it) }
        clockRunnable = null
        super.onDestroy()
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
        clockView = TextView(c).apply {
            textSize = 14f
            setTextColor(ThemeManager.colorOf(c, android.R.attr.textColorSecondary))
            text = SimpleDateFormat("EEE, d MMM HH:mm", Locale("ru")).format(Date())
        }
        header.addView(clockView)
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
        heroButton = com.astraplay.tv.ui.common.tvButton(c, "Выбрать источник", R.drawable.ic_play) { Nav.openSources(c) }
        hero.addView(heroButton)
        content.addView(hero, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(20.dp(c), 8.dp(c), 20.dp(c), 16.dp(c))
        })
        val quick = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        quick.addView(com.astraplay.tv.ui.common.tvButton(c, "Каталог TMDB", R.drawable.ic_movie) { Nav.openCatalog(c) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        quick.addView(com.astraplay.tv.ui.common.tvButton(c, "Свободное кино", R.drawable.ic_play) { Nav.openTorrent(c) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        for (i in 0 until quick.childCount) quickButtons += quick.getChildAt(i)
        content.addView(quick, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(16.dp(c), 0, 16.dp(c), 12.dp(c)) })
    }

    // ---------- секции ----------

    private fun <T : Any> addRow(title: CharSequence): com.astraplay.tv.ui.common.MediaRow<T> {
        val row = com.astraplay.tv.ui.common.MediaRow<T>(this)
        row.setTitle(title)
        row.gone() // появится, когда данные загружены (setData управляет видимостью)
        sectionsHost.addView(row, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        contentRows += row
        return row
    }

    private fun loadSections() {
        val store = ServiceLocator.store
        // пересборка при возврате на экран: чистим старые секции
        sectionsHost.removeAllViews()
        contentRows.clear()
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

        // 4. Каталог (тренды) — только если есть ключ TMDB; сетевой запрос не на каждый возврат
        if (ServiceLocator.tmdb.available) {
            lifecycleScope.launch {
                val items = withContext(Dispatchers.IO) {
                    val cached = trendingCache
                    if (cached != null && System.currentTimeMillis() - trendingAt < TRENDING_TTL_MS) {
                        cached
                    } else {
                        ServiceLocator.tmdb.section(CatalogSection.TRENDING).getOrNull().orEmpty().also {
                            if (it.isNotEmpty()) {
                                trendingCache = it
                                trendingAt = System.currentTimeMillis()
                            }
                        }
                    }
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

    companion object {
        /** Тренды TMDB кэшируются процессно: возврат на главный экран не бьёт в сеть. */
        private var trendingCache: List<com.astraplay.tv.data.tmdb.TmdbItem>? = null
        private var trendingAt = 0L
        private const val TRENDING_TTL_MS = 10 * 60_000L
    }
}
