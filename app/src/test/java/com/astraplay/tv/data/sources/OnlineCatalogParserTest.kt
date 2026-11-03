package com.astraplay.tv.data.sources

import org.junit.Assert.*
import org.junit.Test

class OnlineCatalogParserTest {
    @Test fun lostfilmPrefersRussianTitleAndResolvesPoster() {
        val html = """<div class="row"><a href="/series/Five_Minutes/"><img src="/posters/5.jpg"><div class="name-ru">Пять минут тишины</div><div class="name-en">Five Minutes of Silence</div></a></div>"""
        val item = OnlineCatalogParser.parse(html, "https://www.lostfilm.tv/series/", "lostfilm").single()
        assertEquals("Пять минут тишины", item.title)
        assertEquals("https://www.lostfilm.tv/posters/5.jpg", item.poster)
    }

    @Test fun seasonvarKeepsRussianTextAndDeduplicatesLinks() {
        val html = """<a href="/serial-12-Test.html"><div class="news_n">Пять минут тишины<span> / Five Minutes of Silence</span></div></a><a href="/serial-12-Test.html">Подробнее</a><a href="https://other.test/serial-13-Ad.html">Реклама</a>"""
        val items = OnlineCatalogParser.parse(html, "https://seasonvar.ru/", "seasonvar")
        assertEquals(1, items.size)
        assertEquals("Пять минут тишины", items.single().title)
    }

    @Test fun lazyPosterAndNativeTitleArePreferredOverPromotionalAltText() {
        val html = """<a href="/12-film.html" title="Смотреть фильм бесплатно"><img data-src="/img/12.webp" src="/placeholder.gif" alt="Смотреть онлайн"><div class="title">Название фильма</div></a>"""
        val item = OnlineCatalogParser.parse(html, "https://example.com/", "eneyida").single()
        assertEquals("Название фильма", item.title)
        assertEquals("https://example.com/img/12.webp", item.poster)
    }

    @Test fun unsafeAddressesAreRejected() {
        assertFalse(OnlineSources.validWebUrl("file:///data/data/private"))
        assertFalse(OnlineSources.validWebUrl("javascript:alert(1)"))
        assertFalse(OnlineSources.validWebUrl("https://user:pass@example.com"))
        assertTrue(OnlineSources.validWebUrl("https://example.com/catalog/"))
    }
}
