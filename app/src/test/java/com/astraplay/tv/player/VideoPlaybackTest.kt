package com.astraplay.tv.player

import com.astraplay.tv.data.db.Kinds
import com.astraplay.tv.data.sources.SourcePlayKind
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VideoPlaybackTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun lowMemoryProfileBoundsBufferDespiteLargeUserSetting() {
        val p = PlaybackPolicy.forDevice(true, 120)
        assertEquals(12 * 1024 * 1024, p.bufferBytes)
        assertEquals(15000, p.minBufferMs)
        assertEquals(20000, p.maxBufferMs)
        assertEquals(720, p.maxHeight)
        assertEquals(5000, PlaybackPolicy.forDevice(true, -10).minBufferMs)
    }

    @Test fun queueRoundTripPreservesEpisodeIdentityAndOrigin() {
        val store = VideoQueueStore(directory.root)
        val items = listOf(
            VideoItem("s1e1", "Эпизод 1", "https://cdn.test/1.m3u8", Kinds.WEB, "Сезон 1",
                playKind = SourcePlayKind.DIRECT, pageUrl = "https://site.test/serial/1", referer = "https://site.test/serial/1"),
            VideoItem("s1e2", "Эпизод 2", "https://site.test/episode/2", Kinds.WEB, playKind = SourcePlayKind.PAGE))
        val key = store.write("season-1", items)
        assertEquals(items, store.read(key))
        val resumed = store.read(key).first().entry(45000, 90000)
        assertEquals("https://site.test/serial/1", resumed.url)
        assertEquals(45000, resumed.positionMs)
        assertNull(store.read(key)[1].poster)
    }

    @Test fun corruptAndUntrustedQueueKeysDoNotEscapeDirectory() {
        val store = VideoQueueStore(directory.root)
        assertTrue(store.read("../../secrets").isEmpty())
        java.io.File(directory.root, "a".repeat(64)).writeText("{broken")
        assertTrue(store.read("a".repeat(64)).isEmpty())
        assertFalse(VideoQueueStore.validUrl("javascript:alert(1)"))
        assertFalse(VideoQueueStore.validUrl("https://user:password@video.test/a.mp4"))
        assertTrue(VideoQueueStore.validUrl("content://media/external/video/1"))
    }

    @Test fun queueCacheHasBoundedFileCountAndKeepsCurrentQueue() {
        val store = VideoQueueStore(directory.root)
        var last = ""
        repeat(30) { i -> last = store.write("owner$i", listOf(VideoItem("$i", "Test", "https://v.test/$i.mp4", Kinds.LOCAL))) }
        assertTrue(directory.root.listFiles()!!.size <= 16)
        assertEquals("29", store.read(last).single().itemId)
    }
}
