package com.astraplay.tv.player

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.*
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.astraplay.tv.R
import com.astraplay.tv.core.ServiceLocator
import com.astraplay.tv.data.db.*
import com.astraplay.tv.data.iptv.*
import com.astraplay.tv.ui.common.*
import com.astraplay.tv.ui.theme.ThemeManager
import kotlinx.coroutines.*

/** IPTV has its own queue and lifecycle; movie resume positions never affect live streams. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LivePlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private val httpFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true).setUserAgent("AstraPlay/1.4")
    private fun applyHeaders(channel: Channel?) {
        httpFactory.setUserAgent(channel?.userAgent ?: "AstraPlay/1.4")
        httpFactory.setDefaultRequestProperties(channel?.referrer?.let { mapOf("Referer" to it) } ?: emptyMap())
    }
    private lateinit var video: PlayerView
    private lateinit var root: FrameLayout
    private lateinit var chrome: LinearLayout
    private lateinit var headline: TextView
    private lateinit var description: TextView
    private lateinit var drawer: LinearLayout
    private lateinit var channelList: RecyclerView
    private var channels: List<Channel> = emptyList()
    private var channelIndex = 0
    private var drawerPosition = 0
    private lateinit var channelButton: TextView
    private var currentUrl = ""
    private var currentTitle = ""
    private var playing = true
    private var streamTitle: String? = null
    private var streamArtist: String? = null
    private var manuallyShifted = false
    private lateinit var liveButton: TextView
    private val refreshInfo = object : Runnable {
        override fun run() { updateStatus(); root.postDelayed(this, 1000) }
    }
    private val hideChrome = Runnable {
        if (drawer.visibility != View.VISIBLE) { chrome.visibility = View.GONE; video.requestFocus() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        currentUrl = savedInstanceState?.getString("url") ?: intent.getStringExtra("url") ?: ""
        currentTitle = savedInstanceState?.getString("title") ?: intent.getStringExtra("title") ?: "IPTV"
        if (currentUrl.isBlank()) { finish(); return }
        root = FrameLayout(this)
        video = PlayerView(this).apply { useController = false; keepScreenOn = true; isFocusable = true; isFocusableInTouchMode = true }
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
        chrome = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp(this@LivePlayerActivity), 16.dp(this@LivePlayerActivity), 24.dp(this@LivePlayerActivity), 16.dp(this@LivePlayerActivity))
            setBackgroundColor(0xE6101422.toInt())
        }
        headline = TextView(this).apply { textSize = 23f; setTextColor(-1); text = currentTitle }
        description = TextView(this).apply { textSize = 13f; setTextColor(0xFFD0D7E8.toInt()) }
        chrome.addView(headline)
        chrome.addView(description)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        channelButton = tvButton(this, "Каналы", R.drawable.ic_tv) { showChannels() }
        actions.addView(channelButton)
        actions.addView(tvButton(this, "Предыдущий") { switchChannel(-1) })
        actions.addView(tvButton(this, "Следующий") { switchChannel(1) })
        actions.addView(tvButton(this, "Пауза / эфир") { togglePause() })
        liveButton = tvButton(this, "В прямой эфир", R.drawable.ic_live) {
            player?.seekToDefaultPosition()
            player?.play()
            manuallyShifted = false
            updateStatus()
            showChrome()
        }
        actions.addView(liveButton)
        actions.addView(tvButton(this, "Программа", R.drawable.ic_info) { showProgramme() })
        actions.addView(tvButton(this, "Переподключить") { player?.prepare(); player?.play(); showChrome() })
        actions.addView(tvButton(this, "Звук") {
            player?.let { androidx.media3.ui.TrackSelectionDialogBuilder(this, "Звуковая дорожка", it, C.TRACK_TYPE_AUDIO).build().show() }
        })
        chrome.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(actions) })
        root.addView(chrome, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp(this@LivePlayerActivity), 12.dp(this@LivePlayerActivity), 12.dp(this@LivePlayerActivity), 12.dp(this@LivePlayerActivity))
            setBackgroundColor(0xFA151A2A.toInt())
            visibility = View.GONE
        }
        drawer.addView(makeTitle(this, "Каналы плейлиста"))
        channelList = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@LivePlayerActivity)
            isFocusable = true
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            itemAnimator = null
        }
        drawer.addView(channelList, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(drawer, FrameLayout.LayoutParams(340.dp(this), -1, Gravity.START))
        setContentView(root)
        video.requestFocus()
        video.setOnClickListener { focusControls() }
        lifecycleScope.launch {
            val requestedPlaylist = intent.getLongExtra("playlistId", -1)
            val result = withContext(Dispatchers.IO) {
                val store = ServiceLocator.store
                if (requestedPlaylist > 0) store.channels(requestedPlaylist)
                else {
                    val lists = store.playlists()
                    lists.asSequence().map { store.channels(it.id) }.firstOrNull { rows -> rows.any { it.url == currentUrl } }.orEmpty()
                }
            }
            channels = result.ifEmpty { listOf(Channel(name = currentTitle, url = currentUrl)) }
            channelIndex = channels.indexOfFirst { it.url == currentUrl }.coerceAtLeast(0)
            channelList.adapter = ChannelAdapter()
            applyHeaders(channels.getOrNull(channelIndex))
            if (channels.getOrNull(channelIndex)?.let { it.userAgent != null || it.referrer != null } == true) {
                player?.apply { setMediaItem(MediaItem.fromUri(currentUrl)); prepare(); playWhenReady = playing }
            }
            updateStatus()
            val epgUrl = ServiceLocator.prefs.epgUrl
            if (ServiceLocator.prefs.autoEpg || epgUrl.isNotBlank()) ServiceLocator.epg.ensureForChannels(channels, epgUrl)
            updateStatus()
        }
    }

    override fun onStart() {
        super.onStart()
        val buffer = ServiceLocator.prefs.hlsBufferSecs.coerceIn(5, 120) * 1000
        applyHeaders(channels.getOrNull(channelIndex))
        player = ExoPlayer.Builder(this, androidx.media3.exoplayer.DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                androidx.media3.datasource.DefaultDataSource.Factory(this, httpFactory)))
            .setLoadControl(DefaultLoadControl.Builder()
            .setBufferDurationsMs(buffer, buffer + 15000, 1500, 3000).build()).build().also { p ->
            video.player = p
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            p.setHandleAudioBecomingNoisy(true)
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setPreferredAudioLanguages("ru", "rus").build()
            p.setWakeMode(C.WAKE_MODE_NETWORK)
            p.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) { updateStatus() }
                override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                    mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() }?.let { streamTitle = it }
                    mediaMetadata.artist?.toString()?.takeIf { it.isNotBlank() }?.let { streamArtist = it }
                    updateStatus()
                }
                override fun onMetadata(metadata: Metadata) {
                    for (i in 0 until metadata.length()) {
                        when (val entry = metadata[i]) {
                            is androidx.media3.extractor.metadata.icy.IcyInfo -> {
                                streamTitle = entry.title?.takeIf { it.isNotBlank() }
                                streamArtist = null
                            }
                            is androidx.media3.extractor.metadata.id3.TextInformationFrame -> when (entry.id) {
                                "TIT2", "TT2" -> streamTitle = entry.values.firstOrNull()
                                "TPE1", "TP1" -> streamArtist = entry.values.firstOrNull()
                            }
                        }
                    }
                    updateStatus()
                }
                override fun onPlayerError(error: PlaybackException) {
                    if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                        p.seekToDefaultPosition(); p.prepare(); p.play(); return
                    }
                    description.text = "Не удалось открыть поток. Выберите другой канал или обновите плейлист."
                    showChrome()
                }
            })
            p.setMediaItem(MediaItem.fromUri(currentUrl))
            p.prepare()
            p.playWhenReady = playing
        }
        root.post(refreshInfo)
        focusControls()
    }

    override fun onStop() {
        playing = player?.playWhenReady ?: playing
        root.removeCallbacks(hideChrome)
        root.removeCallbacks(refreshInfo)
        video.player = null
        player?.release()
        player = null
        super.onStop()
    }

    private fun displayName(ch: Channel): String = getSharedPreferences("iptv_display", MODE_PRIVATE)
        .getString("name_${ch.url}", null) ?: ch.displayName

    private fun updateStatus() {
        headline.text = if (channels.isEmpty()) currentTitle else "${channelIndex + 1} / ${channels.size}  •  $currentTitle"
        val p = player
        val now = System.currentTimeMillis()
        val programmes = channels.getOrNull(channelIndex)?.let {
            ServiceLocator.epg.forChannel(it.epgId, it.name, now, now + 36 * 3600_000L).take(2)
        }.orEmpty()
        val programme = programmes.firstOrNull { now in it.startMs until it.stopMs }
        val next = programmes.firstOrNull { it.startMs > now }
        val liveOffset = p?.currentLiveOffset?.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
        val targetOffset = p?.currentMediaItem?.liveConfiguration?.targetOffsetMs?.takeIf { it > 0 } ?: 5000
        if (p?.currentLiveOffset != C.TIME_UNSET && liveOffset <= targetOffset + 2000) manuallyShifted = false
        val behindLive = p?.isCurrentMediaItemLive == true && (manuallyShifted || liveOffset > targetOffset + 10000)
        liveButton.visibility = View.VISIBLE
        val live = p?.isCurrentMediaItemLive == true && !behindLive && p.playWhenReady
        liveButton.text = if (live) "Прямой эфир" else "В прямой эфир"
        liveButton.compoundDrawables.filterNotNull().forEach { it.setTint(if (live) 0xFFFF5252.toInt() else ThemeManager.accentColor(this)) }
        liveButton.setTextColor(if (live) 0xFFFF7070.toInt() else ThemeManager.colorOf(this, android.R.attr.textColorPrimary))
        description.text = when {
            p?.playerError != null -> "Ошибка потока • выберите другой канал"
            p?.playbackState == Player.STATE_BUFFERING -> "Подключаем канал…"
            p?.isCurrentMediaItemSeekable == true -> "Доступна перемотка: ← / →  •  ОК — управление • Меню — каналы"
            else -> "Прямой эфир • ↑ / ↓ — переключить • ОК — управление • Меню — каналы"
        } + (if (behindLive) " • Отставание: ${formatDuration(liveOffset)}" else "") +
            (if (p?.playbackState == Player.STATE_READY && !p.currentTracks.containsType(C.TRACK_TYPE_AUDIO)) "\nПоток не передаёт звуковую дорожку"
             else if (p?.playbackState == Player.STATE_READY && !p.currentTracks.isTypeSupported(C.TRACK_TYPE_AUDIO)) "\nАудиоформат не поддерживается устройством • меню Звук" else "") +
            (streamTitle?.let { "\nВ эфире: " + listOfNotNull(streamArtist, it).joinToString(" — ") } ?: "") +
            (programme?.let { "\n${formatClock(it.startMs)}–${formatClock(it.stopMs)}  ${it.title}" } ?: "") +
            (next?.let { "\nДалее в ${formatClock(it.startMs)}: ${it.title}" } ?: "")
    }

    private fun showProgramme() {
        val channel = channels.getOrNull(channelIndex)
        val now = System.currentTimeMillis()
        val rows = channel?.let { ServiceLocator.epg.forChannel(it.epgId, it.name, now, now + 36 * 3600_000L).take(16) }.orEmpty()
        if (rows.isNotEmpty()) {
            Dialogs.choice(this, "Программа • $currentTitle", rows.map {
                "${formatClock(it.startMs)}–${formatClock(it.stopMs)}  ${it.title}" + (it.desc?.let { desc -> "\n${desc.take(180)}" } ?: "")
            }, 0) { }
        } else {
            Toast.makeText(this, "Обновляем телепрограмму…", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                val result = ServiceLocator.epg.ensureForChannels(channels, ServiceLocator.prefs.epgUrl, force = true)
                Toast.makeText(this@LivePlayerActivity, if (result.isSuccess) "Телепрограмма обновлена" else "Для этого канала программа пока не найдена", Toast.LENGTH_LONG).show()
                updateStatus()
            }
        }
        showChrome()
    }

    private fun selectChannel(index: Int) {
        if (channels.isEmpty()) return
        channelIndex = (index % channels.size + channels.size) % channels.size
        val channel = channels[channelIndex]
        currentUrl = channel.url
        streamTitle = null
        streamArtist = null
        manuallyShifted = false
        currentTitle = displayName(channel)
        applyHeaders(channel)
        player?.apply { setMediaItem(MediaItem.fromUri(currentUrl)); prepare(); play() }
        playing = true
        val entry = MediaEntry(Kinds.CHANNEL, channel.key, currentTitle, subtitle = channel.displayGroup, poster = channel.logo, url = channel.url)
        lifecycleScope.launch(Dispatchers.IO) { ServiceLocator.store.addHistory(entry) }
        closeChannels()
        updateStatus()
        showChrome()
    }

    private fun switchChannel(delta: Int) { selectChannel(channelIndex + delta) }
    private fun togglePause() {
        player?.let { if (it.playWhenReady) it.pause() else it.play() }
        showChrome()
    }
    private fun showChrome() {
        chrome.visibility = View.VISIBLE
        root.removeCallbacks(hideChrome)
        root.postDelayed(hideChrome, 8000)
    }
    private fun showChannels() {
        drawerPosition = channelIndex.coerceIn(0, (channels.size - 1).coerceAtLeast(0))
        drawer.visibility = View.VISIBLE
        root.removeCallbacks(hideChrome)
        channelList.adapter?.notifyDataSetChanged()
        channelList.scrollToPosition(drawerPosition)
        channelList.requestFocus()
    }

    private fun moveChannelSelection(delta: Int) {
        if (channels.isEmpty()) return
        val previous = drawerPosition
        drawerPosition = (drawerPosition + delta + channels.size) % channels.size
        channelList.adapter?.notifyItemChanged(previous)
        channelList.adapter?.notifyItemChanged(drawerPosition)
        channelList.scrollToPosition(drawerPosition)
        channelList.requestFocus()
    }

    private fun closeChannels() { drawer.visibility = View.GONE; focusControls() }

    private fun focusControls() {
        showChrome()
        channelButton.requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (drawer.visibility == View.VISIBLE && event.keyCode in setOf(
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) {
            if (event.action == KeyEvent.ACTION_DOWN) when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> moveChannelSelection(-1)
                KeyEvent.KEYCODE_DPAD_DOWN -> moveChannelSelection(1)
                else -> selectChannel(drawerPosition)
            }
            return true
        }
        if (chrome.hasFocus() && event.action == KeyEvent.ACTION_DOWN) showChrome()
        val globalKey = event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_MENU
        val videoKey = drawer.visibility != View.VISIBLE && !chrome.hasFocus() && event.keyCode in setOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        if (globalKey || videoKey) {
            if (event.action == KeyEvent.ACTION_DOWN) onKeyDown(event.keyCode, event)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (drawer.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU) { closeChannels(); return true }
            return super.onKeyDown(keyCode, event)
        }
        if (chrome.hasFocus() && keyCode != KeyEvent.KEYCODE_BACK && keyCode != KeyEvent.KEYCODE_MENU) {
            showChrome()
            return super.onKeyDown(keyCode, event)
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { switchChannel(1); true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { switchChannel(-1); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { focusControls(); true }
            KeyEvent.KEYCODE_MENU -> { showChannels(); true }
            KeyEvent.KEYCODE_BACK -> { onBackPressed(); true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePause(); true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                player?.let { p ->
                    if (p.isCurrentMediaItemSeekable) {
                        val delta = ServiceLocator.prefs.seekStepSecs.coerceIn(5, 120) * 1000L * if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                        val max = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                        if (delta < 0 && p.isCurrentMediaItemLive) manuallyShifted = true
                        p.seekTo((p.currentPosition + delta).coerceIn(0, max))
                    } else Toast.makeText(this, "Этот поток не поддерживает перемотку", Toast.LENGTH_SHORT).show()
                }
                updateStatus(); true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    @Deprecated("Back compatibility for Android TV 7+")
    override fun onBackPressed() {
        when {
            drawer.visibility == View.VISIBLE -> closeChannels()
            chrome.visibility == View.VISIBLE -> {
                root.removeCallbacks(hideChrome)
                chrome.visibility = View.GONE
                video.requestFocus()
            }
            else -> super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("url", currentUrl)
        outState.putString("title", currentTitle)
        super.onSaveInstanceState(outState)
    }

    private class Holder(val text: TextView) : RecyclerView.ViewHolder(text)
    private inner class ChannelAdapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = channels.size
        override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Holder(tvButton(this@LivePlayerActivity, "") {}.apply {
            layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = 6.dp(this@LivePlayerActivity) }
        })
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val channel = channels[position]
            holder.text.text = "${position + 1}. ${displayName(channel)}" + if (position == channelIndex) "  ●" else ""
            holder.text.isFocusable = false
            holder.text.background = roundedBg(this@LivePlayerActivity,
                if (position == drawerPosition) withAlpha(ThemeManager.accentColor(this@LivePlayerActivity), 75) else ThemeManager.cardColor(this@LivePlayerActivity),
                12f, if (position == drawerPosition) ThemeManager.strokeColor(this@LivePlayerActivity) else null, 2f)
            holder.text.setOnClickListener { selectChannel(position) }
        }
    }
}
