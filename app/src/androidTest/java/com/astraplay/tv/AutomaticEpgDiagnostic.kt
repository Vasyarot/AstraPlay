package com.astraplay.tv

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.astraplay.tv.core.Prefs
import com.astraplay.tv.data.iptv.*
import com.astraplay.tv.data.net.Http
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutomaticEpgDiagnostic {
    @Test fun publicGuideMatchesRussianPlaylistChannelsWithoutAUserUrl() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("autoEpgCheck") == "1")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val epg = EpgStore(Http(Prefs(context)))
        val channels = listOf(
            Channel(name = "NTV (Russia) (576p)", url = "https://example.invalid/ntv", epgId = "NTV.ru@SD"),
            Channel(name = "Rossiya 1 (576p)", url = "https://example.invalid/russia", epgId = "Russia1.ru@SD"),
            Channel(name = "Pervyy kanal (576p)", url = "https://example.invalid/one", epgId = "ChannelOne.ru@SD"))
        val result = epg.ensureForChannels(channels)
        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
        Log.i("AstraEpgCheck", "Source: ${epg.sourceUrl}")
        val now = System.currentTimeMillis()
        val matched = channels.count { channel ->
            val rows = epg.forChannel(channel.epgId, channel.name, now, now + 6 * 3600_000L)
            Log.i("AstraEpgCheck", "${channel.name}: ${rows.size} programmes")
            rows.isNotEmpty()
        }
        assertTrue("No playlist channels matched", matched > 0)
    }
}
