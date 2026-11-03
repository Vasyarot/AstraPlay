package com.astraplay.tv.data.sources

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class SourceCatalogLoaderTest {
    @Test fun movieAndSeriesTabsUseDifferentCatalogs() {
        assertEquals("https://www.lostfilm.tv/movies/", SourceCatalogLoader.categoryAddress("lostfilm", "https://www.lostfilm.tv/series/", "movies"))
        assertEquals("https://filmix.pub/seria/", SourceCatalogLoader.categoryAddress("filmix", "https://filmix.pub/", "series"))
    }

    @Test fun mirrorDiscoveryUsesPublishedServiceDomainsAndIgnoresUnrelatedLinks() {
        val page = Jsoup.parse("""<div class="tgme_page_description"><a href="https://ru2.zetflix.zone/films/">Сайт</a><a href="https://unrelated.test/">Реклама</a><a href="https://t.me/account">Канал</a></div>""", "https://t.me/zetflixpro")
        assertEquals(listOf("https://ru2.zetflix.zone/"), SourceCatalogLoader.publishedZetflixAddresses(page))
    }
}
