package com.astraplay.tv

import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.astraplay.tv.core.ServiceLocator
import com.astraplay.tv.data.db.Kinds
import com.astraplay.tv.data.net.Http
import com.astraplay.tv.data.sources.*
import com.astraplay.tv.player.PlayerActivity
import com.astraplay.tv.player.VideoItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit online smoke test: real full-length Russian films, no test clip or WebView. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LiveRussianSourcesTest {
    @Test fun mosfilmPlaysNatively() = checkChannel("mosfilm_rt", "25963146")
    @Test fun lenfilmPlaysNatively() = checkChannel("lenfilm_rt", "1792500")

    @Test fun smotrimMovieAndSeriesPlayNatively() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val routes = context.getSharedPreferences("last_screen", 0)
        val oldRoute = routes.getString("last_screen_route", null)
        val oldPaused = routes.getLong("last_screen_paused_at", 0)
        val loader = ServiceLocator.sourceDetailsLoader
        val movie = runBlocking { loader.load("https://smotrim.ru/brand/76109", "smotrim", refresh = true) }
        val series = runBlocking { loader.load("https://smotrim.ru/brand/71804", "smotrim", refresh = true) }
        assertFalse("Film classified as series", movie.isSeries)
        assertTrue("Series has no episodes", series.isSeries)
        val episode = series.seasons.flatMap { it.episodes }.first { it.url != null }
        val id = "live_smotrim_${System.currentTimeMillis()}"
        val items = listOf(VideoItem("${id}_film", movie.title, movie.play!!.url, Kinds.WEB, playKind = movie.play.kind, pageUrl = movie.url),
            VideoItem("${id}_series", "${series.title}: ${episode.title}", episode.url!!, Kinds.WEB, playKind = episode.playKind, pageUrl = series.url))
        val key = ServiceLocator.videoQueues.write(id, items)
        try {
            ActivityScenario.launch<PlayerActivity>(Intent(context, PlayerActivity::class.java)
                .putExtra("queueKey", key).putExtra("url", items[0].url).putExtra("allowEmbed", false)).use { scenario ->
                items.forEachIndexed { index, item ->
                    if (index > 0) scenario.onActivity { activity ->
                        PlayerActivity::class.java.getDeclaredMethod("selectQueueItem", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, index)
                    }
                    val deadline = System.currentTimeMillis() + 45000
                    var ready = false
                    while (!ready && System.currentTimeMillis() < deadline) {
                        scenario.onActivity { activity ->
                            val p = player(activity)
                            assertNull("Smotrim native playback failed", p?.playerError)
                            ready = p?.playbackState == Player.STATE_READY && p.videoFormat != null
                        }
                        if (!ready) Thread.sleep(250)
                    }
                    assertTrue("Smotrim native stream not ready: ${item.title}", ready)
                    Thread.sleep(2500)
                    scenario.onActivity { activity ->
                        val p = player(activity)!!
                        assertTrue(p.currentPosition > 1000)
                        assertTrue(p.currentTracks.containsType(androidx.media3.common.C.TRACK_TYPE_AUDIO))
                        instrumentation.sendStatus(2, android.os.Bundle().apply {
                            putString("liveMovie", "Смотрим: ${item.title}; ${p.videoFormat?.width}x${p.videoFormat?.height}; ${p.videoFormat?.sampleMimeType}; PSS=${android.os.Debug.getPss()} KiB")
                        })
                        p.seekTo(45000)
                    }
                    Thread.sleep(3500)
                    scenario.onActivity { assertTrue(player(it)!!.currentPosition >= 45000); assertNull(player(it)!!.playerError) }
                }
            }
        } finally {
            Thread.sleep(600)
            items.forEach { ServiceLocator.store.removeHistory(it.kind, it.itemId); ServiceLocator.store.deletePosition(it.itemId) }
            File(context.filesDir, "video_queues/$key").delete()
            routes.edit().putString("last_screen_route", oldRoute).putLong("last_screen_paused_at", oldPaused).commit()
        }
    }

    private fun checkChannel(sourceId: String, channel: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val routePrefs = context.getSharedPreferences("last_screen", 0)
        val oldRoute = routePrefs.all
        val source = OnlineSource(sourceId, sourceId, "https://rutube.ru/channel/$channel/")
        val catalog = runBlocking { SourceCatalogLoader(ServiceLocator.sourceHttpClient, Http.USER_AGENT).load(source, "movies", totalTimeoutMs = 45000) }
        assertTrue("Empty movie catalog", catalog.entries.isNotEmpty())
        try {
            catalog.entries.take(2).forEachIndexed { index, entry ->
                val details = runBlocking { ServiceLocator.sourceDetailsLoader.load(entry.url, "rutube") }
                assertFalse(details.isSeries)
                assertTrue(details.durationMin >= 30)
                val resolved = runBlocking { ServiceLocator.sourceDetailsLoader.resolve(details.play!!) }
                assertEquals("No native stream for ${details.title}", SourcePlayKind.DIRECT, resolved.kind)
                val id = "live_russian_test_${sourceId}_$index"
                val item = VideoItem(id, details.title, entry.url, Kinds.WEB, playKind = SourcePlayKind.PAGE, pageUrl = entry.url)
                val key = ServiceLocator.videoQueues.write(id, listOf(item))
                try {
                    ActivityScenario.launch<PlayerActivity>(Intent(context, PlayerActivity::class.java)
                        .putExtra("queueKey", key).putExtra("url", entry.url).putExtra("allowEmbed", false)).use { scenario ->
                        val deadline = System.currentTimeMillis() + 45000
                        var ready = false
                        while (!ready && System.currentTimeMillis() < deadline) {
                            scenario.onActivity { activity ->
                                val p = player(activity)
                                assertNull("Native playback error", p?.playerError)
                                ready = p?.playbackState == Player.STATE_READY && p.videoFormat != null
                            }
                            if (!ready) Thread.sleep(250)
                        }
                        assertTrue("Native player not ready: ${details.title}", ready)
                        Thread.sleep(2500)
                        scenario.onActivity { activity ->
                            val p = player(activity)!!
                            assertTrue("Playback does not advance", p.currentPosition > 1000)
                            assertTrue(p.currentTracks.containsType(androidx.media3.common.C.TRACK_TYPE_AUDIO))
                            instrumentation.sendStatus(2, android.os.Bundle().apply {
                                putString("liveMovie", "${details.title}; ${p.videoFormat?.width}x${p.videoFormat?.height}; ${p.videoFormat?.sampleMimeType}; PSS=${android.os.Debug.getPss()} KiB")
                            })
                            p.seekTo(60000)
                        }
                        Thread.sleep(3500)
                        scenario.onActivity { assertTrue("Seek failed", player(it)!!.currentPosition >= 60000); assertNull(player(it)!!.playerError) }
                        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                            File(context.getExternalFilesDir(null), "live-$sourceId-$index.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                            bitmap.recycle()
                        }
                    }
                } finally {
                    Thread.sleep(600)
                    ServiceLocator.store.removeHistory(item.kind, id)
                    ServiceLocator.store.deletePosition(id)
                    File(context.filesDir, "video_queues/$key").delete()
                }
            }
        } finally {
            routePrefs.edit().clear().apply {
                oldRoute.forEach { (key, value) -> when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                } }
            }.commit()
        }
    }
    private fun player(activity: PlayerActivity) = PlayerActivity::class.java.getDeclaredField("player")
        .apply { isAccessible = true }.get(activity) as? ExoPlayer
}
