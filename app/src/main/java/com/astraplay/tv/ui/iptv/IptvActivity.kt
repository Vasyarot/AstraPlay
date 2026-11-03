package com.astraplay.tv.ui.iptv

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.astraplay.tv.R
import com.astraplay.tv.core.ServiceLocator
import com.astraplay.tv.data.db.Kinds
import com.astraplay.tv.data.db.MediaEntry
import com.astraplay.tv.data.iptv.Channel
import com.astraplay.tv.data.iptv.displayName
import com.astraplay.tv.data.iptv.displayGroup
import com.astraplay.tv.data.iptv.M3uParser
import com.astraplay.tv.ui.common.Dialogs
import com.astraplay.tv.ui.common.PosterCard
import com.astraplay.tv.ui.common.dpToPx
import com.astraplay.tv.ui.common.formatClock
import com.astraplay.tv.ui.common.gone
import com.astraplay.tv.ui.common.makeTitle
import com.astraplay.tv.ui.common.roundedBg
import com.astraplay.tv.ui.common.visible
import com.astraplay.tv.ui.common.withAlpha
import com.astraplay.tv.ui.nav.Nav
import com.astraplay.tv.ui.theme.ThemeManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * IPTV: плейлисты (m3u), группы каналов, телепрограмма (XMLTV EPG).
 */
class IptvActivity : AppCompatActivity() {

    private var playlistId: Long = -1L
    private var channels: List<Channel> = emptyList()
    private var groups: List<String> = emptyList()
    private var selectedGroup: String? = null
    private var sortOrder = 0
    private var searchQuery = ""
    private var probeJob: Job? = null
    private var hideFailed = false
    private val healthPrefs by lazy { getSharedPreferences("iptv_health", MODE_PRIVATE) }
    private fun health(channel: Channel): Int {
        val time = healthPrefs.getLong("time_${channel.url}", 0)
        return if (System.currentTimeMillis() - time > 6 * 3600_000L) 0 else healthPrefs.getInt("state_${channel.url}", 0)
    }
    private val channelSettings by lazy { getSharedPreferences("iptv_display", MODE_PRIVATE) }
    private fun channelTitle(channel: Channel) = channelSettings.getString("name_${channel.url}", null) ?: channel.displayName

    private lateinit var root: LinearLayout
    private lateinit var headerTitle: TextView
    private lateinit var groupsRow: RecyclerView
    private lateinit var channelsGrid: RecyclerView
    private lateinit var status: TextView

    private val store get() = ServiceLocator.store
    private val openPlaylistFile = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            downloadAndSave("Плейлист из файла", uri.toString())
        }
    }

    private fun playlistText(url: String): String = if (url.startsWith("content://")) {
        contentResolver.openInputStream(android.net.Uri.parse(url))?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("Не удалось открыть файл")
    } else ServiceLocator.http.get(url)

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        playlistId = intent.getLongExtra("playlistId", -1L)
        buildUi()
        loadPlaylistsAndChannels()
    }

    // ---------- UI ----------

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8.dp(), 0, 16.dp())
        }
        setContentView(root)

        headerTitle = makeTitle(this, getString(R.string.section_iptv))
        root.addView(headerTitle)

        // Панель действий
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16.dp(), 0, 16.dp(), 4.dp())
        }
        root.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(actions)
        })

        fun action(label: String, click: () -> Unit) {
            actions.addView(actionButton(label, click))
        }
        action("Плейлисты") { showPlaylists() }
        action("Группы") { chooseGroup() }
        action("Сортировка") { chooseSort() }
        action("Доступность") { showAvailabilityMenu() }
        action("VHS") {
            Dialogs.choice(this, "Эфир VHS на сайте", listOf("VHS-ка • PREMIER", "Видеосалон VHS • VK Видео Live"), 0) { index ->
                startActivity(android.content.Intent(this, com.astraplay.tv.ui.movies.SourceWebActivity::class.java)
                    .putExtra("url", if (index == 0) "https://premier.one/tv/vhs" else "https://live.vkvideo.ru/vhstv")
                    .putExtra("title", "VHS"))
            }
        }
        action("Поиск каналов") {
            Dialogs.input(this, "Поиск каналов", "Название канала", searchQuery, allowEmpty = true) {
                searchQuery = it
                showChannels()
                channelsGrid.requestFocus()
            }
        }
        action("Ещё") {
            Dialogs.choice(this, "Управление IPTV", listOf("Добавить плейлист", "Обновить текущий", "Обновить все", "Сетка телепрограммы", "Загрузить телепрограмму", "Удалить текущий"), 0) { index ->
                when (index) {
                    0 -> showAddPlaylist()
                    1 -> updatePlaylist()
                    2 -> updateAllPlaylists()
                    3 -> openEpgGrid()
                    4 -> showEpgSetup()
                    5 -> deleteCurrentPlaylist()
                }
            }
        }

        status = TextView(this).apply {
            textSize = 13f
            setTextColor(ThemeManager.colorOf(this@IptvActivity, android.R.attr.textColorSecondary))
            setPadding(16.dp(), 6.dp(), 16.dp(), 6.dp())
            gone()
        }
        root.addView(status)

        val browser = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(browser, LinearLayout.LayoutParams(-1, 0, 1f))
        // Always-visible vertical groups; D-pad right enters the channel grid.
        groupsRow = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@IptvActivity, LinearLayoutManager.VERTICAL, false)
            setPadding(16.dp(), 4.dp(), 16.dp(), 4.dp())
            clipToPadding = false
        }
        browser.addView(groupsRow, LinearLayout.LayoutParams(180.dp(), -1))

        // Сетка каналов
        channelsGrid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@IptvActivity, ((resources.configuration.screenWidthDp - 180) / 180).coerceIn(2, 6))
            setHasFixedSize(true)
            setItemViewCacheSize(8)
        }
        browser.addView(channelsGrid, LinearLayout.LayoutParams(0, -1, 1f))
    }

    private fun Int.dp() = dpToPx(this@IptvActivity, this)

    private fun actionButton(label: String, click: () -> Unit): TextView {
        val c = this
        return TextView(c).apply {
            text = label
            textSize = 14f
            isFocusable = true
            isClickable = true
            setPadding(14.dp(), 8.dp(), 14.dp(), 8.dp())
            setTextColor(ThemeManager.colorOf(c, android.R.attr.textColorPrimary))
            background = roundedBg(c, ThemeManager.cardColor(c), 8f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8.dp() }
            setOnClickListener { click() }
            setOnFocusChangeListener { v, f ->
                (v as TextView).background = roundedBg(
                    c,
                    if (f) withAlpha(ThemeManager.accentColor(c), 50) else ThemeManager.cardColor(c),
                    8f,
                    if (f) ThemeManager.strokeColor(c) else null,
                    2f)
            }
        }
    }

    // ---------- данные ----------

    private fun loadPlaylistsAndChannels() {
        lifecycleScope.launch {
            val lists = withContext(Dispatchers.IO) { store.playlists() }
            when {
                lists.isEmpty() -> {
                    channels = emptyList()
                    groups = emptyList()
                    selectedGroup = null
                    channelsGrid.adapter = null
                    groupsRow.adapter = null
                    status.text = "Добавьте плейлист: Плейлисты или Ещё → Добавить плейлист"
                    status.visible()
                }
                playlistId !in lists.map { it.id } -> {
                    // приоритет: сохранённый последний → первый в списке
                    val last = ServiceLocator.prefs.lastPlaylistId
                    val chosen = lists.firstOrNull { it.id == last } ?: lists.first()
                    playlistId = chosen.id
                    openChannels()
                }
                else -> openChannels()
            }
        }
    }

    private fun openChannels() {
        ServiceLocator.prefs.lastPlaylistId = playlistId
        selectedGroup = null
        searchQuery = ""
        sortOrder = channelSettings.getInt("sort_$playlistId", 0)
        hideFailed = channelSettings.getBoolean("hide_failed_$playlistId", false)
        lifecycleScope.launch {
            channels = withContext(Dispatchers.IO) { store.channels(playlistId) }
            val pl = withContext(Dispatchers.IO) { store.playlists().firstOrNull { it.id == playlistId } }
            headerTitle.text = pl?.name ?: getString(R.string.section_iptv)
            groups = channels.map { it.displayGroup }.distinct().sortedWith(java.text.Collator.getInstance(java.util.Locale("ru")))
            renderGroups()
            showChannels()
            if (pl != null && pl.url.startsWith("https://iptv-org.github.io/") &&
                System.currentTimeMillis() - pl.updatedAt > 24 * 3600_000L) downloadAndSave(pl.name, pl.url, pl.id)
            val epgUrl = ServiceLocator.prefs.epgUrl
            if (ServiceLocator.prefs.autoEpg || epgUrl.isNotBlank()) ServiceLocator.epg.ensureForChannels(channels, epgUrl)
            showChannels()
        }
    }

    private fun renderGroups() {
        val names = mutableListOf(getString(R.string.all))
        names.addAll(groups)
        val adapter = GroupsAdapter(names) { idx ->
            selectedGroup = if (idx == 0) null else names[idx]
            showChannels()
            groupsRow.adapter?.notifyDataSetChanged()
            channelsGrid.requestFocus()
        }
        groupsRow.adapter = adapter
    }

    private fun showChannels() {
        val filtered = channels.filter { (selectedGroup == null || it.displayGroup == selectedGroup) &&
            channelTitle(it).contains(searchQuery, ignoreCase = true) && (!hideFailed || health(it) != -1) }
        val collator = java.text.Collator.getInstance(java.util.Locale("ru"))
        val list = when (sortOrder) {
            1 -> filtered.sortedWith { a, b -> collator.compare(channelTitle(a), channelTitle(b)) }
            2 -> filtered.sortedWith { a, b -> collator.compare(channelTitle(b), channelTitle(a)) }
            3 -> filtered.sortedBy { when (health(it)) { 1 -> 0; -1 -> 2; else -> 1 } }
            else -> filtered
        }

        status.text = if (channels.isEmpty()) getString(R.string.playlist_empty)
        else getString(R.string.channel_count, list.size)
        status.visibility = android.view.View.VISIBLE
        if (searchQuery.isNotEmpty()) status.text = "Найдено: ${list.size} • $searchQuery"

        channelsGrid.adapter = ChannelsAdapter(list) { ch ->
            val entry = MediaEntry(
                kind = Kinds.CHANNEL, itemId = ch.key, title = channelTitle(ch),
                poster = ch.logo, url = ch.url, subtitle = ch.displayGroup,
            )
            lifecycleScope.launch(Dispatchers.IO) { store.addHistory(entry) }
            Nav.play(this, entry, playlistId = playlistId)
        }
    }

    private fun showAvailabilityMenu() {
        Dialogs.choice(this, "Проверка ссылок", listOf("Проверить каналы плейлиста", if (hideFailed) "Показать недоступные" else "Скрывать недоступные", "Остановить проверку", "Сбросить результаты"), 0) { action ->
            when (action) {
                0 -> checkChannels()
                1 -> {
                    hideFailed = !hideFailed
                    channelSettings.edit().putBoolean("hide_failed_$playlistId", hideFailed).apply()
                    showChannels()
                }
                2 -> { probeJob?.cancel(); showChannels() }
                3 -> { probeJob?.cancel(); healthPrefs.edit().clear().apply(); showChannels() }
            }
        }
    }

    private fun checkChannels() {
        probeJob?.cancel()
        val snapshot = channels.toList()
        if (snapshot.isEmpty()) return
        val originalPlaylist = playlistId
        probeJob = lifecycleScope.launch {
            val semaphore = Semaphore(4)
            val probe = com.astraplay.tv.data.iptv.ChannelProbe(ServiceLocator.http.client)
            var done = 0
            status.visible()
            status.text = "Проверка ссылок: 0 / ${snapshot.size}"
            snapshot.map { channel -> async {
                semaphore.withPermit {
                    val responds = withContext(Dispatchers.IO) { probe.responds(channel.url, channel.userAgent, channel.referrer) }
                    healthPrefs.edit().putInt("state_${channel.url}", if (responds) 1 else -1)
                        .putLong("time_${channel.url}", System.currentTimeMillis()).apply()
                    done++
                    if (playlistId == originalPlaylist) status.text = "Проверка ссылок: $done / ${snapshot.size}"
                }
            } }.awaitAll()
            if (playlistId == originalPlaylist) {
                sortOrder = 3
                channelSettings.edit().putInt("sort_$playlistId", sortOrder).apply()
                showChannels()
                status.text = "Отвечают: ${snapshot.count { health(it) == 1 }} / ${snapshot.size} • проверка ссылки не гарантирует воспроизведение"
            }
        }
    }

    private fun chooseSort() {
        Dialogs.choice(this, "Сортировка каналов", listOf("Как в плейлисте", "По названию: А–Я", "По названию: Я–А", "Сначала отвечающие каналы"), sortOrder) { order ->
            sortOrder = order
            channelSettings.edit().putInt("sort_$playlistId", order).apply()
            showChannels()
            channelsGrid.requestFocus()
        }
    }

    private fun chooseGroup() {
        val names = listOf("Все") + groups
        Dialogs.choice(this, "Группы каналов", names, if (selectedGroup == null) 0 else names.indexOf(selectedGroup).coerceAtLeast(0)) { index ->
            selectedGroup = if (index == 0) null else names[index]
            renderGroups()
            showChannels()
            channelsGrid.requestFocus()
        }
    }

    // ---------- действия ----------

    /** Выбор источника: готовые подборки iptv-org или свой URL. */
    private fun showAddPlaylist() {
        val options = mutableListOf(getString(R.string.preset_custom), "Открыть M3U-файл с устройства") +
                com.astraplay.tv.data.iptv.IptvPresets.list.map { it.title }
        Dialogs.choice(this, getString(R.string.choose_playlist_source), options, 0) { idx ->
            if (idx == 0) {
                Dialogs.input(this, getString(R.string.playlist_name), getString(R.string.playlist_name)) { name ->
                    Dialogs.input(this, getString(R.string.playlist_url), "http://…/playlist.m3u") { url ->
                        downloadAndSave(name, url)
                    }
                }
            } else if (idx == 1) {
                openPlaylistFile.launch(arrayOf("*/*"))
            } else {
                val preset = com.astraplay.tv.data.iptv.IptvPresets.list[idx - 2]
                downloadAndSave(preset.title, preset.url)
            }
        }
    }

    /** Обновить все плейлисты подряд (для автоактуализации списков каналов). */
    private fun updateAllPlaylists() {
        status.text = getString(R.string.updating)
        status.visible()
        lifecycleScope.launch {
            val lists = withContext(Dispatchers.IO) { store.playlists() }
            var ok = 0
            for (pl in lists) {
                val res = withContext(Dispatchers.IO) {
                    runCatching {
                        val text = playlistText(pl.url)
                        M3uParser.forPlaylist(text, pl.url).also { require(it.isNotEmpty() && !text.trimStart().startsWith("<")) { "Пустой или некорректный плейлист" } }
                    }
                }
                res.onSuccess { parsed ->
                    withContext(Dispatchers.IO) {
                        store.replaceChannels(pl.id, parsed)
                        store.touchPlaylist(pl.id)
                    }
                    ok++
                }
            }
            if (playlistId !in lists.map { it.id }) playlistId = lists.firstOrNull()?.id ?: -1
            Toast.makeText(this@IptvActivity,
                getString(R.string.updated_all_done, ok), Toast.LENGTH_SHORT).show()
            openChannels()
        }
    }

    /** Полноэкранная сетка телепрограммы. */
    private fun openEpgGrid() {
        if (!ServiceLocator.epg.isLoaded) {
            Toast.makeText(this, getString(R.string.epg_grid_empty), Toast.LENGTH_LONG).show()
            return
        }
        com.astraplay.tv.ui.nav.Nav.openEpgGrid(this, playlistId)
    }

    /** Телепрограмма канала: ближайшие передачи из загруженного EPG. */
    private fun showChannelEpg(ch: Channel) {
        val epg = ServiceLocator.epg
        if (!epg.isLoaded) {
            Toast.makeText(this, getString(R.string.no_epg), Toast.LENGTH_LONG).show()
            return
        }
        val upcoming = epg.forChannel(ch.epgId, ch.name, System.currentTimeMillis(), System.currentTimeMillis() + 36 * 3600_000L).take(12)
        if (upcoming.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_epg), Toast.LENGTH_LONG).show()
            return
        }
        val items = upcoming.map { p ->
            val day = java.text.SimpleDateFormat("dd.MM", java.util.Locale.getDefault())
                .format(java.util.Date(p.startMs))
            "${formatClock(p.startMs)}  ${p.title}" + (if (p.desc != null) "  —  ${p.desc.take(60)}" else "") + "   [$day]"
        }
        Dialogs.choice(this,
            "${getString(R.string.epg_programme)}: ${ch.name}", items, 0) { /* просмотр */ }
    }

    private fun showPlaylists() {
        lifecycleScope.launch {
            val lists = withContext(Dispatchers.IO) { store.playlists() }
            if (lists.isEmpty()) { showAddPlaylist(); return@launch }
            val names = lists.map { it.name }
            Dialogs.choice(this@IptvActivity, getString(R.string.iptv_playlists), names, 0) { idx ->
                playlistId = lists[idx].id
                openChannels()
            }
        }
    }

    private fun deleteCurrentPlaylist() {
        lifecycleScope.launch {
            val pl = withContext(Dispatchers.IO) { store.playlists().firstOrNull { it.id == playlistId } }
            if (pl == null) return@launch
            Dialogs.confirm(this@IptvActivity, getString(R.string.delete),
                getString(R.string.delete_playlist_q, pl.name)) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { store.deletePlaylist(pl.id) }
                    playlistId = -1L
                    loadPlaylistsAndChannels()
                }
            }
        }
    }

    private fun updatePlaylist() {
        lifecycleScope.launch {
            val pl = withContext(Dispatchers.IO) { store.playlists().firstOrNull { it.id == playlistId } }
            if (pl == null) return@launch
            downloadAndSave(pl.name, pl.url, keepId = pl.id)
        }
    }

    private fun showEpgSetup() {
        Dialogs.input(this, getString(R.string.load_epg), "https://…/epg.xml.gz", ServiceLocator.prefs.epgUrl) { url ->
            ServiceLocator.prefs.epgUrl = url
            status.text = getString(R.string.loading)
            status.visible()
            lifecycleScope.launch {
                val res = ServiceLocator.epg.ensureLoaded(url, force = true)
                res.fold(
                    onSuccess = {
                        status.text = getString(R.string.epg_next) + ": OK"
                        showChannels()
                    },
                    onFailure = {
                        status.text = getString(R.string.network_error)
                    }
                )
            }
        }
    }

    private fun downloadAndSave(name: String, url: String, keepId: Long = -1L) {
        status.text = getString(R.string.loading)
        status.visible()
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val text = playlistText(url)
                    if (ServiceLocator.prefs.epgUrl.isBlank()) M3uParser.epgUrl(text)?.let { ServiceLocator.prefs.epgUrl = it }
                    M3uParser.forPlaylist(text, url).also { require(it.isNotEmpty() && !text.trimStart().startsWith("<")) { "Пустой или некорректный плейлист" } }
                }
            }
            res.fold(
                onSuccess = { parsed ->
                    val id = withContext(Dispatchers.IO) {
                        if (keepId > 0) {
                            store.replaceChannels(keepId, parsed)
                            store.touchPlaylist(keepId)
                            keepId
                        } else {
                            val newId = store.addPlaylist(name, url, "url")
                            store.replaceChannels(newId, parsed)
                            newId
                        }
                    }
                    playlistId = id
                    Toast.makeText(this@IptvActivity,
                        getString(R.string.playlist_updated, name), Toast.LENGTH_SHORT).show()
                    openChannels()
                },
                onFailure = {
                    status.text = getString(R.string.network_error)
                }
            )
        }
    }

    // ---------- адаптеры ----------

    private class GroupsVH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    private inner class GroupsAdapter(
        private val names: List<String>,
        private val onSelect: (Int) -> Unit,
    ) : RecyclerView.Adapter<GroupsVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupsVH {
            val tv = TextView(this@IptvActivity).apply {
                textSize = 14f
                isFocusable = true
                isClickable = true
                setPadding(14.dp(), 8.dp(), 14.dp(), 8.dp())
                background = roundedBg(this@IptvActivity, ThemeManager.cardColor(this@IptvActivity), 8f)
            }
            tv.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8.dp() }
            return GroupsVH(tv)
        }
        override fun getItemCount() = names.size
        override fun onBindViewHolder(holder: GroupsVH, position: Int) {
            val name = names[position]
            holder.tv.text = name
            holder.tv.setOnFocusChangeListener { view, focused ->
                view.background = roundedBg(this@IptvActivity, if (focused) withAlpha(ThemeManager.accentColor(this@IptvActivity), 65) else ThemeManager.cardColor(this@IptvActivity), 10f,
                    if (focused) ThemeManager.strokeColor(this@IptvActivity) else null, 2f)
            }
            val selected = if (position == 0) selectedGroup == null else selectedGroup == name
            holder.tv.setTextColor(
                if (selected) ThemeManager.accentColor(this@IptvActivity)
                else ThemeManager.colorOf(this@IptvActivity, android.R.attr.textColorPrimary))
            holder.tv.setOnClickListener { onSelect(position) }
        }
    }

    private class ChannelVH(val card: PosterCard) : RecyclerView.ViewHolder(card)

    private inner class ChannelsAdapter(
        private val list: List<Channel>,
        private val onClick: (Channel) -> Unit,
    ) : RecyclerView.Adapter<ChannelVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelVH {
            val card = PosterCard(this@IptvActivity)
            card.applyMode(PosterCard.MODE_WIDE)
            val lp = GridLayoutManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 166.dp()
            ).apply { rightMargin = 12.dp(); bottomMargin = 12.dp() }
            card.layoutParams = lp
            return ChannelVH(card)
        }
        override fun getItemCount() = list.size
        override fun onBindViewHolder(holder: ChannelVH, position: Int) {
            val ch = list[position]
            holder.card.loadPoster(ch.logo)
            holder.card.label.text = channelTitle(ch)
            val now = ServiceLocator.epg.forChannel(ch.epgId, ch.name, System.currentTimeMillis(), System.currentTimeMillis() + 1).firstOrNull()
            holder.card.showBadge(
                if (health(ch) == -1) "Не отвечает" else if (now != null) formatClock(now.startMs) + " " + now.title.take(24) else if (health(ch) == 1) "Ссылка отвечает" else null
            )
            holder.card.setOnClickListener { onClick(ch) }
            holder.card.setOnLongClickListener {
                // меню канала: в избранное или телепрограмма
                Dialogs.choice(this@IptvActivity,
                    "${getString(R.string.channel_actions)}: ${ch.name}",
                    listOf(getString(R.string.add_to_fav), getString(R.string.epg_programme), "Изменить название"), 0) { idx ->
                    when (idx) {
                        0 -> {
                            val entry = MediaEntry(
                                kind = Kinds.CHANNEL, itemId = ch.key, title = channelTitle(ch),
                                poster = ch.logo, url = ch.url, subtitle = ch.displayGroup)
                            lifecycleScope.launch(Dispatchers.IO) { store.addFavorite(entry) }
                            Toast.makeText(this@IptvActivity,
                                getString(R.string.added_to_fav), Toast.LENGTH_SHORT).show()
                        }
                        1 -> showChannelEpg(ch)
                        2 -> Dialogs.input(this@IptvActivity, "Название канала", "Название на русском", channelTitle(ch)) { name ->
                            channelSettings.edit().putString("name_${ch.url}", name).apply()
                            showChannels()
                        }
                    }
                }
                true
            }
        }
    }
}
