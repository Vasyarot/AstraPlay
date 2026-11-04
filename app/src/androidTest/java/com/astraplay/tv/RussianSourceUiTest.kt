package com.astraplay.tv

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.astraplay.tv.data.sources.OnlineTitle
import com.astraplay.tv.ui.movies.SourcesActivity
import com.astraplay.tv.ui.movies.SourceDetailsActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RussianSourceUiTest {
    @Test fun realMovieCatalogSupportsDpadAndNativeDetails() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("source_sections", 0)
        val oldSource = prefs.getString("last_source", null)
        val routes = context.getSharedPreferences("last_screen", 0)
        val oldRoute = routes.getString("last_screen_route", null)
        val oldPaused = routes.getLong("last_screen_paused_at", 0)
        prefs.edit().putString("last_source", "mosfilm_rt").commit()
        try {
            var entry: OnlineTitle? = null
            ActivityScenario.launch(SourcesActivity::class.java).use { scenario ->
                val deadline = System.currentTimeMillis() + 45000
                while (entry == null && System.currentTimeMillis() < deadline) {
                    scenario.onActivity { activity ->
                        val grid = SourcesActivity::class.java.getDeclaredField("grid").apply { isAccessible = true }.get(activity) as? RecyclerView
                        val adapter = grid?.adapter
                        if (grid != null && adapter != null && adapter.itemCount > 0 && grid.childCount > 0) {
                            @Suppress("UNCHECKED_CAST")
                            val rows = adapter.javaClass.getDeclaredField("entries").apply { isAccessible = true }.get(adapter) as List<OnlineTitle>
                            entry = rows.first()
                            // Nested focus used to crash getChildAdapterPosition with LinearLayout.LayoutParams.
                            val card = grid.getChildAt(0) as ViewGroup
                            val child = card.getChildAt(card.childCount - 1)
                            child.isFocusable = true; child.isFocusableInTouchMode = true; child.requestFocus()
                            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
                        }
                    }
                    if (entry == null) Thread.sleep(250)
                }
                assertNotNull("Movie grid did not load", entry)
                screenshot("russian-catalog")
            }
            ActivityScenario.launch<SourceDetailsActivity>(Intent(context, SourceDetailsActivity::class.java)
                .putExtra("url", entry!!.url).putExtra("title", entry!!.title)).use { scenario ->
                var loaded = false
                val deadline = System.currentTimeMillis() + 20000
                while (!loaded && System.currentTimeMillis() < deadline) {
                    scenario.onActivity { activity ->
                        loaded = SourceDetailsActivity::class.java.getDeclaredField("details").apply { isAccessible = true }.get(activity) != null
                        assertFalse("Card contains a website", hasWebView(activity.window.decorView))
                    }
                    if (!loaded) Thread.sleep(200)
                }
                assertTrue("Movie card did not load", loaded)
                Thread.sleep(800)
                screenshot("russian-card")
            }
        } finally {
            prefs.edit().putString("last_source", oldSource).commit()
            routes.edit().putString("last_screen_route", oldRoute).putLong("last_screen_paused_at", oldPaused).commit()
        }
    }
    private fun hasWebView(view: View): Boolean = view is android.webkit.WebView ||
        (view is ViewGroup && (0 until view.childCount).any { hasWebView(view.getChildAt(it)) })
    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
