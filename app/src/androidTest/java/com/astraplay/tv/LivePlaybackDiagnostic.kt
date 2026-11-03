package com.astraplay.tv

import android.content.Intent
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.astraplay.tv.player.LivePlayerActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in network diagnostic: pass -e streamUrl URL when running instrumentation manually. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LivePlaybackDiagnostic {
    @Test fun suppliedStreamReachesPlaybackWithAudio() {
        val stream = InstrumentationRegistry.getArguments().getString("streamUrl")
        assumeTrue("A streamUrl must be supplied explicitly", !stream.isNullOrBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<LivePlayerActivity>(Intent(context, LivePlayerActivity::class.java)
            .putExtra("url", stream).putExtra("title", "Проверка потока")).use { scenario ->
            var ready = false
            var error: String? = null
            var hasAudio = false
            val deadline = System.currentTimeMillis() + 25000
            while (!ready && error == null && System.currentTimeMillis() < deadline) {
                scenario.onActivity { activity ->
                    val field = LivePlayerActivity::class.java.getDeclaredField("player").apply { isAccessible = true }
                    val player = field.get(activity) as? ExoPlayer
                    ready = player?.playbackState == Player.STATE_READY && player.isPlaying
                    error = player?.playerError?.let { "${it.errorCodeName}: ${it.cause}" }
                    hasAudio = player?.currentTracks?.isTypeSelected(C.TRACK_TYPE_AUDIO) == true
                    if (ready) Log.i("AstraPlaybackCheck", "Ready with audio=$hasAudio; format=${player?.audioFormat?.sampleMimeType}")
                }
                if (!ready && error == null) Thread.sleep(200)
            }
            assertTrue(error ?: "Stream did not start within 25 seconds", ready)
            assertTrue("No audio track selected", hasAudio)
        }
    }
}
