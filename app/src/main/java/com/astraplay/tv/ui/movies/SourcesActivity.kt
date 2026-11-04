package com.astraplay.tv.ui.movies

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.astraplay.tv.R
import com.astraplay.tv.core.LastScreen
import com.astraplay.tv.core.ServiceLocator
import com.astraplay.tv.data.sources.*
import com.astraplay.tv.ui.common.*
import com.astraplay.tv.ui.theme.ThemeManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import org.jsoup.Jsoup

class SourcesActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private lateinit var sources: OnlineSources
    private var job: Job? = null
    private var selected: OnlineSource? = null
    private var mediaType = "movies"

    // ---------- Экран «источники слева + сетка справа» ----------

    private var rowsScreen = false
    private var leftScroll: ScrollView? = null
    private var leftPanel: LinearLayout? = null
    private var bannerList: List<Pair<OnlineSource, TextView>> = emptyList()
    private var statusView: TextView? = null
    private var grid: RecyclerView? = null
    private var gridJob: Job? = null
    /** Сетку ждёт фокус (первый заход/возврат с пустой сеткой). */
    private var pendingGridFocus = false
    /** Карточка, на которую возвращаться из панели сайтов. */
    private var returnCardPos = 0

    private data class SectionSpec(val label: String, val moviesPath: String?, val seriesPath: String?)

    /** Разделы ленты; первый = «Новинки» (по умолчанию у всех). */
    private fun sectionsFor(id: String): List<SectionSpec> = when (id) {
        "zona" -> listOf(
            SectionSpec("Новинки", "/movies/", "/tvseries/"),
            SectionSpec("По рейтингу", "/movies/filter/sort-rating", "/tvseries/filter/sort-rating"),
        )
        else -> listOf(SectionSpec("Новинки", null, null))
    }

    private fun sectionIndex(id: String): Int =
        getSharedPreferences("source_sections", MODE_PRIVATE).getInt("section_$id", 0)

    private fun sectionPath(id: String): String? {
        val sections = sectionsFor(id)
        val s = sections.getOrElse(sectionIndex(id)) { sections[0] }
        return if (mediaType == "movies") s.moviesPath else s.seriesPath
    }

    /**
     * Кэш лент: переживает пересоздание активити. Ключ включает адрес источника —
     * смена адреса не должна показывать данные старого зеркала. [failed] —
     * отрицательный кэш с ограниченным сроком, чтобы не долбить мёртвые сайты.
     */
    private object RowCache {
        val entries = HashMap<String, List<OnlineTitle>>()
        val failed = HashMap<String, Long>()
        const val FAILED_TTL_MS = 5 * 60_000L

        fun isFailed(key: String): Boolean {
            val at = failed[key] ?: return false
            if (System.currentTimeMillis() - at > FAILED_TTL_MS) {
                failed.remove(key); return false
            }
            return true
        }

        fun dropSource(sourceId: String) {
            entries.keys.removeAll { it.startsWith("$sourceId|") }
            failed.keys.removeAll { it.startsWith("$sourceId|") }
        }
    }

    /** Дисковый кэш лент: строка показывается мгновенно, сеть только обновляет её. */
    private val rowDisk by lazy { com.astraplay.tv.core.DiskJsonCache(java.io.File(cacheDir, "rows")) }

    /** Свежая лента с диска: сеть не трогаем вовсе. Устаревшая — показана, обновляется тихо. */
    private val ROW_DISK_FRESH_MS = 30 * 60_000L

    private fun readRowDisk(key: String): Pair<Long, List<OnlineTitle>>? {
        val raw = rowDisk.read(key)
        if (raw == null) android.util.Log.d("RowCache", "disk MISS $key")
        val json = runCatching { org.json.JSONObject(raw ?: return null) }.getOrNull() ?: return null
        val at = json.optLong("at", 0L)
        val arr = json.optJSONArray("items") ?: return null
        val items = mutableListOf<OnlineTitle>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("t")
            val url = o.optString("u")
            if (title.isBlank() || url.isBlank()) continue
            items += OnlineTitle(title, url, o.optString("p").ifEmpty { null })
        }
        if (items.isEmpty()) return null
        return at to items
    }

    private fun writeRowDisk(key: String, entries: List<OnlineTitle>) {
        val arr = org.json.JSONArray()
        entries.take(60).forEach { e ->
            arr.put(org.json.JSONObject().put("t", e.title).put("u", e.url).put("p", e.poster ?: ""))
        }
        rowDisk.write(key, org.json.JSONObject()
            .put("k", key)
            .put("at", System.currentTimeMillis())
            .put("items", arr)
            .toString())
        android.util.Log.d("RowCache", "disk WRITE $key (${entries.size} карточек)")
    }

    /** Точечная чистка: удаляем с диска только ленты этого источника. */
    private fun dropRowCache(sourceId: String) {
        RowCache.dropSource(sourceId)
        runCatching {
            java.io.File(cacheDir, "rows").listFiles()?.forEach { f ->
                runCatching {
                    if (org.json.JSONObject(f.readText()).optString("k").startsWith("$sourceId|")) f.delete()
                }
            }
        }
    }

    /** Не более 3 одновременных загрузок строк: не забиваем Wi-Fi и не подвешиваем экран. */
    private val rowLimiter = kotlinx.coroutines.sync.Semaphore(3)

    /** Клиент строк с короткими таймаутами: мёртвый хост занимает слот максимум ~15 с. */
    private val rowClient by lazy {
        webClient.newBuilder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /** Ключ кэша: источник + тип + раздел + адрес (смена адреса меняет данные). */
    private fun cacheKey(id: String, address: String?, section: Int) = "$id|$mediaType|$section|${address ?: "none"}"

    private val webClient by lazy {
        val cookieManager = android.webkit.CookieManager.getInstance().apply { setAcceptCookie(true) }
        ServiceLocator.http.client.newBuilder().cookieJar(SourceSession(cookieManager)).build()
    }
    private val browserAgent by lazy {
        runCatching { android.webkit.WebSettings.getDefaultUserAgent(this) }
            .getOrDefault(com.astraplay.tv.data.net.Http.USER_AGENT)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        sources = OnlineSources(this)
        mediaType = savedInstanceState?.getString("mediaType") ?: mediaType
        // восстановление после пересоздания (сворачивание/разворачивание): тот же
        // источник, лента берётся из RowCache/диска — без повторной загрузки
        val restoredId = savedInstanceState?.getString("selectedSource")
        showSources(restoredId?.let { id -> sources.all().firstOrNull { it.id == id } })
    }

    override fun onResume() {
        super.onResume()
        // процесс могли убить в фоне — запоминаем выбор источника ([[LastScreen]])
        LastScreen.save(this, SourcesActivity::class.java)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        selected?.let { outState.putString("selectedSource", it.id) }
        outState.putString("mediaType", mediaType)
    }

    /** Источники для текущего типа: Seasonvar — только фильмы. */
    private fun visibleSources(): List<OnlineSource> =
        sources.all().filter { if (mediaType == "series") !com.astraplay.tv.data.sources.RutubeSource.isChannelSource(it.id) else it.id != "seasonvar" }

    /**
     * Двухпанельный экран: сайты — колонкой слева (как в главном меню),
     * справа — сетка карточек выбранного сайта. Фокус по умолчанию — в сетке.
     */
    private fun showSources(preselect: OnlineSource? = null) {
        job?.cancel()
        gridJob?.cancel()
        selected = null
        rowsScreen = true
        returnCardPos = 0
        root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        setContentView(root)

        // левая панель: баннеры сайтов в стиле главного экрана
        leftPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10.dp(this@SourcesActivity), 16.dp(this@SourcesActivity), 8.dp(this@SourcesActivity), 12.dp(this@SourcesActivity))
            setBackgroundColor(ThemeManager.panelColor(this@SourcesActivity))
        }
        val banners = mutableListOf<Pair<OnlineSource, TextView>>()
        for (s in visibleSources()) {
            val banner = TextView(this).apply {
                text = s.name
                gravity = android.view.Gravity.CENTER
                maxLines = 2
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(android.graphics.Color.WHITE)
                isFocusable = true
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 84.dp(this@SourcesActivity)
                ).apply { bottomMargin = 8.dp(this@SourcesActivity) }
                drawBanner(this, s, focused = false)
                setOnClickListener {
                    if (s.address == null) edit(s)
                    else { selectSource(s); focusGrid() }
                }
                setOnLongClickListener { showRowMenu(s); true }
                setOnFocusChangeListener { v, f ->
                    drawBanner(v as TextView, s, f)
                    v.animate().scaleX(if (f) 1.04f else 1f).scaleY(if (f) 1.04f else 1f)
                        .setDuration(120).start()
                }
            }
            leftPanel!!.addView(banner)
            banners += s to banner
        }
        bannerList = banners
        leftScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(leftPanel) }
        root.addView(leftScroll, LinearLayout.LayoutParams(170.dp(this@SourcesActivity), ViewGroup.LayoutParams.MATCH_PARENT))

        // правая колонка: заголовок, вкладки, статус, сетка
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        right.addView(makeTitle(this, "Фильмы и сериалы"))
        right.addView(TextView(this).apply {
            text = "Карточка — смотреть • удержание ОК на сайте слева: раздел, адрес, вход"
            textSize = 13f
            setTextColor(ThemeManager.colorOf(this@SourcesActivity, android.R.attr.textColorSecondary))
            setPadding(16.dp(this@SourcesActivity), 0, 16.dp(this@SourcesActivity), 8.dp(this@SourcesActivity))
        })
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabs.addView(tvButton(this, if (mediaType == "movies") "● Фильмы" else "Фильмы") { mediaType = "movies"; showSources() })
        tabs.addView(tvButton(this, if (mediaType == "series") "● Сериалы" else "Сериалы") { mediaType = "series"; showSources() })
        tabs.addView(tvButton(this, "Торренты", R.drawable.ic_magnet) {
            startActivity(Intent(this, com.astraplay.tv.ui.torrent.TorrentPlayerActivity::class.java))
        })
        right.addView(tabs)
        statusView = TextView(this).apply {
            textSize = 13f
            setTextColor(ThemeManager.colorOf(this@SourcesActivity, android.R.attr.textColorSecondary))
            setPadding(16.dp(this@SourcesActivity), 6.dp(this@SourcesActivity), 16.dp(this@SourcesActivity), 6.dp(this@SourcesActivity))
        }
        right.addView(statusView)
        grid = makeTitlesGrid()
        right.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        // выбранный сайт: последний открытый, иначе первый в списке
        val lastId = getSharedPreferences("source_sections", MODE_PRIVATE).getString("last_source", null)
        val initial = preselect
            ?: sources.all().firstOrNull { it.id == lastId }?.takeIf { last -> visibleSources().any { it.id == lastId } }
            ?: visibleSources().firstOrNull()
        pendingGridFocus = true
        if (initial != null) selectSource(initial)
    }

    /** Рисовка баннера сайта: выбранный — насыщенный, в фокусе — рамка и увеличение. */
    private fun drawBanner(tv: TextView, s: OnlineSource, focused: Boolean) {
        val selectedNow = selected?.id == s.id
        val alpha = when {
            focused -> 255
            selectedNow -> 220
            else -> 120
        }
        tv.background = roundedBg(this, withAlpha(brandColor(s.id), alpha), 12f,
            if (focused) android.graphics.Color.WHITE else null, 2f)
    }

    /** Выбор сайта: подсветка баннера и загрузка его ленты в сетку. */
    private fun selectSource(source: OnlineSource, force: Boolean = false) {
        selected = source
        getSharedPreferences("source_sections", MODE_PRIVATE)
            .edit().putString("last_source", source.id).apply()
        bannerList.forEach { (s, tv) -> drawBanner(tv, s, tv.hasFocus()) }
        statusView?.text = "Загрузка…"
        grid?.adapter = null
        loadGrid(source, force)
    }

    /** Лента выбранного сайта: память → диск → сеть; сила — только по «Обновить». */
    private fun loadGrid(source: OnlineSource, force: Boolean) {
        gridJob?.cancel()
        if (source.address == null) {
            statusView?.text = "Укажите адрес сайта: удержание ОК на сайте слева"
            return
        }
        val sections = sectionsFor(source.id)
        val sIdx = sectionIndex(source.id).coerceAtMost(sections.size - 1)
        val key = cacheKey(source.id, source.address, sIdx)

        val cached = RowCache.entries[key]
        if (cached != null && !force) {
            showGridItems(cached, sections[sIdx].label)
            return
        }
        if (!force && RowCache.isFailed(key)) {
            statusView?.text = "Недоступен • удержание ОК на сайте — «Обновить ленту»"
            return
        }
        gridJob = lifecycleScope.launch {
            var shownFromDisk = false
            if (cached == null) {
                // 1) диск: свежая лента показывается мгновенно, без похода в сеть
                val disk = withContext(Dispatchers.IO) { readRowDisk(key) }
                if (disk != null && isActive) {
                    RowCache.entries[key] = disk.second
                    val fresh = System.currentTimeMillis() - disk.first < ROW_DISK_FRESH_MS
                    showGridItems(disk.second, sections[sIdx].label)
                    shownFromDisk = true
                    if (fresh && !force) return@launch
                }
            }
            // 2) сеть: первая загрузка, устаревший диск или явное «Обновить»
            rowLimiter.withPermit {
                try {
                    val catalog = SourceCatalogLoader(
                        rowClient, com.astraplay.tv.data.net.Http.USER_AGENT, browserAgent,
                        callTimeoutMs = 8000, readTimeoutMs = 8000)
                        .load(source, mediaType, sectionPath(source.id), totalTimeoutMs = if (com.astraplay.tv.data.sources.RutubeSource.isChannelSource(source.id)) 30000 else 15000)
                    RowCache.entries[key] = catalog.entries
                    RowCache.failed.remove(key)
                    withContext(Dispatchers.IO) { writeRowDisk(key, catalog.entries) }
                    if (isActive) showGridItems(catalog.entries, sections[sIdx].label)
                } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                    // истечение дедлайна — не отмена экрана; отмену экрана пробрасываем
                    if (!isActive) throw error
                    android.util.Log.w("SourceCatalog", "grid ${source.id}: дедлайн загрузки")
                    if (!shownFromDisk) {
                        RowCache.failed[key] = System.currentTimeMillis()
                        statusView?.text = "Недоступен • удержание ОК на сайте — «Обновить ленту»"
                    }
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (!isActive) return@withPermit
                    android.util.Log.w("SourceCatalog", "grid ${source.id}: ${error.javaClass.simpleName}: ${error.message}")
                    if (!shownFromDisk) {
                        RowCache.failed[key] = System.currentTimeMillis()
                        statusView?.text = "Не удалось загрузить: ${explain(error)}"
                    }
                }
            }
        }
    }

    private fun showGridItems(items: List<OnlineTitle>, sectionLabel: String) {
        statusView?.text = "${sectionsFor(selected?.id ?: "").firstOrNull { it.label == sectionLabel }?.label ?: sectionLabel}" +
            " • доступно: ${items.size}"
        grid?.adapter = TitlesAdapter(items)
        if (pendingGridFocus) focusGrid()
    }

    /** Фокус в сетку карточек — по умолчанию курсор живёт здесь. */
    private fun focusGrid(at: Int = returnCardPos) {
        val rv = grid ?: return
        val count = rv.adapter?.itemCount ?: 0
        if (count <= 0) {
            pendingGridFocus = true
            return
        }
        pendingGridFocus = false
        val target = at.coerceIn(0, count - 1)
        rv.scrollToPosition(target)
        val vh = rv.findViewHolderForAdapterPosition(target)?.itemView
        if (vh != null) vh.requestFocus()
        else rv.post { (rv.findViewHolderForAdapterPosition(target)?.itemView ?: rv.getChildAt(0))?.requestFocus() }
    }

    private fun edit(source: OnlineSource) {
        Dialogs.input(this, "Адрес • ${source.name}", "https://…", source.address ?: "") { value ->
            runCatching { sources.setAddress(source.id, value) }.fold(
                onSuccess = {
                    // адрес в ключе кэша лент меняется; сбрасываем и раздел — прежние данные чужого адреса не показываем
                    dropRowCache(source.id)
                    // пересобираем панели (подпись баннера) и открываем этот же сайт
                    showSources(preselect = source)
                },
                onFailure = { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() })
        }
    }

    /** Фирменный цвет баннера сайта-источника. */
    private fun brandColor(id: String): Int = when (id) {
        "lostfilm" -> 0xFFB71C1C.toInt()   // красный LostFilm.TV
        "seasonvar" -> 0xFF2E7D32.toInt()  // зелёный Seasonvar
        "filmix" -> 0xFFEF6C00.toInt()     // оранжевый Filmix
        "hdrezka" -> 0xFF1565C0.toInt()    // синий HDRezka
        "zetflix" -> 0xFFC62828.toInt()    // красный Zetflix
        "zona" -> 0xFF4527A0.toInt()       // фиолетовый Zona
        "eneyida" -> 0xFF00838F.toInt()    // бирюзовый Eneyida
        "anilibria" -> 0xFFAD1457.toInt()  // розовый AniLibria
        "smotrim" -> 0xFF283593.toInt()    // синий Смотрим
        "mosfilm" -> 0xFF8D6E63.toInt()    // золотисто-коричневый Мосфильм
        else -> 0xFF455A64.toInt()         // нейтральный серо-синий
    }

    /** Ручная навигация между панелями: ВЛЕВО из первой колонки сетки — к сайтам,
     *  ВПРАВО с сайта — обратно в сетку; по панели сайтов ВВЕРХ/ВНИЗ с зажимом на краях. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            val focus = currentFocus
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_UP,
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (focus == null) return super.dispatchKeyEvent(event)
                    val idx = bannerList.indexOfFirst { it.second === focus }
                    if (idx < 0) return super.dispatchKeyEvent(event)
                    val target = idx + if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                    if (target in bannerList.indices) bannerList[target].second.requestFocus()
                    return true // край панели: зажимаем, не отдаём системному поиску
                }
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (focus == null) return super.dispatchKeyEvent(event)
                    val rv = grid ?: return super.dispatchKeyEvent(event)
                    if (isInsideView(focus, rv)) {
                        val pos = rv.findContainingViewHolder(focus)?.bindingAdapterPosition ?: RecyclerView.NO_POSITION
                        val span = (rv.layoutManager as? GridLayoutManager)?.spanCount ?: 1
                        if (pos != RecyclerView.NO_POSITION && pos % span == 0) {
                            // запоминаем карточку, чтобы ВПРАВО вернуло на то же место
                            returnCardPos = pos
                            bannerList.firstOrNull { it.first.id == selected?.id }?.second
                                ?.requestFocus()
                            return true
                        }
                    }
                    return super.dispatchKeyEvent(event)
                }
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (focus != null && bannerList.any { it.second === focus }) {
                        focusGrid()
                        return true
                    }
                    return super.dispatchKeyEvent(event)
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** true, если [v] лежит внутри [root] по иерархии родителей. */
    private fun isInsideView(v: View, container: View): Boolean {
        var p: android.view.ViewParent? = v.parent
        while (p != null) {
            if (p === container) return true
            p = p.parent
        }
        return false
    }

    private fun showRowMenu(source: OnlineSource) {
        val sections = sectionsFor(source.id)
        val options = mutableListOf<String>()
        if (sections.size > 1) sections.forEach { options += "Раздел: ${it.label}" }
        options += "Обновить ленту"
        options += "Войти на сайт"
        options += "Изменить адрес"
        options += "Открыть сайт"
        Dialogs.choice(this, source.name, options, 0) { idx ->
            when {
                sections.size > 1 && idx < sections.size -> {
                    getSharedPreferences("source_sections", MODE_PRIVATE)
                        .edit().putInt("section_${source.id}", idx).apply()
                    dropRowCache(source.id)
                    selectSource(source, force = true)
                    focusGrid()
                }
                options[idx] == "Обновить ленту" -> {
                    dropRowCache(source.id)
                    selectSource(source, force = true)
                    focusGrid()
                }
                options[idx] == "Войти на сайт" -> {
                    val home = android.net.Uri.parse(source.address!!)
                    val login = if (source.id == "lostfilm") home.buildUpon().path("/login").clearQuery().fragment(null).build().toString() else source.address!!
                    openPage("Вход • ${source.name}", login)
                }
                options[idx] == "Изменить адрес" -> edit(source)
                else -> source.address?.let { openPage(source.name, it) }
            }
        }
    }


    /** Понятная причина по классу ошибки — видно и на экране, и в logcat. */
    private fun explain(error: Exception): String {
        val m = error.message ?: ""
        return when {
            error is java.net.UnknownHostException ->
                "домен не найден (сайт закрылся или не открывается из вашей сети)"
            m.contains("PROTOCOL_ERROR") || error.javaClass.simpleName.contains("StreamReset") ->
                "сервер обрывает соединение (HTTP/2 PROTOCOL_ERROR) — ограничение на стороне сайта"
            m.contains("timeout", ignoreCase = true) || error is java.net.SocketTimeoutException ->
                "истекло время ожидания"
            m.contains("SSL", ignoreCase = true) ->
                "ошибка TLS-соединения"
            m.contains("HTTP ") -> m.substringAfter("HTTP ")
            else -> error.message ?: "нет связи"
        }
    }
    private fun makeTitlesGrid(): RecyclerView = RecyclerView(this).apply {
        layoutManager = GridLayoutManager(this@SourcesActivity, (resources.configuration.screenWidthDp / 160).coerceIn(2, 7))
        setHasFixedSize(true)
        setItemViewCacheSize(10)
        itemAnimator = null // анимации вставки на слабых приставках дают рывки прокрутки
        setPadding(8.dp(this@SourcesActivity), 4.dp(this@SourcesActivity), 8.dp(this@SourcesActivity), 12.dp(this@SourcesActivity))
        clipToPadding = false
    }

    /** Открытие страницы каталога/сайта во встроенном браузере — для явных действий. */
    private fun openPage(title: String, url: String) {
        job?.cancel()
        lifecycleScope.launch(Dispatchers.IO) { ServiceLocator.store.addHistory(
            com.astraplay.tv.data.db.MediaEntry(kind = com.astraplay.tv.data.db.Kinds.WEB,
                itemId = "web_${url.hashCode()}", title = title, url = url)) }
        startActivity(Intent(this, SourceWebActivity::class.java).putExtra("url", url).putExtra("title", title))
    }

    /**
     * Открытие конкретного фильма/сериала из ленты: поддержанные источники —
     * нативный экран AstraPlay, остальные — как раньше во встроенном браузере.
     */
    private fun openCard(title: String, url: String, poster: String?) {
        job?.cancel()
        lifecycleScope.launch(Dispatchers.IO) { ServiceLocator.store.addHistory(
            com.astraplay.tv.data.db.MediaEntry(kind = com.astraplay.tv.data.db.Kinds.WEB,
                itemId = "web_${url.hashCode()}", title = title, url = url, poster = poster)) }
        startActivity(Intent(this, SourceDetailsActivity::class.java)
            .putExtra("url", url).putExtra("title", title).putExtra("poster", poster ?: ""))
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
            holder.card.setOnClickListener { openCard(entry.title, entry.url, entry.poster) }
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
        // фокус на панели сайтов — возвращаем в сетку, иначе выходим
        val focus = currentFocus
        if (focus != null && bannerList.any { it.second === focus }) {
            focusGrid()
        } else super.onBackPressed()
    }
}
