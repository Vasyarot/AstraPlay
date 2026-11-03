package com.astraplay.tv

import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.astraplay.tv.core.ServiceLocator
import com.astraplay.tv.data.iptv.Channel
import com.astraplay.tv.player.LivePlayerActivity
import com.astraplay.tv.ui.common.PosterCard
import com.astraplay.tv.ui.home.HomeActivity
import com.astraplay.tv.ui.iptv.IptvActivity
import com.astraplay.tv.ui.movies.SourcesActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TvNavigationTest {
    private var originalAutoEpg = true
    @org.junit.Before fun disableExternalEpgForUiTests() {
        originalAutoEpg = ServiceLocator.prefs.autoEpg
        ServiceLocator.prefs.autoEpg = false
    }
    @org.junit.After fun restoreAutoEpg() { ServiceLocator.prefs.autoEpg = originalAutoEpg }
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun texts(view: View): List<String> =
        (if (view is TextView) listOf(view.text.toString()) else emptyList()) +
            (if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList())

    private fun find(view: View, text: String): TextView? {
        if (view is TextView && view.text.toString() == text) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), text)?.let { return it }
        return null
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val path = File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png")
        path.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun homeHasRussianNavigationAndNoAppsSection() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val labels = texts(activity.window.decorView)
                assertTrue(labels.contains("Фильмы и сериалы"))
                assertTrue(find(activity.window.decorView, "Фильмы и сериалы")!!.hasFocus())
                assertTrue(labels.contains("Телеканалы"))
                assertFalse(labels.contains("Приложения"))
                val card = PosterCard(activity)
                card.applyMode(PosterCard.MODE_POSTER)
                card.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY))
                card.layout(0, 0, 200, 320)
                val titleTop = card.label.top
                card.setFocusedStyle(true)
                assertEquals(titleTop, card.label.top)
                assertEquals(1f, card.scaleY)
                assertTrue(card.label.visibility == View.VISIBLE)
                assertTrue(card.label.top >= card.getChildAt(0).bottom)
            }
            screenshot("home-v14")
        }
    }

    @Test fun sourceSelectionIncludesUserRequestedProviders() {
        ActivityScenario.launch(SourcesActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> find(activity.window.decorView, "Сериалы")!!.performClick() }
            scenario.onActivity {
                val labels = texts(it.window.decorView).joinToString("\n")
                assertTrue(labels.contains("LostFilm.tv"))
                assertTrue(labels.contains("Seasonvar"))
                assertTrue(labels.contains("HDRezka"))
                assertTrue(labels.contains("KinoDB\nНужен адрес"))
            }
            screenshot("sources-v14")
        }
    }

    @Test fun iptvSortingAndGroupsAreReachable() {
        ActivityScenario.launch(IptvActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(find(activity.window.decorView, "Группы"))
                find(activity.window.decorView, "Сортировка")!!.performClick()
            }
            screenshot("iptv-sort-v14")
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        }
    }

    @Test fun livePlayerSwitchesInsideTheCurrentPlaylist() {
        val context = instrumentation.targetContext
        // A deliberately unavailable stream avoids external playback dependencies.
        val store = ServiceLocator.store
        val id = store.addPlaylist("Тест навигации", "https://example.invalid/test.m3u", "url")
        val rows = listOf(
            Channel(name = "Первый тестовый", url = "http://127.0.0.1:1/one.m3u8", group = "Family Kids"),
            Channel(name = "Второй тестовый", url = "http://127.0.0.1:1/two.m3u8", group = "Animation"))
        store.replaceChannels(id, rows)
        try {
            val intent = Intent(context, LivePlayerActivity::class.java).putExtra("playlistId", id)
                .putExtra("url", rows[0].url).putExtra("title", rows[0].name)
            ActivityScenario.launch<LivePlayerActivity>(intent).use { scenario ->
                instrumentation.waitForIdleSync()
                Thread.sleep(500)
                scenario.onActivity { activity -> find(activity.window.decorView, "Следующий")!!.performClick() }
                scenario.onActivity { activity ->
                    assertTrue(texts(activity.window.decorView).any { it.contains("2 / 2") && it.contains("Второй тестовый") })
                    find(activity.window.decorView, "Каналы")!!.performClick()
                }
                screenshot("live-channels-v14")
                scenario.onActivity { activity ->
                    val labels = texts(activity.window.decorView).joinToString("\n")
                    assertTrue(labels.contains("1. Первый тестовый"))
                    assertTrue(labels.contains("2. Второй тестовый"))
                    activity.onBackPressed() // drawer closes; playback remains open
                    activity.onBackPressed() // playback panel closes; video remains open
                    assertFalse(activity.isFinishing)
                    assertFalse(find(activity.window.decorView, "Следующий")!!.isShown)
                }
            }
        } finally { store.deletePlaylist(id) }
    }

    @Test fun longChannelListWrapsAndNeverLosesDpadControl() {
        val store = ServiceLocator.store
        val id = store.addPlaylist("Тест длинного списка", "https://example.invalid/long.m3u", "url")
        val rows = (1..80).map { Channel(name = "Канал $it", url = "http://127.0.0.1:1/$it.m3u8") }
        store.replaceChannels(id, rows)
        try {
            val intent = Intent(instrumentation.targetContext, LivePlayerActivity::class.java)
                .putExtra("playlistId", id).putExtra("url", rows[0].url).putExtra("title", rows[0].name)
            ActivityScenario.launch<LivePlayerActivity>(intent).use { scenario ->
                Thread.sleep(300)
                scenario.onActivity { activity ->
                    assertTrue(find(activity.window.decorView, "Каналы")!!.hasFocus())
                    find(activity.window.decorView, "Каналы")!!.performClick()
                    assertFalse(texts(activity.window.decorView).contains("Закрыть список"))
                }
                repeat(80) { instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN) }
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                scenario.onActivity { activity -> assertTrue(texts(activity.window.decorView).any { it.contains("1 / 80") }) }
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_MENU)
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_UP)
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                scenario.onActivity { activity -> assertTrue(texts(activity.window.decorView).any { it.contains("80 / 80") && it.contains("Канал 80") }) }
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                scenario.onActivity { activity -> assertFalse(find(activity.window.decorView, "Каналы")!!.isShown); assertFalse(activity.isFinishing) }
            }
        } finally { store.deletePlaylist(id) }
    }
}
