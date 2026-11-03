package com.astraplay.tv.data.torrent

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class TorrServerClientTest {
    @Test fun validatesTorrentInputsAndPreservesFileIndexAndName() {
        val hash = "0123456789abcdef0123456789abcdef01234567"
        assertTrue(TorrServerClient.validLink("magnet:?xt=urn:btih:$hash&dn=Test"))
        assertFalse(TorrServerClient.validLink("magnet:?xt=urn:btih:not-a-hash"))
        assertTrue(TorrServerClient.validLink("https://example.com/video.torrent?token=test"))
        val url = TorrServerClient.streamAddress("http://127.0.0.1:8090", hash, 4, "folder/Серия 4.mkv").toHttpUrl()
        assertEquals(hash, url.queryParameter("link"))
        assertEquals("4", url.queryParameter("index"))
        assertEquals("Серия 4.mkv", url.pathSegments.last())
        assertNotNull(url.queryParameter("play"))
    }
}
