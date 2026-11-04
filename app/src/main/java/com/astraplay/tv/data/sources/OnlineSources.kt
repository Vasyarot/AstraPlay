package com.astraplay.tv.data.sources

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

data class OnlineSource(val id: String, val name: String, val address: String?, val note: String = "Фильмы и сериалы")
data class OnlineTitle(val title: String, val url: String, val poster: String?)

/** User-editable addresses are separate from the app's parsers and survive updates. */
class OnlineSources(context: Context) {
    private val prefs = context.getSharedPreferences("online_sources", Context.MODE_PRIVATE)
    private val defaults = listOf(
        OnlineSource("mosfilm_rt", "Мосфильм · RUTUBE", "https://rutube.ru/channel/25963146/", "Русские фильмы • встроенный плеер"),
        OnlineSource("lenfilm_rt", "Ленфильм · RUTUBE", "https://rutube.ru/channel/1792500/", "Русские фильмы • встроенный плеер"),
        OnlineSource("lostfilm", "LostFilm.tv", "https://lostfilm.download/", "Сериалы и озвучка LostFilm"),
        OnlineSource("seasonvar", "Seasonvar", "https://seasonvar.net/", "Сериалы по сезонам"),
        OnlineSource("filmix", "Filmix", "https://filmix.pub/"),
        OnlineSource("hdrezka", "HDRezka", "https://rezka.ag/"),
        OnlineSource("zetflix", "Zetflix", "https://zet-flix.online/"),
        OnlineSource("zona", "Zona", "https://g4.zona.plus/"),
        OnlineSource("eneyida", "Eneyida (UA)", "https://eneyida.tv/", "Українською мовою"),
        OnlineSource("anilibria", "AniLibria", "https://aniliberty.top/", "Аниме • текущий сайт проекта"),
        // Легальные бесплатные источники, доступные из РФ
        OnlineSource("smotrim", "Смотрим (ВГТРК)", "https://smotrim.ru/", "Бесплатно и легально • фильмы и сериалы"),
        OnlineSource("mosfilm", "Мосфильм. Золотая коллекция", "https://www.youtube.com/@mosfilmtv", "Классика бесплатно и легально • на YouTube"),
        OnlineSource("kinodb", "KinoDB", null, "Укажите адрес сервиса"),
        OnlineSource("encyclopedia", "Encyclopedia", null, "Укажите адрес сервиса"),
        OnlineSource("zombie", "Zombie", null, "Укажите адрес сервиса"),
        OnlineSource("octopus", "Octopus", null, "Укажите адрес сервиса"),
    )

    fun all(): List<OnlineSource> = defaults.map { source ->
        source.copy(address = prefs.getString("url_${source.id}", source.address))
    }

    fun setAddress(id: String, address: String) {
        require(validWebUrl(address)) { "Нужен адрес http:// или https://" }
        prefs.edit().putString("url_$id", address.trim()).apply()
    }

    companion object {
        fun validWebUrl(value: String): Boolean = value.toHttpUrlOrNull()?.let {
            it.username.isEmpty() && it.password.isEmpty()
        } ?: false
    }
}

/** Public HTML catalogs only. Players and authentication remain on the source's page. */
object OnlineCatalogParser {
    fun parse(html: String, baseUrl: String, sourceId: String): List<OnlineTitle> =
        parse(Jsoup.parse(html, baseUrl), sourceId)

    fun parse(doc: Document, sourceId: String): List<OnlineTitle> =
        when (sourceId) {
            "zona" -> parseZona(doc)
            "seasonvar" -> parseSeasonvar(doc)
            else -> parseGeneric(doc, sourceId)
        }

    /**
     * Seasonvar: два живых формата ссылок — новый движок seasonvar.net
     * (`a.line`, путь `/serial/<id>-…html`) и прежняя разметка зеркал
     * (`/serial-<id>-…html`, название в `.news_n`). Русский текст сохраняется,
     * дубли ссылок склеиваются, ссылки чужих доменов отбрасываются.
     */
    private fun parseSeasonvar(doc: Document): List<OnlineTitle> {
        val out = linkedMapOf<String, OnlineTitle>()
        val base = doc.baseUri().toHttpUrlOrNull() ?: return out.values.toList()
        val serialPath = Regex("^/serial[-/]\\d+.*\\.html$")
        for (link in doc.select("a[href]")) {
            val url = link.absUrl("href").toHttpUrlOrNull() ?: continue
            if (url.host != base.host) continue
            if (!serialPath.matches(url.encodedPath)) continue
            val title = sequenceOf(
                link.selectFirst(".seasonvar-line__left span")?.text(),
                link.selectFirst(".news_n")?.ownText(),
                link.selectFirst(".news_n")?.text(),
            ).filterNotNull().map { it.trim() }
                .firstOrNull { it.length in 2..180 } ?: continue
            val poster = (link.selectFirst(".seasonvar-line__image")?.attr("style")
                ?: link.selectFirst("img[src]")?.let { it.absUrl("src").ifEmpty { null } })
                ?.let { Regex("url\\(([^)]+)\\)").find(it)?.groupValues?.get(1) ?: it }
                ?.removeSurrounding("'")?.removeSurrounding("\"")
                ?.let { base.resolve(it)?.toString() }
            val cleanUrl = url.newBuilder().fragment(null).build().toString()
            val old = out[cleanUrl]
            if (old == null || (old.poster == null && poster != null)) {
                out[cleanUrl] = OnlineTitle(title, cleanUrl, poster)
            }
            if (out.size >= 120) break
        }
        return out.values.toList()
    }

    /** Zona: карточки a.results-item, название в title, постер в meta[itemprop=image] родителя. */
    private fun parseZona(doc: Document): List<OnlineTitle> {
        val out = linkedMapOf<String, OnlineTitle>()
        val base = doc.baseUri().toHttpUrlOrNull() ?: return out.values.toList()
        for (link in doc.select("a.results-item[href]")) {
            val url = link.absUrl("href").toHttpUrlOrNull() ?: continue
            if (url.host != base.host) continue
            val path = url.encodedPath
            if (!path.startsWith("/movies/") && !path.startsWith("/tvseries/")) continue
            val title = link.attr("title").ifEmpty { link.text() }.trim()
            if (title.length < 2) continue
            var host: org.jsoup.nodes.Element? = link.parent()
            while (host != null && host.tagName() != "li") host = host.parent()
            val poster = host?.selectFirst("meta[itemprop=image]")?.attr("content")
                ?.takeIf { it.startsWith("http") }
            val cleanUrl = url.newBuilder().fragment(null).build().toString()
            out.putIfAbsent(cleanUrl, OnlineTitle(title, cleanUrl, poster))
            if (out.size >= 120) break
        }
        return out.values.toList()
    }

    private fun parseGeneric(doc: Document, sourceId: String): List<OnlineTitle> {
        val out = linkedMapOf<String, OnlineTitle>()
        for (link in doc.select("a[href]")) {
            val url = link.absUrl("href").toHttpUrlOrNull() ?: continue
            val base = doc.baseUri().toHttpUrlOrNull() ?: continue
            if (url.host != base.host) continue
            val path = url.encodedPath
            val matches = when (sourceId) {
                "lostfilm" -> Regex("^/(series|movies)/[^/]+/?$").matches(path)
                "seasonvar" -> Regex("^/serial-\\d+.*\\.html$").matches(path)
                "anilibria" -> path.startsWith("/anime/releases/release/")
                "smotrim" -> Regex("^/brand/\\d+$").matches(path)
                else -> Regex(".*/\\d+[-/][^/]+(?:\\.html)?/?$").matches(path)
            }
            if (!matches) continue
            var container = link
            repeat(3) {
                if (container.selectFirst("img") == null && container.parent() != null) container = container.parent()!!
            }
            val img = container.selectFirst("img")
            val named = container.selectFirst(".title-ru, .name-ru, .news_n, .name, .short-title, .title, h2, h3, .card-title, .v-card-title")
            val title = sequenceOf(named?.ownText()?.ifEmpty { named.text() }, link.attr("title"), img?.attr("alt"), link.text())
                .filterNotNull().map { it.trim() }.firstOrNull { it.length in 2..180 && it !in listOf("Смотреть", "Подробнее", "Все серии") }
                ?: continue
            val poster = img?.let { image ->
                sequenceOf("data-src", "data-original", "src").map { image.absUrl(it) }
                    .firstOrNull { it.toHttpUrlOrNull() != null }
            }
            val cleanUrl = url.newBuilder().fragment(null).build().toString()
            val old = out[cleanUrl]
            if (old == null || (old.poster == null && poster != null)) out[cleanUrl] = OnlineTitle(title, cleanUrl, poster)
            if (out.size >= 120) break
        }
        return out.values.toList()
    }
}
