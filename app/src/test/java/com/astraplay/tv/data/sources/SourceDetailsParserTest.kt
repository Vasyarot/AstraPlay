package com.astraplay.tv.data.sources

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

/**
 * Парсеры нативных карточек проверяются на обезличенных HTML-примерах живых
 * страниц (app/src/test/resources/sources). Если источник меняет разметку,
 * пример обновляется с живой страницы и тест фиксирует новое правило.
 */
class SourceDetailsParserTest {

    private fun doc(name: String, base: String) =
        javaClass.getResourceAsStream("/sources/$name")!!.use {
            Jsoup.parse(it, "UTF-8", base)
        }

    // ---------- определение источника по адресу ----------

    @Test fun supportedCardAddressesAreDetected() {
        assertEquals("zona", SourceDetailsParsers.detect("https://g4.zona.plus/movies/chelovek-pauk-novyi-den"))
        assertEquals("zona", SourceDetailsParsers.detect("https://w140.zona.plus/tvseries/gangsterlend"))
        assertEquals("smotrim", SourceDetailsParsers.detect("https://smotrim.ru/brand/75257"))
        assertEquals("smotrim", SourceDetailsParsers.detect("https://smotrim.ru/video/4012679"))
        assertEquals("seasonvar", SourceDetailsParsers.detect("https://seasonvar.ru/serial-10063-VVS.html"))
        assertEquals("seasonvar", SourceDetailsParsers.detect("https://seasonvar.net/serial/12345-Test.html"))
    }

    @Test fun catalogAndForeignAddressesAreNotCards() {
        assertNull(SourceDetailsParsers.detect("https://g4.zona.plus/movies/"))
        assertNull(SourceDetailsParsers.detect("https://smotrim.ru/serials"))
        assertNull(SourceDetailsParsers.detect("https://seasonvar.ru/"))
        assertNull(SourceDetailsParsers.detect("https://example.com/movies/foo"))
        assertNull(SourceDetailsParsers.detect("javascript:alert(1)"))
    }

    // ---------- Zona ----------

    @Test fun zonaMovieCardParsesMicrodataAndEmbed() {
        val d = ZonaDetailsParser().parse(doc("zona_movie.html", "https://w140.zona.plus/movies/chelovek-pauk-novyi-den"))
        assertEquals("Человек-паук: Новый день", d.title)
        assertEquals("Spider-Man: Brand New Day", d.originalTitle)
        assertEquals("2026", d.year)
        assertEquals("Германия, Великобритания, Канада, США", d.country)
        assertTrue(d.genres.contains("приключения"))
        assertTrue(d.genres.contains("боевик"))
        assertEquals(145, d.durationMin)
        assertTrue(d.rating!!.contains("7.9"))
        assertTrue(d.description!!.startsWith("Одинокий и повзрослевший Питер Паркер"))
        assertEquals("Дестин Дэниел Креттон", d.director)
        assertTrue(d.actors.isNotEmpty())
        assertFalse(d.isSeries)
        // у фильма есть официальный embed-плеер
        assertEquals(SourcePlayKind.EMBED, d.play!!.kind)
        assertEquals("https://e1.zona.plus/movies/embed/chelovek-pauk-novyi-den", d.play!!.url)
    }

    @Test fun zonaSerialCardListsSeasonsAndHonestPlaybackNote() {
        val d = ZonaDetailsParser().parse(doc("zona_serial.html", "https://w140.zona.plus/tvseries/gangsterlend"))
        assertTrue(d.isSeries)
        assertEquals(listOf(1, 2), d.seasons.map { it.number })
        assertTrue(d.seasons[0].url!!.endsWith("/tvseries/gangsterlend/season-1"))
        assertTrue(d.episodesOrEmpty().isNotEmpty())
        assertEquals("Stick or Twist", d.episodesOrEmpty().first().title)
        assertEquals(1, d.episodesOrEmpty().first().number)
        // embed-плеера у сериалов Zona нет — карточка честно об этом говорит
        assertNull(d.play)
        assertNotNull(d.playbackNote)
    }

    // ---------- Смотрим ----------

    @Test fun smotrimBrandParsesTitlePosterAndEpisodes() {
        val d = SmotrimDetailsParser().parse(doc("smotrim_brand.html", "https://smotrim.ru/brand/75257"))
        assertEquals("Рожденные в СССР", d.title)
        assertEquals("1991", d.year)
        assertEquals("https://cdn.smotrim.ru/photobank/prod/large/150/628/77.jpg", d.poster)
        assertTrue(d.isSeries)
        val eps = d.episodesOrEmpty()
        assertTrue(eps.size >= 10)
        assertEquals("https://embed.smotrim.ru/iframe/video/id/6082985", eps.first().url)
        assertTrue(eps.any { it.url!!.endsWith("/video/id/4012679") })
        assertTrue(eps.any { it.durationMin > 0 })
        // берётся реальное описание бренда, а не SEO-шаблон оглавления
        assertTrue(d.description!!.startsWith("Документальный цикл"))
        assertFalse(d.description!!.contains(" смотреть онлайн"))
    }

    // ---------- Seasonvar ----------

    @Test fun seasonvarSerialParsesTitleAndPlaylistLinks() {
        val d = SeasonvarDetailsParser().parse(doc("seasonvar_serial.html",
            "https://seasonvar.ru/serial-10063-VVS_Sekrety_Superbrendov_pshjkux.html"))
        assertEquals("BBC: Секреты Супербрендов", d.title)
        assertEquals("Secrets of the Superbrands", d.originalTitle)
        assertEquals("https://cdn.svstat.net/oblojka/10063.jpg", d.poster)
        assertTrue(d.isSeries)
        assertEquals(1, d.seasons.size)
        assertTrue(d.seasons[0].url!!.contains("/playls2/") && d.seasons[0].url!!.contains("plist.txt"))
        // нативного воспроизведения нет — в карточке нет кнопки «Смотреть» и есть честная пометка
        assertNull(d.play)
        assertNotNull(d.playbackNote)
    }

    @Test fun seasonvarNetEngineCleansSeoTitleAndIsHonestAboutSeasons() {
        val d = SeasonvarDetailsParser().parse(doc("seasonvar_net_serial.html",
            "https://seasonvar.net/serial/3337-ono-dobro-pozhalovat-v-derri.html"))
        // og:title «Оно: Добро пожаловать в Дерри (2025) смотреть на SeasonVar» → чистое название
        assertEquals("Оно: Добро пожаловать в Дерри", d.title)
        assertEquals("2025", d.year)
        assertEquals("https://seasonvar.net/static/og/3337.jpg", d.poster)
        assertTrue(d.isSeries)
        // новый движок отдаёт сезоны только через JS — карточка не выдумывает их
        assertTrue(d.seasons.isEmpty())
        assertTrue(d.playbackNote!!.contains("плеером сайта"))
    }

    @Test fun seasonvarPlaylistJsonBecomesEpisodes() {
        val json = javaClass.getResourceAsStream("/sources/seasonvar_playlist.json")!!.use {
            it.readBytes().decodeToString()
        }
        val eps = SeasonvarDetailsParser().episodesFromPlaylist(json)
        assertEquals(1, eps.size)
        assertEquals(1, eps[0].number)
        assertTrue(eps[0].title.contains("серия"))
        assertTrue(!eps[0].title.contains("<"))
    }

    // ---------- защита от чужих страниц ----------

    @Test fun antibotOrLoginPageIsRejected() {
        val antibot = Jsoup.parse("<html><title>Just a moment...</title><body>Checking your browser before accessing</body></html>", "https://x.test/")
        val login = Jsoup.parse("<html><title></title><body><form><input type=\"password\"></form></body></html>", "https://x.test/")
        assertTrue(DetailsParsing.isAntibotOrLogin(antibot))
        assertTrue(DetailsParsing.isAntibotOrLogin(login))
        val normal = doc("zona_movie.html", "https://w140.zona.plus/movies/chelovek-pauk-novyi-den")
        assertFalse(DetailsParsing.isAntibotOrLogin(normal))
    }
}

private fun SourceDetails.episodesOrEmpty(): List<SourceEpisode> = seasons.flatMap { it.episodes }
