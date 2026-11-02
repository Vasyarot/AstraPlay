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

    private lateinit var root: LinearLayout
    private lateinit var headerTitle: TextView
    private lateinit var groupsRow: RecyclerView
    private lateinit var channelsGrid: RecyclerView
    private lateinit var status: TextView

    private val store get() = ServiceLocator.store

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
        root.addView(actions)

        fun action(label: String, click: () -> Unit) {
            actions.addView(actionButton(label, click))
        }
        action(getString(R.string.add_playlist)) { showAddPlaylist() }
        action(getString(R.string.iptv_playlists)) { showPlaylists() }
        action(getString(R.string.refresh_playlist)) { updatePlaylist() }
        action(getString(R.string.update_all)) { updateAllPlaylists() }
        action(getString(R.string.load_epg)) { showEpgSetup() }
        action(getString(R.string.delete)) { deleteCurrentPlaylist() }

        status = TextView(this).apply {
            textSize = 13f
            setTextColor(ThemeManager.colorOf(this@IptvActivity, android.R.attr.textColorSecondary))
            setPadding(16.dp(), 6.dp(), 16.dp(), 6.dp())
            gone()
        }
        root.addView(status)

        // Группы
        groupsRow = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@IptvActivity, LinearLayoutManager.HORIZONTAL, false)
            setPadding(16.dp(), 4.dp(), 16.dp(), 4.dp())
            clipToPadding = false
        }
        root.addView(groupsRow, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        // Сетка каналов
        channelsGrid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@IptvActivity, 5, GridLayoutManager.VERTICAL, false)
            setHasFixedSize(true)
            setItemViewCacheSize(8)
        }
        root.addView(channelsGrid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
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
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = 8.dp() }
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
                    status.text = getString(R.string.playlist_empty)
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
        lifecycleScope.launch {
            channels = withContext(Dispatchers.IO) { store.channels(playlistId) }
            val pl = withContext(Dispatchers.IO) { store.playlists().firstOrNull { it.id == playlistId } }
            headerTitle.text = pl?.name ?: getString(R.string.section_iptv)
            groups = channels.mapNotNull { it.group }.distinct().sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { it })
            renderGroups()
            showChannels()
        }
    }

    private fun renderGroups() {
        val names = mutableListOf(getString(R.string.all))
        names.addAll(groups)
        val adapter = GroupsAdapter(names) { idx ->
            selectedGroup = if (idx == 0) null else names[idx]
            showChannels()
        }
        groupsRow.adapter = adapter
    }

    private fun showChannels() {
        val epg = ServiceLocator.epg
        val list = if (selectedGroup == null) channels
        else channels.filter { it.group == selectedGroup }

        status.text = if (channels.isEmpty()) getString(R.string.playlist_empty)
        else getString(R.string.channel_count, list.size)
        status.visibility = if (channels.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE

        channelsGrid.adapter = ChannelsAdapter(list) { ch ->
            val entry = MediaEntry(
                kind = Kinds.CHANNEL, itemId = ch.key, title = ch.name,
                poster = ch.logo, url = ch.url, subtitle = ch.group,
            )
            lifecycleScope.launch(Dispatchers.IO) { store.addHistory(entry) }
            Nav.play(this, entry)
        }
    }

    // ---------- действия ----------

    /** Выбор источника: готовые подборки iptv-org или свой URL. */
    private fun showAddPlaylist() {
        val options = mutableListOf(getString(R.string.preset_custom)) +
                com.astraplay.tv.data.iptv.IptvPresets.list.map { it.title }
        Dialogs.choice(this, getString(R.string.choose_playlist_source), options, 0) { idx ->
            if (idx == 0) {
                Dialogs.input(this, getString(R.string.playlist_name), getString(R.string.playlist_name)) { name ->
                    Dialogs.input(this, getString(R.string.playlist_url), "http://…/playlist.m3u") { url ->
                        downloadAndSave(name, url)
                    }
                }
            } else {
                val preset = com.astraplay.tv.data.iptv.IptvPresets.list[idx - 1]
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
                        val text = ServiceLocator.http.get(pl.url)
                        M3uParser.parse(text)
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
            if (lists.isNotEmpty()) playlistId = lists.first().id
            Toast.makeText(this@IptvActivity,
                getString(R.string.updated_all_done, ok), Toast.LENGTH_SHORT).show()
            openChannels()
        }
    }

    /** Телепрограмма канала: ближайшие передачи из загруженного EPG. */
    private fun showChannelEpg(ch: Channel) {
        val epg = ServiceLocator.epg
        if (!epg.isLoaded) {
            Toast.makeText(this, getString(R.string.no_epg), Toast.LENGTH_LONG).show()
            return
        }
        val upcoming = epg.upcoming(ch.epgId, 12)
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
        Dialogs.input(this, getString(R.string.load_epg), "http://…/epg.xml") { url ->
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
                    val text = ServiceLocator.http.get(url)
                    M3uParser.parse(text)
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
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = 8.dp() }
            return GroupsVH(tv)
        }
        override fun getItemCount() = names.size
        override fun onBindViewHolder(holder: GroupsVH, position: Int) {
            val name = names[position]
            holder.tv.text = name
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
                220.dp(), 170.dp()
            ).apply { rightMargin = 12.dp(); bottomMargin = 12.dp() }
            card.layoutParams = lp
            return ChannelVH(card)
        }
        override fun getItemCount() = list.size
        override fun onBindViewHolder(holder: ChannelVH, position: Int) {
            val ch = list[position]
            holder.card.loadPoster(ch.logo)
            holder.card.label.text = ch.name
            val now = ServiceLocator.epg.nowFor(ch.epgId)
            holder.card.showBadge(
                if (now != null) formatClock(now.startMs) + " " + now.title.take(24) else null
            )
            holder.card.setOnClickListener { onClick(ch) }
            holder.card.setOnLongClickListener {
                // меню канала: в избранное или телепрограмма
                Dialogs.choice(this@IptvActivity,
                    "${getString(R.string.channel_actions)}: ${ch.name}",
                    listOf(getString(R.string.add_to_fav), getString(R.string.epg_programme)), 0) { idx ->
                    when (idx) {
                        0 -> {
                            val entry = MediaEntry(
                                kind = Kinds.CHANNEL, itemId = ch.key, title = ch.name,
                                poster = ch.logo, url = ch.url, subtitle = ch.group)
                            lifecycleScope.launch(Dispatchers.IO) { store.addFavorite(entry) }
                            Toast.makeText(this@IptvActivity,
                                getString(R.string.added_to_fav), Toast.LENGTH_SHORT).show()
                        }
                        else -> showChannelEpg(ch)
                    }
                }
                true
            }
        }
    }
}
