package com.astraplay.tv.data.sources

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class PublicVideoMetadataTest {
    @Test fun movieReadsRealMetadataAndChoosesVideoInsteadOfAdvertisingFrame() {
        val doc = Jsoup.parse("""<h1>Fallback</h1><iframe src='https://ads.test/pop'></iframe>
            <script type='application/ld+json'>{"@type":"Movie","name":"Фильм","datePublished":"2025-03-02",
            "description":"Описание фильма","genre":["Драма"],"duration":"PT1H37M","image":"/poster.jpg",
            "actor":[{"name":"Актёр"}],"video":{"@type":"VideoObject","contentUrl":"/film.m3u8"}}</script>""", "https://site.test/movie")
        val d = GenericDetailsParser("filmix").parse(doc)
        assertEquals("Фильм", d.title)
        assertEquals("2025", d.year)
        assertEquals(97, d.durationMin)
        assertEquals("https://site.test/poster.jpg", d.poster)
        assertEquals("https://site.test/film.m3u8", d.play!!.url)
        assertEquals(SourcePlayKind.DIRECT, d.play!!.kind)
        assertFalse(d.isSeries)
    }

    @Test fun seriesKeepsSeasonAndEpisodeTypesWithoutGuessingProtectedStreams() {
        val doc = Jsoup.parse("""<script type='application/ld+json'>{"@type":"TVSeries","name":"Сериал",
            "episode":[{"@type":"TVEpisode","name":"Вторая серия","episodeNumber":2,"partOfSeason":{"seasonNumber":3},
            "url":"/episode/2"},{"@type":"TVEpisode","name":"Первая серия","episodeNumber":1,
            "partOfSeason":{"seasonNumber":3},"video":{"@type":"VideoObject","contentUrl":"https://cdn.test/1.mp4"}}]}</script>""", "https://site.test/series/test")
        val d = GenericDetailsParser().parse(doc)
        assertTrue(d.isSeries)
        assertEquals(3, d.seasons.single().number)
        val episodes = d.seasons.single().episodes
        assertEquals(listOf(1, 2), episodes.map { it.number })
        assertEquals(SourcePlayKind.DIRECT, episodes[0].playKind)
        assertEquals(SourcePlayKind.PAGE, episodes[1].playKind)
    }

    @Test fun unsafeAndAdvertisementLinksNeverBecomeNativeVideo() {
        val doc = Jsoup.parse("""<h1>Сериал</h1><video src='javascript:evil()'></video><iframe src='https://ad.test/frame'></iframe>
            <div class='episodes'><a href='https://ad.test/page'>1 серия</a><a href='/episode/2'>2 серия</a></div>""", "https://site.test/show")
        assertTrue(PublicVideoMetadata.media(doc).isEmpty())
        assertEquals("https://site.test/episode/2", GenericDetailsParser().parse(doc).seasons.single().episodes.single().url)
    }

    @Test fun resolverUsesPublicVideoSourceAndRefererWithoutLaunchingWebView() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("https://site.test/movie", chain.request().header("Referer"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("<video><source src='/v/main.m3u8'></video>".toResponseBody("text/html".toMediaType())).build()
        }.build()
        val resolved = SourceDetailsLoader(client, "Test").resolve(SourcePlayOption("Test", SourcePlayKind.EMBED,
            "https://player.test/embed/1", "https://site.test/movie"))
        assertEquals(SourcePlayKind.DIRECT, resolved.kind)
        assertEquals("https://player.test/v/main.m3u8", resolved.url)
    }

    @Test fun plainSeasonvarMediaIsPlayableButEncryptedPlaylistEntryIsNotGuessed() {
        val eps = SeasonvarDetailsParser().episodesFromPlaylist("""[{"title":"1 серия","file":"https://cdn.test/1.mp4"},
            {"title":"2 серия","file":"#2encrypted-data"}]""")
        assertEquals("https://cdn.test/1.mp4", eps[0].url)
        assertNull(eps[1].url)
    }

    @Test fun publicSchedulePreservesSeasonNumbersAndDoesNotInventVideoLinks() {
        val seasons = SeasonvarDetailsParser().scheduleFromHtml("""<div class='seasonvar-page__as-item'>
            <div class='seasonvar-page__as-caption'>Сезон 2</div><ul><li><span>Эпизод 1</span>, 02.11.2026</li></ul></div>
            <div class='seasonvar-page__as-item'><div class='seasonvar-page__as-caption'>Сезон 1</div>
            <ul><li><span>Эпизод 3</span>, 01.11.2026</li></ul></div>""")
        assertEquals(listOf(1, 2), seasons.map { it.number })
        assertEquals(3, seasons.first().episodes.single().number)
        assertNull(seasons.first().episodes.single().url)
    }

    @Test fun microdataPosterAndEmbeddedPlayerAreRecognizedWithoutAdvertisingFrames() {
        val doc = Jsoup.parse("""<h1>Фильм</h1><meta property='og:image' content='/logo.png'>
            <img itemprop='image' src='/poster.jpg'><meta itemprop='duration' content='PT46M15S'>
            <div class='player-main-block'><iframe itemprop='video' src='https://player.test/embed'></iframe></div>
            <iframe src='https://ads.test'></iframe>""", "https://site.test/movie")
        val d = GenericDetailsParser("filmix").parse(doc)
        assertEquals("https://site.test/poster.jpg", d.poster)
        assertEquals(46, d.durationMin)
        assertEquals(SourcePlayKind.EMBED, d.play!!.kind)
        assertEquals("https://player.test/embed", d.play!!.url)
    }

    @Test fun smotrimMovieReadsItsOwnEpisodeFromPublicSsrState() {
        val state = """[{"brand-episodes-42":1},{"list":2},[3],{"episodes":4,"title":11},{"list":5},[6],
            {"publicId":7,"title":8,"number":9,"durationSeconds":10},12345,"Тестовый фильм",1,120,""]"""
        val doc = Jsoup.parse("""<meta property='og:title' content='Фильм Тестовый фильм (2026) — смотреть'>
            <script id='__NUXT_DATA__' type='application/json'>$state</script>""", "https://smotrim.ru/brand/42")
        val d = SmotrimDetailsParser().parse(doc)
        assertFalse(d.isSeries)
        assertTrue(d.seasons.isEmpty())
        assertEquals("https://embed.smotrim.ru/iframe/video/id/12345", d.play!!.url)
    }
}
