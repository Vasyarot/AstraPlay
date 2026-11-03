package com.astraplay.tv

import android.webkit.CookieManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.astraplay.tv.data.sources.SourceSession
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SourceSessionTest {
    @Test fun nativeCatalogUsesOnlyTheRequestedWebsitesSession() {
        val ready = CountDownLatch(1)
        lateinit var manager: CookieManager
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            manager = CookieManager.getInstance()
            manager.setAcceptCookie(true)
            manager.setCookie("https://astraplay-session-test.invalid/", "test_session=local-test; Path=/; Secure; HttpOnly") { ready.countDown() }
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        manager.flush()
        val session = SourceSession(manager)
        assertTrue(session.loadForRequest("https://astraplay-session-test.invalid/catalog".toHttpUrl()).any { it.name == "test_session" && it.value == "local-test" })
        assertFalse(session.loadForRequest("https://different-source.invalid/".toHttpUrl()).any { it.name == "test_session" })
        manager.setCookie("https://astraplay-session-test.invalid/", "test_session=; Max-Age=0; Path=/; Secure")
        manager.flush()
    }
}
