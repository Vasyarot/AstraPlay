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
import com.astraplay.tv.core.ServiceLocator
import com.astraplay.tv.data.db.Kinds
import com.astraplay.tv.data.db.MediaEntry
import com.astraplay.tv.ui.common.Dialogs
import com.astraplay.tv.ui.theme.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Универсальный плеер (Media3/ExoPlayer): IPTV-HLS, локальные файлы,
 * прямые mp4. Resume-позиции, буфер по настройке, дорожки звука/субтитров,
 * автопереход к следующей серии.
 */
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

    private val store get() = ServiceLocator.store
    private val prefs get() = ServiceLocator.prefs

    /** Видим ли контроллер (для поведения D-pad влево/вправо). */
    private var controllerVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)

        readIntent()

        playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            useController = true
            controllerShowTimeoutMs = 4000
            controllerHideOnTouch = true
            setControllerVisibilityListener(object : PlayerView.ControllerVisibilityListener {
                override fun onVisibilityChanged(visibility: Int) {
                    controllerVisible = visibility == android.view.View.VISIBLE
                }
            })
            setShowSubtitleButton(true)
        }
        setContentView(playerView)

        initPlayer()
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

        val bufferMs = prefs.hlsBufferSecs * 1000
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ bufferMs,
                /* maxBufferMs = */ bufferMs + 15_000,
                /* bufferForPlaybackMs = */ 2_500,
                /* bufferForPlaybackAfterRebufferMs = */ 5_000,
            )
            .build()

        player = ExoPlayer.Builder(this, DefaultRenderersFactory(this))
            .setLoadControl(loadControl)
            .build()
            .apply {
                setWakeMode(C.WAKE_MODE_NETWORK)
                addListener(playerListener)
                setMediaItem(
                    MediaItem.Builder()
                        .setUri(url)
                        .setMediaMetadata(
                            androidx.media3.common.MediaMetadata.Builder()
                                .setTitle(title)
                                .setSubtitle(subtitle)
                                .build())
                        .build())
                // resume, если позиция осмысленная
                if (startPos > 5_000 && (duration <= 0 || startPos < duration - 30_000)) {
                    seekTo(startPos)
                }
                prepare()
                play()
            }
        playerView.player = player
        startSaving()
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            Toast.makeText(this@PlayerActivity,
                getString(R.string.network_error), Toast.LENGTH_LONG).show()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                lifecycleScope.launch(Dispatchers.IO) {
                    store.savePosition(currentEntry(positionMs = 0))
                    store.addHistory(currentEntry())
                }
                // автопереход к следующей серии
                if (prefs.autoplayNext && !nextUrl.isNullOrBlank()) {
                    runOnUiThread { playNext() }
                } else {
                    finish()
                }
            }
        }
    }

    // Периодическое сохранение позиции
    private var saveRunnable: Runnable? = null

    private fun startSaving() {
        val r = object : Runnable {
            override fun run() {
                val p = player
                if (p != null && p.isPlaying) {
                    val pos = p.currentPosition
                    val dur = p.duration.takeIf { it > 0 } ?: 0L
                    lifecycleScope.launch(Dispatchers.IO) {
                        store.savePosition(currentEntry(pos, dur))
                    }
                }
                playerView.postDelayed(this, 5_000)
            }
        }
        saveRunnable = r
        playerView.postDelayed(r, 5_000)
    }

    private fun currentEntry(positionMs: Long = player?.currentPosition ?: 0L,
                             durationMs: Long = player?.duration?.takeIf { it > 0 } ?: duration): MediaEntry =
        MediaEntry(
            kind = kind, itemId = itemId, title = title, subtitle = subtitle,
            poster = poster, url = url, positionMs = positionMs, durationMs = durationMs)

    private fun playNext() {
        player?.release()
        player = null
        // превращаем «next» в текущие данные
        itemId = nextItemId ?: ""
        title = nextTitle ?: title
        url = nextUrl ?: return
        startPos = 0
        duration = 0
        nextUrl = null; nextTitle = null; nextItemId = null
        initPlayer()
    }

    // ---------- управление с пульта ----------

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val p = player ?: return super.onKeyDown(keyCode, event)
        val step = prefs.seekStepSecs * 1000L
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (!controllerVisible) {
                    p.seekTo((p.currentPosition - step).coerceAtLeast(0)); true
                } else super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (!controllerVisible) {
                    p.seekTo(p.currentPosition + step); true
                } else super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_MENU -> {
                showPlayerMenu(); true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (controllerVisible) playerView.hideController()
                finish(); true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    /** Главное меню плеера: дорожки, скорость, кадр, таймер сна. */
    private fun showPlayerMenu() {
        val p = player ?: return
        Dialogs.choice(this, getString(R.string.player_menu),
            listOf(
                getString(R.string.audio_track),
                getString(R.string.subtitles),
                getString(R.string.playback_speed),
                getString(R.string.resize_mode),
                getString(R.string.sleep_timer),
            ), 0) { which ->
            when (which) {
                0 -> TrackSelectionDialogBuilder(this, getString(R.string.audio_track), p, C.TRACK_TYPE_AUDIO)
                    .build().show()
                1 -> TrackSelectionDialogBuilder(this, getString(R.string.subtitles), p, C.TRACK_TYPE_TEXT)
                    .build().show()
                2 -> chooseSpeed(p)
                3 -> chooseResize()
                4 -> chooseSleepTimer()
            }
        }
    }

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

    private fun chooseSleepTimer() {
        val minutes = listOf(0, 15, 30, 60, 90)
        val labels = mutableListOf(getString(R.string.off))
        minutes.drop(1).forEach { labels += getString(R.string.sleep_set, it) }
        Dialogs.choice(this, getString(R.string.sleep_timer), labels, 0) { idx ->
            setSleepTimer(minutes[idx])
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

    override fun onPause() {
        super.onPause()
        val p = player
        if (p != null && p.isPlaying) {
            val pos = p.currentPosition
            val dur = p.duration.takeIf { it > 0 } ?: 0L
            lifecycleScope.launch(Dispatchers.IO) {
                store.savePosition(currentEntry(pos, dur))
            }
        }
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        saveRunnable?.let { playerView.removeCallbacks(it) }
        sleepRunnable?.let { playerView.removeCallbacks(it) }
        val p = player
        if (p != null) {
            val pos = p.currentPosition
            val dur = p.duration.takeIf { it > 0 } ?: 0L
            lifecycleScope.launch(Dispatchers.IO) {
                store.savePosition(currentEntry(pos, dur))
                store.addHistory(currentEntry())
            }
            p.release()
            player = null
        }
        playerView.player = null
        super.onDestroy()
    }
}
