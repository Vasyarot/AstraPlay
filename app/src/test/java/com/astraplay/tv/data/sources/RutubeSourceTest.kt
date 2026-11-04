package com.astraplay.tv.data.sources

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RutubeSourceTest {
    private val id = "be1e1f4dc5b959d96fccca6b061ae409"
    @Test fun acceptsOnlyRutubeVideoPaths() {
        assertEquals(id, RutubeSource.videoId("https://rutube.ru/video/$id/"))
        assertEquals(id, RutubeSource.videoId(RutubeSource.embedUrl(id)))
        assertNull(RutubeSource.videoId("https://rutube.ru.evil.example/video/$id/"))
        assertNull(RutubeSource.videoId("https://rutube.ru/video/invalid/"))
        assertEquals("rutube", SourceDetailsParsers.detect("https://rutube.ru/video/$id/"))
    }
    @Test fun excludesTrailersAndLiveAudioFromMovies() {
        val rows = org.json.JSONArray()
        rows.put(JSONObject().put("id", id).put("title", "Фильм").put("duration", 5313))
        rows.put(JSONObject().put("id", "a".repeat(32)).put("title", "Трейлер").put("duration", 100))
        rows.put(JSONObject().put("id", "b".repeat(32)).put("title", "Эфир").put("duration", 5000).put("is_livestream", true))
        rows.put(JSONObject().put("id", "c".repeat(32)).put("title", "Без длительности"))
        assertEquals(listOf("Фильм"), RutubeSource.parseCatalog(JSONObject().put("results", rows)).map { it.title })
    }
    @Test fun metadataKeepsStablePageRatherThanExpiringStream() {
        val details = RutubeSource.parseDetails(JSONObject().put("title", "Свадьба в Малиновке (1967)").put("duration", 5313), id)
        assertEquals("1967", details.year)
        assertEquals(88, details.durationMin)
        assertEquals(SourcePlayKind.PAGE, details.play!!.kind)
        assertEquals("https://rutube.ru/video/$id/", details.play.url)
    }
    @Test fun nativeHlsRetainsOfficialFallback() {
        val options = JSONObject().put("video_balancer", JSONObject().put("m3u8", "https://media.example/film.m3u8?token=temporary"))
        val play = RutubeSource.parsePlayback(options, id)
        assertEquals(SourcePlayKind.DIRECT, play.kind)
        assertEquals(RutubeSource.embedUrl(id), play.fallbackUrl)
        assertEquals("https://rutube.ru/", play.referer)
    }
    @Test fun protectedOrMissingStreamUsesOfficialEmbed() {
        val options = JSONObject().put("is_drm", true).put("video_balancer", JSONObject().put("m3u8", "https://media.example/film.m3u8"))
        assertEquals(SourcePlayKind.EMBED, RutubeSource.parsePlayback(options, id).kind)
        assertEquals(SourcePlayKind.EMBED, RutubeSource.parsePlayback(JSONObject(), id).kind)
    }
}
