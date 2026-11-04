package com.astraplay.tv.data.sources

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SmotrimPlaybackTest {
    @Test fun recognizesOnlyOfficialVideoAddresses() {
        assertEquals("6192749", SmotrimPlayback.videoId("https://embed.smotrim.ru/iframe/video/id/6192749/sid/smotrim/"))
        assertEquals("6192749", SmotrimPlayback.videoId("https://smotrim.ru/video/6192749"))
        assertNull(SmotrimPlayback.videoId("https://smotrim.ru.evil.example/video/6192749"))
        assertNull(SmotrimPlayback.videoId("https://smotrim.ru/brand/6192749"))
    }
    @Test fun currentApiSelectsPublicHlsAndRetainsEmbed() {
        val response = JSONObject().put("status", "OK").put("data", JSONObject().put("streams",
            JSONObject().put("m3u8", "https://video.example/master.m3u8?token=short-lived")))
        val play = SmotrimPlayback.parse(response, "6192749")
        assertEquals(SourcePlayKind.DIRECT, play.kind)
        assertEquals("https://smotrim.ru/", play.referer)
        assertEquals(SmotrimPlayback.embedUrl("6192749"), play.fallbackUrl)
    }
    @Test fun restrictedResponsesDoNotGuessStreamUrls() {
        assertEquals(SourcePlayKind.EMBED, SmotrimPlayback.parse(JSONObject().put("status", "ERROR"), "1").kind)
        val video = JSONObject().put("drm", true).put("streams", JSONObject().put("m3u8", "https://video.example/a.m3u8"))
        assertEquals(SourcePlayKind.EMBED, SmotrimPlayback.parse(JSONObject().put("status", "OK").put("data", video), "1").kind)
    }
}
