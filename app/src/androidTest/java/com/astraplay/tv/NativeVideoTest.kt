package com.astraplay.tv

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.astraplay.tv.core.DiskJsonCache
import com.astraplay.tv.core.ServiceLocator
import com.astraplay.tv.data.db.Kinds
import com.astraplay.tv.data.sources.*
import com.astraplay.tv.player.PlayerActivity
import com.astraplay.tv.player.VideoItem
import com.astraplay.tv.ui.movies.SourceDetailsActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NativeVideoTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun player(activity: PlayerActivity): ExoPlayer? = PlayerActivity::class.java.getDeclaredField("player")
        .apply { isAccessible = true }.get(activity) as? ExoPlayer

    private fun waitReady(scenario: ActivityScenario<PlayerActivity>, index: Int = 0) {
        val deadline = System.currentTimeMillis() + 20000
        var ready = false
        while (!ready && System.currentTimeMillis() < deadline) {
            scenario.onActivity { activity ->
                val actual = PlayerActivity::class.java.getDeclaredField("queueIndex").apply { isAccessible = true }.getInt(activity)
                val p = player(activity)
                assertNull("Playback failed", p?.playerError)
                ready = actual == index && p?.playbackState == Player.STATE_READY
            }
            if (!ready) Thread.sleep(150)
        }
        assertTrue("Video did not become ready at queue index $index", ready)
    }

    @Test fun nativeQueueAutoAdvancesAndReleasesDecoderOnStop() {
        val clip = InstrumentationRegistry.getArguments().getString("clipUri")
        assumeTrue("Provide a local test video using -e clipUri", !clip.isNullOrBlank())
        val id = "native_video_test_${System.currentTimeMillis()}"
        val oldAutoplay = ServiceLocator.prefs.autoplayNext
        val oldSeekStep = ServiceLocator.prefs.seekStepSecs
        ServiceLocator.prefs.seekStepSecs = 10
        val lastRoute = context.getSharedPreferences("last_screen", 0).all
        ServiceLocator.prefs.autoplayNext = true
        val queue = listOf(VideoItem("${id}_1", "Тест · серия 1", clip!!, Kinds.LOCAL),
            VideoItem("${id}_2", "Тест · серия 2", clip, Kinds.LOCAL))
        val key = ServiceLocator.videoQueues.write(id, queue)
        try {
            ActivityScenario.launch<PlayerActivity>(Intent(context, PlayerActivity::class.java)
                .putExtra("queueKey", key).putExtra("url", clip)).use { scenario ->
                waitReady(scenario)
                scenario.onActivity { activity ->
                    val p = player(activity)!!
                    p.pause()
                    p.seekTo(20000)
                    activity.findViewById<androidx.media3.ui.PlayerView>(R.id.player_view).showController()
                }
                Thread.sleep(700)
                scenario.onActivity { activity ->
                    val bar = activity.findViewById<androidx.media3.ui.DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)
                    bar.requestFocus()
                    bar.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT))
                    bar.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_RIGHT))
                    bar.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_CENTER))
                }
                Thread.sleep(300)
                scenario.onActivity { activity ->
                    val p = player(activity)!!
                    assertTrue("Timeline must seek by 10 seconds, independently of movie length: ${p.currentPosition}", kotlin.math.abs(p.currentPosition - 30000L) <= 1200L)
                    assertEquals(10000L, p.seekBackIncrement)
                    assertEquals(10000L, p.seekForwardIncrement)
                    val button = activity.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)
                    val position = IntArray(2)
                    button.getLocationOnScreen(position)
                    assertTrue("Playback controls must be at the bottom", position[1] > activity.window.decorView.height / 2)
                    p.play()
                    assertTrue("Test clip must have audio", p.currentTracks.containsType(androidx.media3.common.C.TRACK_TYPE_AUDIO))
                    assertTrue(p.duration > 10000)
                    assertEquals(720, p.trackSelectionParameters.maxVideoHeight)
                    android.util.Log.i("NativeVideoCheck", "Playing PSS=${android.os.Debug.getPss()} KiB; codec=${p.videoFormat?.sampleMimeType}; format=${p.videoFormat?.width}x${p.videoFormat?.height}")
                    instrumentation.sendStatus(2, android.os.Bundle().apply { putString("videoMetrics", "PSS=${android.os.Debug.getPss()} KiB; ${p.videoFormat?.width}x${p.videoFormat?.height}; ${p.videoFormat?.sampleMimeType}") })
                    p.seekTo(p.duration - 600)
                }
                waitReady(scenario, 1)
                scenario.onActivity { player(it)!!.seekTo(15000) }
                Thread.sleep(500)
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.onActivity { assertNull("Decoder must be released in background", player(it)) }
                scenario.moveToState(Lifecycle.State.RESUMED)
                waitReady(scenario, 1)
                scenario.onActivity { activity ->
                    assertTrue("Position lost on resume", player(activity)!!.currentPosition >= 14000)
                }
                scenario.recreate()
                waitReady(scenario, 1)
                scenario.onActivity { activity ->
                    assertTrue("Position lost after recreation", player(activity)!!.currentPosition >= 14000)
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    activity.findViewById<androidx.media3.ui.PlayerView>(R.id.player_view).showController()
                    activity.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)?.requestFocus()
                }
                Thread.sleep(400)
                scenario.onActivity { activity ->
                    assertTrue("Native controller is hidden", activity.findViewById<View>(androidx.media3.ui.R.id.exo_controller).isShown)
                    assertTrue("Player title is hidden", activity.findViewById<View>(R.id.player_title).isShown)
                }
                screenshot("native-player")
            }
        } finally {
            ServiceLocator.prefs.autoplayNext = oldAutoplay
            ServiceLocator.prefs.seekStepSecs = oldSeekStep
            // Queued persistence completes off the UI thread; wait before removing test-only entries.
            Thread.sleep(600)
            queue.forEach { ServiceLocator.store.removeHistory(it.kind, it.itemId); ServiceLocator.store.deletePosition(it.itemId); ServiceLocator.store.setWatched(it.itemId, it.kind, it.title, false) }
            File(context.filesDir, "video_queues/$key").delete()
            restoreRoute(lastRoute)
        }
    }

    @Test fun thousandEpisodesRemainVirtualizedWithoutWebView() {
        val url = "https://native-card-test.invalid/series/${System.currentTimeMillis()}"
        val details = SourceDetails("generic", "Тестовый источник", url, "Большой сериал",
            year = "2026", description = "Проверка нативной карточки и длинного списка серий.", isSeries = true,
            seasons = listOf(SourceSeason(1, "Сезон 1", episodes = (1..1000).map {
                SourceEpisode(it, "Серия $it", "https://media.invalid/$it.mp4", playKind = SourcePlayKind.DIRECT)
            })))
        val cache = DiskJsonCache(File(context.cacheDir, "source_details"))
        cache.write("native-video-v2|generic|$url", JSONObject().put("at", System.currentTimeMillis())
            .put("d", JSONObject(SourceDetailsJson.write(details))).toString())
        val lastRoute = context.getSharedPreferences("last_screen", 0).all
        try {
            ActivityScenario.launch<SourceDetailsActivity>(Intent(context, SourceDetailsActivity::class.java)
                .putExtra("url", url).putExtra("title", details.title)).use { scenario ->
                Thread.sleep(1000)
                scenario.onActivity { activity ->
                    val recycler = findRecycler(activity.window.decorView)!!
                    assertEquals(1002, recycler.adapter!!.itemCount)
                    assertTrue("Episode rows were eagerly inflated", recycler.childCount < 25)
                    assertFalse("Description must not contain WebView", containsWebView(activity.window.decorView))
                }
                screenshot("native-series-header")
                scenario.onActivity { activity ->
                    val recycler = findRecycler(activity.window.decorView)!!
                    recycler.scrollToPosition(999)
                }
                Thread.sleep(300)
                screenshot("native-series-card")
            }
        } finally {
            restoreRoute(lastRoute)
        }
    }

    @Test fun movieCardShowsMetadataWithoutWebsite() {
        val url = "https://native-movie-test.invalid/movies/${System.currentTimeMillis()}"
        val details = SourceDetails("generic", "Источник фильма", url, "Фильм • нативная карточка",
            year = "2026", country = "Россия", genres = listOf("Приключения", "Комедия"),
            durationMin = 97, rating = "★ 8.1 · Источник фильма", description = "Описание выбранного фильма отображается в стиле AstraPlay. Скрипты сайта и рекламные блоки не загружаются.",
            director = "Режиссёр", actors = listOf("Первый актёр", "Второй актёр"),
            play = SourcePlayOption("Смотреть", SourcePlayKind.DIRECT,
                InstrumentationRegistry.getArguments().getString("clipUri") ?: "https://video.invalid/film.mp4"))
        DiskJsonCache(File(context.cacheDir, "source_details")).write("native-video-v2|generic|$url", JSONObject()
            .put("at", System.currentTimeMillis()).put("d", JSONObject(SourceDetailsJson.write(details))).toString())
        val lastRoute = context.getSharedPreferences("last_screen", 0).all
        var queueKey: String? = null
        val monitor = instrumentation.addMonitor(PlayerActivity::class.java.name, null, false)
        try {
            ActivityScenario.launch<SourceDetailsActivity>(Intent(context, SourceDetailsActivity::class.java)
                .putExtra("url", url).putExtra("title", details.title)).use { scenario ->
                Thread.sleep(700)
                scenario.onActivity { activity ->
                    assertFalse(containsWebView(activity.window.decorView))
                    assertEquals(1, findRecycler(activity.window.decorView)!!.adapter!!.itemCount)
                }
                screenshot("native-movie-card")
                scenario.onActivity { activity -> findText(activity.window.decorView, "Смотреть")!!.performClick() }
                val opened = instrumentation.waitForMonitorWithTimeout(monitor, 10000)
                assertTrue("Movie card must open native PlayerActivity, not another web card", opened is PlayerActivity)
                queueKey = opened.intent.getStringExtra("queueKey")
                instrumentation.runOnMainSync { opened.finish() }
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            Thread.sleep(600)
            val id = "web_${url.hashCode()}"
            ServiceLocator.store.removeHistory(Kinds.WEB, id); ServiceLocator.store.deletePosition(id)
            queueKey?.let { File(context.filesDir, "video_queues/$it").delete() }
            restoreRoute(lastRoute)
        }
    }

    private fun restoreRoute(values: Map<String, *>) {
        context.getSharedPreferences("last_screen", 0).edit().clear().apply {
            values.forEach { (key, value) -> when (value) { is String -> putString(key, value); is Long -> putLong(key, value) } }
        }.commit()
    }

    private fun findRecycler(view: View): RecyclerView? {
        if (view is RecyclerView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findRecycler(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun findText(view: View, value: String): android.widget.TextView? {
        if (view is android.widget.TextView && view.text.toString() == value) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findText(view.getChildAt(i), value)?.let { return it }
        return null
    }
    private fun containsWebView(view: View): Boolean = view is android.webkit.WebView ||
        (view is ViewGroup && (0 until view.childCount).any { containsWebView(view.getChildAt(it)) })

    private fun screenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
