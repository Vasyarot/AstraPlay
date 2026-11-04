package com.astraplay.tv.player

import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import com.astraplay.tv.R
import com.astraplay.tv.core.LastScreen
import com.astraplay.tv.core.ServiceLocator
import com.astraplay.tv.data.db.Kinds
import com.astraplay.tv.data.db.MediaEntry
import com.astraplay.tv.ui.common.Dialogs
import com.astraplay.tv.ui.common.dp
import com.astraplay.tv.ui.theme.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import com.astraplay.tv.data.sources.SourcePlayKind
import com.astraplay.tv.data.sources.SourcePlayOption

/**
 * Универсальный плеер (Media3/ExoPlayer): IPTV-HLS, локальные файлы,
 * прямые mp4. Resume-позиции, буфер по настройке, дорожки звука/субтитров,
 * автопереход к следующей серии.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView

    private var itemId = ""
    private var kind = Kinds.LOCAL
    private var title = ""
    private var subtitle: String? = null
    private var poster: String? = null
    private var url = ""
    private var startPos = 0L
    private var duration = 0L
    private var nextTitle: String? = null
    private var nextUrl: String? = null
    private var nextItemId: String? = null
    private var queueKey: String? = null
    private var queueIndex = 0
    private var queue: List<VideoItem> = emptyList()
    private var queueReady = false
    private var prepareJob: Job? = null
    private var currentItem: VideoItem? = null
    private var playingBeforeStop = true
    private var qualityHeight = 720
    private var lowMemory = true
    private var errorPanel: android.view.View? = null
    private var errorText: android.widget.TextView? = null
    private var resolvedUrl: String? = null
    private var resolvedFallbackUrl: String? = null
    private var smbPlayUrl: String? = null
    private var waitingForEmbed = false
    private var pendingEmbedResult: androidx.activity.result.ActivityResult? = null
    private val embeddedPlayer = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (!queueReady) pendingEmbedResult = result else handleEmbedResult(result)
    }

    private fun handleEmbedResult(result: androidx.activity.result.ActivityResult) {
        waitingForEmbed = false
        val completed = result.data?.getBooleanExtra("completed", false) == true
        val position = result.data?.getLongExtra("position", 0) ?: 0
        val length = result.data?.getLongExtra("duration", 0) ?: 0
        val snapshot = currentItem?.entry(if (completed) 0 else position, length)
        val storage = store
        if (snapshot != null && (completed || result.data?.getBooleanExtra("snapshotAvailable", false) == true))
            persistenceScope.launch { storage.savePosition(snapshot); storage.addHistory(snapshot) }
        if (completed && prefs.autoplayNext && queueIndex + 1 < queue.size) {
            queueIndex++; startPos = 0; duration = 0; playingBeforeStop = true
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) openCurrent(true)
        } else finish()
    }

    private val store get() = ServiceLocator.store
    private val prefs get() = ServiceLocator.prefs

    /** Видим ли контроллер (для поведения D-pad влево/вправо). */
    private var controllerVisible = false
    private var playbackCompleted = false
    private val persistenceJob = kotlinx.coroutines.SupervisorJob()
    private val persistenceScope = kotlinx.coroutines.CoroutineScope(persistenceJob + Dispatchers.IO.limitedParallelism(1))

    // ---------- избранное ----------

    private var favButton: android.widget.TextView? = null
    private var favState = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)

        readIntent()
        val manager = getSystemService(android.app.ActivityManager::class.java)
        val memory = android.app.ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }
        lowMemory = manager.isLowRamDevice || memory.totalMem <= 1536L * 1024 * 1024 || manager.memoryClass <= 128
        qualityHeight = savedInstanceState?.getInt("quality") ?: if (lowMemory) 720 else 1080
        startPos = savedInstanceState?.getLong("position") ?: startPos
        playingBeforeStop = savedInstanceState?.getBoolean("playing") ?: true
        waitingForEmbed = savedInstanceState?.getBoolean("waitingEmbed") ?: false

        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.player_view)
        playerView.apply {
            useController = true
            controllerShowTimeoutMs = 4000
            controllerHideOnTouch = true
            // видео — во весь экран по умолчанию, кадр можно сменить в меню
            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            isFocusable = true
            isFocusableInTouchMode = true
            setControllerVisibilityListener(object : PlayerView.ControllerVisibilityListener {
                override fun onVisibilityChanged(visibility: Int) {
                    controllerVisible = visibility == android.view.View.VISIBLE
                }
            })
            setShowSubtitleButton(true)
        }
        playerView.requestFocus()

        // шапка контроллера: название и подзаголовок текущей записи
        playerView.findViewById<android.widget.TextView>(R.id.player_title)?.text = title
        playerView.findViewById<android.widget.TextView>(R.id.player_subtitle)?.text = subtitle

        // избранное прямо в контроллере
        favButton = playerView.findViewById(R.id.player_fav)
        favButton?.setOnClickListener { toggleFavorite() }
        favButton?.setOnFocusChangeListener { _, focused -> paintFavButton(focused) }
        if (itemId.isBlank()) favButton?.visibility = android.view.View.GONE
        refreshFavState()
        setupControls()
        loadQueue(savedInstanceState?.getInt("queueIndex") ?: intent.getIntExtra("queueIndex", 0))
    }

    private fun setupControls() {
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        playerView.findViewById<androidx.media3.ui.PlayerControlView>(androidx.media3.ui.R.id.exo_controller)?.apply {
            setAnimationEnabled(false)
            setShowPreviousButton(false)
            setShowNextButton(false)
        }
        errorPanel = findViewById(R.id.player_error_panel)
        errorText = findViewById(R.id.player_error_text)
        fun action(id: Int, block: () -> Unit) { findViewById<android.view.View>(id)?.setOnClickListener { block() } }
        action(R.id.player_menu) { showPlayerMenu() }
        action(R.id.player_quality) { chooseQuality() }
        action(R.id.player_previous) { selectQueueItem(queueIndex - 1) }
        action(R.id.player_next) { selectQueueItem(queueIndex + 1) }
        action(R.id.player_retry) { errorPanel?.visibility = android.view.View.GONE; openCurrent(false) }
        action(R.id.player_error_quality) { chooseQuality() }
        action(R.id.player_close) { finish() }
        val buttons = listOf(R.id.player_menu, R.id.player_quality, R.id.player_previous, R.id.player_next,
            R.id.player_retry, R.id.player_error_quality, R.id.player_close)
        buttons.forEach { id -> findViewById<android.widget.TextView>(id)?.apply {
            val normal = com.astraplay.tv.ui.common.roundedBg(context, 0x40202030, 10f)
            val focused = com.astraplay.tv.ui.common.roundedBg(context, com.astraplay.tv.ui.common.withAlpha(ThemeManager.accentColor(context), 140), 10f, ThemeManager.strokeColor(context))
            background = normal
            setOnFocusChangeListener { v, focus -> v.background = if (focus) focused else normal }
        } }
        (playerView.findViewById<androidx.media3.ui.DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress))?.apply {
            setPlayedColor(ThemeManager.accentColor(this@PlayerActivity))
            // The default is duration / 20, which jumps minutes in a full-length movie.
            setKeyTimeIncrement(prefs.seekStepSecs.coerceIn(5, 120) * 1000L)
        }
    }

    private fun loadQueue(index: Int) {
        queueKey = intent.getStringExtra("queueKey")
        lifecycleScope.launch {
            queue = withContext(Dispatchers.IO) { ServiceLocator.videoQueues.read(queueKey) }
            if (queue.isEmpty()) {
                if (!VideoQueueStore.validUrl(url)) { showError(getString(R.string.video_invalid_address)); return@launch }
                val playKind = runCatching { SourcePlayKind.valueOf(intent.getStringExtra("playKind") ?: "DIRECT") }.getOrDefault(SourcePlayKind.DIRECT)
                queue = buildList {
                    add(VideoItem(itemId, title, url, kind, subtitle, poster, playKind,
                        intent.getStringExtra("pageUrl"), intent.getStringExtra("referer")))
                    if (!nextUrl.isNullOrBlank() && VideoQueueStore.validUrl(nextUrl!!))
                        add(VideoItem(nextItemId ?: "video_${nextUrl.hashCode()}", nextTitle ?: title, nextUrl!!, kind, subtitle, poster))
                }
            }
            queueIndex = index.coerceIn(queue.indices)
            queueReady = true
            if (waitingForEmbed) {
                currentItem = queue[queueIndex]
                currentItem?.let { item -> itemId = item.itemId; title = item.title; subtitle = item.subtitle; poster = item.poster; kind = item.kind; url = item.url }
                val pending = pendingEmbedResult
                pendingEmbedResult = null
                if (pending != null) { handleEmbedResult(pending); return@launch }
            }
            if (!waitingForEmbed && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) openCurrent(true)
        }
    }

    private fun openCurrent(restorePosition: Boolean) {
        if (!queueReady) return
        prepareJob?.cancel()
        releasePlayer(false)
        val item = queue.getOrNull(queueIndex) ?: return
        currentItem = item
        resolvedFallbackUrl = null
        itemId = item.itemId; title = item.title; subtitle = item.subtitle; poster = item.poster; kind = item.kind; url = item.url
        updateLabels()
        errorPanel?.visibility = android.view.View.GONE
        findViewById<android.view.View>(R.id.player_loading)?.visibility = android.view.View.VISIBLE
        prepareJob = lifecycleScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { store.position(item.itemId) }
                if (restorePosition && startPos <= 0) startPos = saved?.positionMs ?: 0
                duration = saved?.durationMs ?: duration
                val sourcePlay = SourcePlayOption("", item.playKind, item.url, item.referer)
                val play = if (intent.getBooleanExtra("preferEmbed", false) && item.playKind == SourcePlayKind.EMBED) sourcePlay
                    else if (intent.getBooleanExtra("preferEmbed", false) && com.astraplay.tv.data.sources.RutubeSource.videoId(item.url) != null)
                        sourcePlay.copy(kind = SourcePlayKind.EMBED, url = com.astraplay.tv.data.sources.RutubeSource.embedUrl(com.astraplay.tv.data.sources.RutubeSource.videoId(item.url)!!))
                    else if (intent.getBooleanExtra("preferEmbed", false) && com.astraplay.tv.data.sources.SmotrimPlayback.videoId(item.url) != null)
                        sourcePlay.copy(kind = SourcePlayKind.EMBED, url = com.astraplay.tv.data.sources.SmotrimPlayback.embedUrl(com.astraplay.tv.data.sources.SmotrimPlayback.videoId(item.url)!!))
                    else if (item.playKind == SourcePlayKind.DIRECT) SourcePlayOption("", item.playKind, item.url, item.referer)
                    else withContext(Dispatchers.IO) { ServiceLocator.sourceDetailsLoader.resolve(SourcePlayOption("", item.playKind, item.url, item.referer)) }
                if (play.kind == SourcePlayKind.EMBED) {
                    if (!intent.getBooleanExtra("allowEmbed", true)) {
                        showError(getString(R.string.video_native_unavailable))
                        return@launch
                    }
                    // Public direct media is unavailable. The provider's official player stays inside the app.
                    waitingForEmbed = true
                    embeddedPlayer.launch(android.content.Intent(this@PlayerActivity, EmbedPlayerActivity::class.java)
                        .putExtra("url", play.url).putExtra("title", title).putExtra("pos", startPos))
                    return@launch
                }
                resolvedUrl = play.url
                resolvedFallbackUrl = play.fallbackUrl
                smbPlayUrl = if (url.startsWith("smb://")) withContext(Dispatchers.IO) {
                    com.astraplay.tv.data.smb.SmbClient.withCredentials(url, store.smbServers())
                } else null
                val snapshot = item.entry()
                withContext(Dispatchers.IO) { store.addHistory(snapshot) }
                initPlayer()
                rememberRoute()
                refreshFavState()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { showError(getString(R.string.video_open_failed)) }
        }
    }

    private fun updateLabels() {
        favButton?.visibility = if (itemId.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
        playerView.findViewById<android.widget.TextView>(R.id.player_title)?.text = title
        playerView.findViewById<android.widget.TextView>(R.id.player_subtitle)?.text = subtitle.orEmpty()
        findViewById<android.view.View>(R.id.player_previous)?.visibility = if (queueIndex > 0) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<android.view.View>(R.id.player_next)?.visibility = if (queueIndex + 1 < queue.size) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<android.widget.TextView>(R.id.player_quality)?.text = getString(R.string.video_quality_value, qualityHeight)
    }

    private fun showError(message: String) {
        findViewById<android.view.View>(R.id.player_loading)?.visibility = android.view.View.GONE
        errorText?.text = message
        errorPanel?.visibility = android.view.View.VISIBLE
        playerView.hideController()
        findViewById<android.view.View>(R.id.player_retry)?.requestFocus()
    }

    private fun selectQueueItem(index: Int) {
        if (index !in queue.indices || index == queueIndex) return
        saveCurrent(true)
        releasePlayer(false)
        queueIndex = index; startPos = 0; duration = 0; playbackCompleted = false; playingBeforeStop = true
        openCurrent(true)
    }

    /** Рисовка кнопки избранного: звезда жёлтая в избранном, акцентная — вне. */
    private fun paintFavButton(focused: Boolean) {
        val b = favButton ?: return
        val accent = ThemeManager.accentColor(this)
        b.setTextColor(0xFFFFFFFF.toInt())
        b.background = com.astraplay.tv.ui.common.roundedBg(this,
            if (focused) com.astraplay.tv.ui.common.withAlpha(accent, 70) else 0x30FFFFFF,
            12f, if (focused) ThemeManager.strokeColor(this) else null, 2f)
        b.setTypeface(null, if (focused) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        val star = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_star)?.mutate()
        star?.setTint(if (favState) 0xFFFFC94D.toInt() else accent)
        star?.setBounds(0, 0, 18.dp(this), 18.dp(this))
        b.setCompoundDrawables(star, null, null, null)
        b.text = if (favState) getString(R.string.remove_from_fav) else getString(R.string.add_to_fav)
    }

    private fun refreshFavState() {
        if (itemId.isBlank()) return
        lifecycleScope.launch {
            favState = withContext(Dispatchers.IO) { store.isFavorite(kind, itemId) }
            paintFavButton(favButton?.hasFocus() == true)
        }
    }

    private fun toggleFavorite() {
        val entry = currentEntry()
        lifecycleScope.launch {
            val fav = withContext(Dispatchers.IO) { store.isFavorite(entry.kind, entry.itemId) }
            if (fav) {
                withContext(Dispatchers.IO) { store.removeFavorite(entry.kind, entry.itemId) }
                Toast.makeText(this@PlayerActivity, getString(R.string.removed_from_fav), Toast.LENGTH_SHORT).show()
            } else {
                withContext(Dispatchers.IO) { store.addFavorite(entry) }
                Toast.makeText(this@PlayerActivity, getString(R.string.added_to_fav), Toast.LENGTH_SHORT).show()
            }
            refreshFavState()
        }
    }

    private fun readIntent() {
        val i = intent
        itemId = i.getStringExtra("itemId") ?: ""
        kind = i.getStringExtra("kind") ?: Kinds.LOCAL
        title = i.getStringExtra("title") ?: ""
        subtitle = i.getStringExtra("subtitle")
        poster = i.getStringExtra("poster")
        url = i.getStringExtra("url") ?: ""
        startPos = i.getLongExtra("pos", 0L)
        duration = i.getLongExtra("dur", 0L)
        nextTitle = i.getStringExtra("nextTitle")
        nextUrl = i.getStringExtra("nextUrl")
        nextItemId = i.getStringExtra("nextItemId")
    }

    private fun initPlayer() {
        if (url.isBlank()) { finish(); return }

        playbackCompleted = false
        val policy = PlaybackPolicy.forDevice(lowMemory, prefs.hlsBufferSecs)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ policy.minBufferMs,
                /* maxBufferMs = */ policy.maxBufferMs,
                /* bufferForPlaybackMs = */ 2_500,
                /* bufferForPlaybackAfterRebufferMs = */ 5_000,
            )
            .setTargetBufferBytes(policy.bufferBytes)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(0, false)
            .build()
        val headers = currentItem?.referer?.takeIf { com.astraplay.tv.data.sources.OnlineSources.validWebUrl(it) }
            ?.let { mapOf("Referer" to it) } ?: emptyMap()
        val httpFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(ServiceLocator.sourceHttpClient)
            .setUserAgent(com.astraplay.tv.data.net.Http.USER_AGENT)
            .setDefaultRequestProperties(headers)
        player = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setSeekBackIncrementMs(prefs.seekStepSecs.coerceIn(5, 120) * 1000L)
            .setSeekForwardIncrementMs(prefs.seekStepSecs.coerceIn(5, 120) * 1000L)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                androidx.media3.datasource.DefaultDataSource.Factory(this, httpFactory)))
            .setLoadControl(loadControl)
            .build()
            .apply {
                setWakeMode(C.WAKE_MODE_NETWORK)
                setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                setHandleAudioBecomingNoisy(true)
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setMaxVideoSize(if (qualityHeight <= 720) 1280 else 1920, qualityHeight)
                    .setMaxVideoBitrate(if (qualityHeight <= 720) 3_000_000 else 8_000_000)
                    .setPreferredVideoMimeTypes("video/avc")
                    .setPreferredAudioLanguages("ru", "rus")
                    .build()
                addListener(playerListener)
                if (url.startsWith("smb://")) {
                    // Видео из SMB-шары: свой DataSource с поддержкой seek.
                    // В записях адрес хранится без пароля — он подставляется здесь,
                    // в момент подключения, из списка серверов.
                    val playUrl = smbPlayUrl ?: url
                    val source = androidx.media3.exoplayer.source.ProgressiveMediaSource
                        .Factory { com.astraplay.tv.data.smb.SmbDataSource() }
                        .createMediaSource(
                            MediaItem.Builder().setUri(playUrl)
                                .setMediaMetadata(
                                    androidx.media3.common.MediaMetadata.Builder()
                                        .setTitle(title).setSubtitle(subtitle).build())
                                .build())
                    setMediaSource(source)
                } else {
                    setMediaItem(
                        MediaItem.Builder()
                            .setUri(resolvedUrl ?: url)
                            .setMediaMetadata(
                                androidx.media3.common.MediaMetadata.Builder()
                                    .setTitle(title)
                                    .setSubtitle(subtitle)
                                    .build())
                            .build())
                }
                // resume, если позиция осмысленная
                if (startPos > 5_000 && (duration <= 0 || startPos < duration - 30_000)) {
                    seekTo(startPos)
                }
                prepare()
                playWhenReady = playingBeforeStop
            }
        playerView.player = player
        findViewById<android.view.View>(R.id.player_loading)?.visibility = android.view.View.GONE
        startSaving()
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            val fallback = resolvedFallbackUrl
            if (fallback != null && intent.getBooleanExtra("allowEmbed", true)) {
                resolvedFallbackUrl = null
                startPos = player?.currentPosition ?: startPos
                releasePlayer(false)
                waitingForEmbed = true
                embeddedPlayer.launch(android.content.Intent(this@PlayerActivity, EmbedPlayerActivity::class.java)
                    .putExtra("url", fallback).putExtra("title", title).putExtra("pos", startPos))
                return
            }
            val message = when (error.errorCode) {
                PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> R.string.video_decoder_error
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> R.string.video_network_error
                else -> R.string.video_open_failed
            }
            showError(getString(message))
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                playbackCompleted = true
                saveCurrent(true)
                // автопереход к следующей серии
                if (prefs.autoplayNext && queueIndex + 1 < queue.size) {
                    playerView.post { selectQueueItem(queueIndex + 1) }
                } else {
                    playerView.showController()
                }
            }
        }
    }

    // Периодическое сохранение позиции
    private var saveRunnable: Runnable? = null

    private fun startSaving() {
        saveRunnable?.let { playerView.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                val p = player
                if (p != null && p.isPlaying) {
                    val pos = p.currentPosition
                    val dur = p.duration.takeIf { it > 0 } ?: 0L
                    val entry = currentEntry(pos, dur)
                    persistenceScope.launch { store.savePosition(entry) }
                }
                playerView.postDelayed(this, 5_000)
            }
        }
        saveRunnable = r
        playerView.postDelayed(r, 5_000)
    }

    private fun currentEntry(positionMs: Long = player?.currentPosition ?: startPos,
                             durationMs: Long = player?.duration?.takeIf { it > 0 } ?: duration): MediaEntry =
        currentItem?.entry(positionMs, durationMs) ?: MediaEntry(
            kind = kind, itemId = itemId, title = title, subtitle = subtitle,
            poster = poster, url = url, positionMs = positionMs, durationMs = durationMs)

    private fun saveCurrent(history: Boolean = false) {
        if (currentItem == null || player == null) return
        val snapshot = currentEntry(if (playbackCompleted) 0 else player!!.currentPosition)
        val completed = playbackCompleted
        val storage = store
        persistenceScope.launch {
            storage.savePosition(snapshot)
            if (history) storage.addHistory(snapshot)
            if (completed) storage.setWatched(snapshot.itemId, snapshot.kind, snapshot.title, true)
        }
    }

    private fun releasePlayer(save: Boolean = true) {
        saveRunnable?.let { playerView.removeCallbacks(it) }; saveRunnable = null
        if (save) saveCurrent(true)
        player?.let { p ->
            startPos = if (playbackCompleted) 0 else p.currentPosition
            duration = p.duration.takeIf { it > 0 } ?: duration
            playingBeforeStop = p.playWhenReady && !playbackCompleted
            p.removeListener(playerListener)
            playerView.player = null
            p.release()
        }
        player = null
    }

    private fun chooseQuality() {
        Dialogs.choice(this, getString(R.string.video_quality),
            listOf(getString(R.string.video_quality_economy), getString(R.string.video_quality_fullhd)), if (qualityHeight <= 720) 0 else 1) { index ->
            qualityHeight = if (index == 0) 720 else 1080
            player?.let { p ->
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                    .setMaxVideoSize(if (qualityHeight == 720) 1280 else 1920, qualityHeight)
                    .setMaxVideoBitrate(if (qualityHeight == 720) 3_000_000 else 8_000_000).build()
            }
            updateLabels()
            rememberRoute()
        }
    }

    // ---------- управление с пульта ----------

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // любое нажатие на пульте сбрасывает синхронный таймер — как системный
        lastInteractionAt = android.os.SystemClock.elapsedRealtime()
        if (errorPanel?.visibility == android.view.View.VISIBLE) return super.onKeyDown(keyCode, event)
        val p = player ?: return super.onKeyDown(keyCode, event)
        val step = prefs.seekStepSecs.coerceIn(5, 120) * 1000L
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (!controllerVisible) {
                    if (p.isCurrentMediaItemSeekable) p.seekTo((p.currentPosition - step).coerceAtLeast(0)); true
                } else super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (!controllerVisible) {
                    if (p.isCurrentMediaItemSeekable) p.seekTo((p.currentPosition + step).coerceAtMost(p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE)); true
                } else super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_MENU -> {
                showPlayerMenu(); true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (!controllerVisible) {
                    // показать контроллер и сразу дать фокус play/pause —
                    // иначе ОК на видео «не работает»
                    playerView.showController()
                    playerView.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_play_pause)?.requestFocus()
                    controllerVisible = true
                    true
                } else if (currentFocus === playerView) {
                    // фокус на самом видео: ОК — пауза/плей
                    if (p.isPlaying) p.pause() else p.play()
                    true
                } else super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (p.isPlaying) p.pause() else p.play()
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (controllerVisible) playerView.hideController() else finish()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    /** Главное меню плеера: избранное, дорожки, скорость, кадр, таймер сна. */
    private fun showPlayerMenu() {
        val p = player ?: return
        Dialogs.choice(this, getString(R.string.player_menu),
            listOf(
                if (favState) getString(R.string.remove_from_fav) else getString(R.string.add_to_fav),
                getString(R.string.audio_track),
                getString(R.string.subtitles),
                getString(R.string.playback_speed),
                getString(R.string.resize_mode),
                getString(R.string.sleep_timer),
                getString(R.string.video_quality),
                getString(R.string.source_card_episode_list),
            ), 0) { which ->
            when (which) {
                0 -> toggleFavorite()
                1 -> TrackSelectionDialogBuilder(this, getString(R.string.audio_track), p, C.TRACK_TYPE_AUDIO)
                    .build().show()
                2 -> TrackSelectionDialogBuilder(this, getString(R.string.subtitles), p, C.TRACK_TYPE_TEXT)
                    .build().show()
                3 -> chooseSpeed(p)
                4 -> chooseResize()
                5 -> chooseSleepTimer()
                6 -> chooseQuality()
                7 -> showQueue()
            }
        }
    }

    private fun showQueue() {
        if (queue.size < 2) return
        val rows = androidx.recyclerview.widget.RecyclerView(this).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
            itemAnimator = null
            layoutParams = ViewGroup.LayoutParams(-1, 340.dp(context))
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.source_card_episode_list).setView(rows)
            .setNegativeButton(android.R.string.cancel, null).create()
        rows.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<QueueVH>() {
            override fun getItemCount() = queue.size
            override fun onCreateViewHolder(parent: ViewGroup, type: Int) = QueueVH(
                com.astraplay.tv.ui.common.tvButton(this@PlayerActivity, "") {}.apply {
                    layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(-1, 56.dp(context))
                })
            override fun onBindViewHolder(holder: QueueVH, position: Int) {
                val item = queue[position]
                holder.text.text = (if (position == queueIndex) "● " else "") + item.title
                holder.text.maxLines = 2
                holder.text.setOnClickListener {
                    val index = holder.bindingAdapterPosition
                    if (index != androidx.recyclerview.widget.RecyclerView.NO_POSITION) { dialog.dismiss(); selectQueueItem(index) }
                }
            }
        }
        dialog.show()
        rows.scrollToPosition(queueIndex)
        rows.post { rows.findViewHolderForAdapterPosition(queueIndex)?.itemView?.requestFocus() }
    }

    private class QueueVH(val text: android.widget.TextView) : androidx.recyclerview.widget.RecyclerView.ViewHolder(text)

    private fun chooseSpeed(p: ExoPlayer) {
        val labels = listOf("0.75x", "1x", "1.25x", "1.5x", "2x")
        val values = floatArrayOf(0.75f, 1f, 1.25f, 1.5f, 2f)
        val current = p.playbackParameters.speed
        val sel = values.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }.coerceAtLeast(0)
        Dialogs.choice(this, getString(R.string.playback_speed), labels, sel) { idx ->
            p.setPlaybackParameters(androidx.media3.common.PlaybackParameters(values[idx]))
        }
    }

    private fun chooseResize() {
        val labels = listOf(
            getString(R.string.resize_fit),
            getString(R.string.resize_fill),
            getString(R.string.resize_zoom),
        )
        val modes = intArrayOf(
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT,
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL,
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        )
        val sel = modes.indexOf(playerView.resizeMode).coerceAtLeast(0)
        Dialogs.choice(this, getString(R.string.resize_mode), labels, sel) { idx ->
            playerView.resizeMode = modes[idx]
        }
    }

    private var sleepRunnable: Runnable? = null
    private var idleRunnable: Runnable? = null
    private var lastInteractionAt = 0L

    /** Таймаут выключения экрана, заданный в системе (мс); 0 — «не выключать». */
    private fun systemScreenOffMs(): Long = runCatching {
        android.provider.Settings.System.getInt(
            contentResolver, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, 0).toLong()
    }.getOrDefault(0L)

    /**
     * Синхронизация с таймером Android: пауза после стольких же минут бездействия,
     * на сколько стоит системный таймаут экрана. Как и системный, таймер
     * сбрасывается любым нажатием на пульте.
     */
    private fun startSystemSleepSync() {
        stopSystemSleepSync()
        val timeoutMs = systemScreenOffMs().coerceAtLeast(60_000L)
        lastInteractionAt = android.os.SystemClock.elapsedRealtime()
        val r = object : Runnable {
            override fun run() {
                if (android.os.SystemClock.elapsedRealtime() - lastInteractionAt >= timeoutMs) {
                    player?.pause()
                    Toast.makeText(this@PlayerActivity, getString(R.string.sleep_done), Toast.LENGTH_LONG).show()
                } else {
                    playerView.postDelayed(this, 10_000L)
                }
            }
        }
        idleRunnable = r
        playerView.postDelayed(r, 10_000L)
    }

    private fun stopSystemSleepSync() {
        idleRunnable?.let { playerView.removeCallbacks(it) }
        idleRunnable = null
    }

    private fun chooseSleepTimer() {
        val minutes = listOf(15, 30, 60, 90)
        // kind: 0 — выкл, 1 — синхронно с таймером Android, 2 — фиксированные минуты
        class Opt(val kind: Int, val minutes: Int)
        val systemMs = systemScreenOffMs()
        val opts = mutableListOf(Opt(0, 0))
        if (systemMs > 0) opts += Opt(1, ((systemMs + 59_999) / 60_000L).toInt().coerceAtLeast(1))
        minutes.forEach { opts += Opt(2, it) }
        val labels = opts.map {
            when (it.kind) {
                0 -> getString(R.string.off)
                1 -> getString(R.string.sleep_sync, it.minutes)
                else -> getString(R.string.sleep_set, it.minutes)
            }
        }
        val sel = if (prefs.sleepSyncSystem && opts.any { it.kind == 1 }) 1 else 0
        Dialogs.choice(this, getString(R.string.sleep_timer), labels, sel) { idx ->
            when (opts[idx].kind) {
                0 -> {
                    prefs.sleepSyncSystem = false
                    stopSystemSleepSync()
                    setSleepTimer(0)
                }
                1 -> {
                    prefs.sleepSyncSystem = true
                    sleepRunnable?.let { playerView.removeCallbacks(it) }
                    sleepRunnable = null
                    startSystemSleepSync()
                    Toast.makeText(this, getString(R.string.sleep_sync_set, opts[idx].minutes), Toast.LENGTH_LONG).show()
                }
                else -> {
                    prefs.sleepSyncSystem = false
                    stopSystemSleepSync()
                    setSleepTimer(opts[idx].minutes)
                }
            }
        }
    }

    private fun setSleepTimer(minutes: Int) {
        sleepRunnable?.let { playerView.removeCallbacks(it) }
        sleepRunnable = null
        if (minutes <= 0) {
            Toast.makeText(this, getString(R.string.sleep_off), Toast.LENGTH_SHORT).show()
            return
        }
        val r = Runnable {
            player?.pause()
            Toast.makeText(this, getString(R.string.sleep_done), Toast.LENGTH_LONG).show()
        }
        sleepRunnable = r
        playerView.postDelayed(r, minutes * 60_000L)
        Toast.makeText(this, getString(R.string.sleep_set, minutes), Toast.LENGTH_SHORT).show()
    }

    // ---------- жизненный цикл ----------

    override fun onStart() {
        super.onStart()
        if (queueReady && player == null && !waitingForEmbed) openCurrent(false)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        prepareJob?.cancel(); releasePlayer()
        setIntent(intent); readIntent()
        waitingForEmbed = false; pendingEmbedResult = null
        queueReady = false; currentItem = null; playbackCompleted = false; playingBeforeStop = true
        loadQueue(intent.getIntExtra("queueIndex", 0))
    }

    private fun rememberRoute() {
        if (currentItem == null) return
        LastScreen.save(this, PlayerActivity::class.java, org.json.JSONObject()
            .put("itemId", itemId).put("kind", kind).put("title", title).put("url", url)
            .put("dur", duration).put("queueKey", queueKey).put("queueIndex", queueIndex)
            .put("pageUrl", currentItem?.pageUrl).put("referer", currentItem?.referer)
            .put("playKind", currentItem?.playKind?.name)
            .put("allowEmbed", intent.getBooleanExtra("allowEmbed", true))
            .put("preferEmbed", intent.getBooleanExtra("preferEmbed", false))
            .apply { subtitle?.let { put("subtitle", it) }; poster?.let { put("poster", it) } })
    }

    override fun onResume() {
        super.onResume()
        // процесс могли убить в фоне — запись восстанавливается при возврате
        // ([[LastScreen]]); позицию подставляет HomeActivity из места просмотра
        rememberRoute()
        // после возврата в приложение таймер сна начинается заново
        if (prefs.sleepSyncSystem) startSystemSleepSync()
        // избранное могли изменить на других экранах — обновляем состояние
        refreshFavState()
    }

    override fun onPause() {
        super.onPause()
        saveCurrent()
    }

    override fun onStop() {
        prepareJob?.cancel()
        releasePlayer()
        stopSystemSleepSync()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("position", player?.currentPosition ?: startPos)
        outState.putBoolean("playing", player?.playWhenReady ?: playingBeforeStop)
        outState.putInt("queueIndex", queueIndex)
        outState.putInt("quality", qualityHeight)
        outState.putBoolean("waitingEmbed", waitingForEmbed)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        saveRunnable?.let { playerView.removeCallbacks(it) }
        sleepRunnable?.let { playerView.removeCallbacks(it) }
        stopSystemSleepSync()
        prepareJob?.cancel()
        releasePlayer()
        persistenceJob.complete()
        super.onDestroy()
    }
}
