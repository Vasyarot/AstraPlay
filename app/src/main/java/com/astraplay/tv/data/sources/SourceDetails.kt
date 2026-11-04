package com.astraplay.tv.data.sources

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Серия источника. [url] — то, что можно воспроизвести нативно/через официальный плеер; null — только название. */
data class SourceEpisode(
    val number: Int,
    val title: String,
    val url: String? = null,
    val durationMin: Int = 0,
    val playKind: SourcePlayKind = SourcePlayKind.EMBED,
)

/** Сезон источника. [url] — адрес догрузки серий (страница сезона или плейлист). */
data class SourceSeason(
    val number: Int,
    val name: String,
    val url: String? = null,
    val episodes: List<SourceEpisode> = emptyList(),
)

enum class SourcePlayKind { DIRECT, EMBED, PAGE }

/** Вариант воспроизведения: [EMBED] — только официальный плеер источника, [DIRECT] — прямой медиаресурс. */
data class SourcePlayOption(
    val label: String,
    val kind: SourcePlayKind,
    val url: String,
    val referer: String? = null,
    val fallbackUrl: String? = null,
)

/**
 * Нативная карточка фильма/сериала из конкретного источника. Все поля
 * необязательные: отсутствующие на экране аккуратно скрываются.
 */
data class SourceDetails(
    val sourceId: String,
    val sourceName: String,
    val url: String,                     // финальный адрес карточки после редиректов
    val title: String,
    val originalTitle: String? = null,
    val poster: String? = null,
    val year: String? = null,
    val country: String? = null,
    val genres: List<String> = emptyList(),
    val durationMin: Int = 0,
    val ageRating: String? = null,
    val rating: String? = null,          // значение с именем источника рейтинга
    val description: String? = null,
    val director: String? = null,
    val actors: List<String> = emptyList(),
    val isSeries: Boolean = false,
    val seasons: List<SourceSeason> = emptyList(),
    val play: SourcePlayOption? = null,
    /** Честное ограничение по воспроизведению; показывается в карточке. */
    val playbackNote: String? = null,
)

/** Неудача получения карточки: не страница фильма, антибот или форма входа. */
class SourceDetailsException(message: String) : Exception(message)

/** JSON-сериализация карточки для файлового кэша: переживает убийство процесса. */
internal object SourceDetailsJson {

    fun write(d: SourceDetails): String = org.json.JSONObject().apply {
        put("sourceId", d.sourceId)
        put("sourceName", d.sourceName)
        put("url", d.url)
        put("title", d.title)
        d.originalTitle?.let { put("originalTitle", it) }
        d.poster?.let { put("poster", it) }
        d.year?.let { put("year", it) }
        d.country?.let { put("country", it) }
        put("genres", org.json.JSONArray(d.genres))
        put("durationMin", d.durationMin)
        d.ageRating?.let { put("ageRating", it) }
        d.rating?.let { put("rating", it) }
        d.description?.let { put("description", it) }
        d.director?.let { put("director", it) }
        put("actors", org.json.JSONArray(d.actors))
        put("isSeries", d.isSeries)
        put("seasons", org.json.JSONArray().apply {
            d.seasons.forEach { s ->
                put(org.json.JSONObject().apply {
                    put("number", s.number)
                    put("name", s.name)
                    s.url?.let { put("url", it) }
                    put("episodes", org.json.JSONArray().apply {
                        s.episodes.forEach { e ->
                            put(org.json.JSONObject().apply {
                                put("number", e.number)
                                put("title", e.title)
                                e.url?.let { put("url", it) }
                                put("durationMin", e.durationMin)
                                put("playKind", e.playKind.name)
                            })
                        }
                    })
                })
            }
        })
        d.play?.let { p -> put("play", org.json.JSONObject()
            .put("label", p.label).put("kind", p.kind.name).put("url", p.url).put("referer", p.referer).put("fallbackUrl", p.fallbackUrl)) }
        d.playbackNote?.let { put("playbackNote", it) }
    }.toString()

    fun read(s: String): SourceDetails? = runCatching {
        val o = org.json.JSONObject(s)
        fun str(name: String) = o.optString(name).ifEmpty { null }
        fun strList(name: String) = o.optJSONArray(name)
            ?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).ifEmpty { null } } }
            ?: emptyList()
        SourceDetails(
            sourceId = o.getString("sourceId"),
            sourceName = o.getString("sourceName"),
            url = o.getString("url"),
            title = o.getString("title"),
            originalTitle = str("originalTitle"),
            poster = str("poster"),
            year = str("year"),
            country = str("country"),
            genres = strList("genres"),
            durationMin = o.optInt("durationMin", 0),
            ageRating = str("ageRating"),
            rating = str("rating"),
            description = str("description"),
            director = str("director"),
            actors = strList("actors"),
            isSeries = o.optBoolean("isSeries", false),
            seasons = o.optJSONArray("seasons")?.let { sa ->
                (0 until sa.length()).mapNotNull { so ->
                    val s = sa.optJSONObject(so) ?: return@mapNotNull null
                    SourceSeason(
                        number = s.optInt("number", 0),
                        name = s.optString("name"),
                        url = s.optString("url").ifEmpty { null },
                        episodes = s.optJSONArray("episodes")?.let { ea ->
                            (0 until ea.length()).mapNotNull { eo ->
                                val e = ea.optJSONObject(eo) ?: return@mapNotNull null
                                SourceEpisode(
                                    number = e.optInt("number", 0),
                                    title = e.optString("title"),
                                    url = e.optString("url").ifEmpty { null },
                                    durationMin = e.optInt("durationMin", 0),
                                    playKind = runCatching { SourcePlayKind.valueOf(e.optString("playKind", "EMBED")) }
                                        .getOrDefault(SourcePlayKind.EMBED))
                            }
                        } ?: emptyList())
                }
            } ?: emptyList(),
            play = o.optJSONObject("play")?.let { p ->
                SourcePlayOption(
                    label = p.getString("label"),
                    kind = SourcePlayKind.valueOf(p.getString("kind")),
                    url = p.getString("url"), referer = p.optString("referer").takeIf { it.isNotBlank() && it != "null" },
                    fallbackUrl = p.optString("fallbackUrl").takeIf { OnlineSources.validWebUrl(it) })
            },
            playbackNote = str("playbackNote"),
        )
    }.getOrNull()
}

/**
 * Адаптер парсинга карточки конкретного источника. Один источник — один
 * парсер с собственными селекторами; общих регулярных выражений на все
 * сайты нет.
 */
interface SourceDetailsParser {
    val sourceId: String
    val sourceName: String
    fun parse(doc: Document): SourceDetails

    suspend fun enrich(loader: SourceDetailsLoader, details: SourceDetails, doc: Document): SourceDetails = details

    /** Догрузка серий выбранного сезона; по умолчанию сезоны уже полные. */
    suspend fun loadSeason(loader: SourceDetailsLoader, details: SourceDetails, season: SourceSeason): SourceSeason = season
}

/** Общие помощники разбора. */
internal object DetailsParsing {
    fun meta(doc: Document, css: String): String? =
        doc.selectFirst(css)?.attr("content")?.trim()?.ifEmpty { null }

    fun og(doc: Document, property: String): String? = meta(doc, "meta[property=$property]")

    /** ISO-8601 длительность PT145M → 145. */
    fun durationMinutes(iso: String?): Int {
        val m = Regex("PT(?:(\\d+)H)?(?:(\\d+)M)?").find(iso?.trim()?.uppercase() ?: "") ?: return 0
        val h = m.groupValues[1].toIntOrNull() ?: 0
        val min = m.groupValues[2].toIntOrNull() ?: 0
        return h * 60 + min
    }

    /** Текст без HTML, SEO-мусора и остатков разметки. */
    fun cleanText(raw: String?): String? {
        val text = raw?.replace(Regex("<br\\s*/?>"), " ")
            ?.replace(Regex("<[^>]*>"), " ")
            ?.replace('\u00A0', ' ')
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?: return null
        if (text.length < 2) return null
        return text
    }

    fun minutes(text: String?): Int =
        Regex("(\\d+)\\s*мин").find(text ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /** Похоже ли на страницу антибот-проверки или форму входа. */
    fun isAntibotOrLogin(doc: Document): Boolean {
        val text = doc.body()?.text()?.take(1500)?.lowercase() ?: return false
        val markers = listOf(
            "just a moment", "checking your browser", "cf-browser-verification",
            "доступ ограничен", "проверка браузера", "подтвердите, что запросы отправляли вы",
            " ddos-guard", "denied access",
        )
        if (markers.any { text.contains(it) }) return true
        val hasPassword = doc.selectFirst("input[type=password]") != null
        val noTitle = doc.title().isBlank()
        return hasPassword && noTitle
    }
}

/** Zona: microdata в <head> + блок entity-desc; сезоны — entity-seasons. */
internal class ZonaDetailsParser : SourceDetailsParser {
    override val sourceId = "zona"
    override val sourceName = "Zona"

    override fun parse(doc: Document): SourceDetails {
        with(DetailsParsing) {
            val title = meta(doc, "meta[itemprop=name]")
                ?: og(doc, "og:title")
                ?: doc.title().substringBefore(" смотреть").trim()
            val path = doc.baseUri().toHttpUrlOrNull()?.encodedPath ?: "/"
            val isSeries = path.startsWith("/tvseries/")
            val genres = doc.select("span[itemprop=genre]").map { it.text().trim() }.filter { it.isNotEmpty() }
            val countries = doc.select(".js-countries .entity-desc-link-u").map { it.text().trim() }
            val director = doc.selectFirst(".js-director [itemprop=name]")?.text()?.trim()?.ifEmpty { null }
                ?: cleanText(doc.selectFirst(".js-director")?.text())
            val actors = doc.select("span[itemprop=actor] [itemprop=name]").map { it.text().trim() }
                .filter { it.isNotEmpty() }.take(10)
            val rating = meta(doc, "meta[itemprop=ratingValue]")?.let { "★ $it · $sourceName" }
            val seasons = doc.select("a.entity-season[href]").map { link ->
                val number = Regex("season-(\\d+)").find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
                    ?: link.text().trim().toIntOrNull() ?: 0
                SourceSeason(number, "${number}-й сезон", link.absUrl("href").ifEmpty { null })
            }.distinctBy { it.number }
            val episodes = parseEpisodes(doc)
            val play = if (!isSeries) {
                // официальный embed-плеер Zona (только он, без страницы с рекламой)
                doc.selectFirst("[itemprop=embedUrl]")?.attr("href")
                    ?.takeIf { it.startsWith("https://") }
                    ?.let { SourcePlayOption("Смотреть", SourcePlayKind.EMBED, it) }
            } else null
            return SourceDetails(
                sourceId = sourceId,
                sourceName = sourceName,
                url = doc.baseUri(),
                title = title,
                originalTitle = meta(doc, "meta[itemprop=alternativeHeadline]"),
                poster = meta(doc, "meta[itemprop=image]") ?: og(doc, "og:image"),
                year = doc.selectFirst("[itemprop=copyrightYear]")?.text()?.trim()?.ifEmpty { null },
                country = countries.takeIf { it.isNotEmpty() }?.joinToString(", "),
                genres = genres,
                durationMin = durationMinutes(meta(doc, "meta[itemprop=duration]")),
                rating = rating,
                description = cleanText(doc.selectFirst(".entity-desc-description")?.text())
                    ?: meta(doc, "meta[itemprop=description]"),
                director = director,
                actors = actors,
                isSeries = isSeries,
                // серии страницы относятся к выбранному (первому) сезону; остальные догружаются лениво
                seasons = seasons.mapIndexed { idx, s ->
                    if (idx == 0) s.copy(episodes = episodes) else s
                }.ifEmpty {
                    if (episodes.isEmpty()) emptyList()
                    else listOf(SourceSeason(1, "1-й сезон", doc.baseUri(), episodes))
                },
                play = play,
                playbackNote = if (isSeries)
                    "Сериалы Zona воспроизводятся официальным плеером на странице источника." else null,
            )
        }
    }

    /** Серии сезона со страницы сезона: span.entity-episode-link + entity-episode-name. */
    private fun parseEpisodes(doc: Document): List<SourceEpisode> = with(DetailsParsing) {
        doc.select("span.entity-episode-link").mapNotNull { ep ->
            val name = cleanText(ep.selectFirst(".entity-episode-name")?.text()) ?: return@mapNotNull null
            val number = ep.attr("data-episode").toIntOrNull()
                ?: Regex("^(\\d+)\\s*[—-]").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            SourceEpisode(number, cleanText(name.substringAfter("—", name)) ?: name)
        }
    }

    override suspend fun loadSeason(loader: SourceDetailsLoader, details: SourceDetails, season: SourceSeason): SourceSeason {
        val pageUrl = season.url ?: return season
        val episodes = parseEpisodes(loader.document(pageUrl))
        if (episodes.isEmpty()) return season
        return season.copy(episodes = episodes)
    }
}

/** Смотрим (ВГТРК): Open Graph + блоки card-episode; серии — /video/{id}. */
internal class SmotrimDetailsParser : SourceDetailsParser {
    override val sourceId = "smotrim"
    override val sourceName = "Смотрим"

    override fun parse(doc: Document): SourceDetails {
        with(DetailsParsing) {
            val ogTitle = og(doc, "og:title") ?: doc.title()
            // «Сериал X (1991-2026) — смотреть онлайн в хорошем качестве на «СМОТРИМ»» → X
            var name = cleanText(ogTitle.substringBefore(" — смотреть"))
                ?.removePrefix("Сериал ")?.removePrefix("Фильм ")?.trim() ?: ogTitle
            val yearFromTitle = Regex("\\((\\d{4})(?:-(\\d{4}))?\\)").find(name)?.groupValues?.get(1)
            name = name.replace(Regex("\\s*\\((\\d{4})(?:-(\\d{4}))?\\)$"), "").trim()
            // серия: блок card-episode — ссылка /video/{id}, название, метка и длительность внутри
            val htmlEpisodes = doc.select(".card-episode").mapNotNull { card ->
                val url = card.selectFirst("a[href]")?.absUrl("href")?.toHttpUrlOrNull()
                    ?: return@mapNotNull null
                val videoId = Regex("/video/(\\d+)").find(url.encodedPath)?.groupValues?.get(1)
                    ?: return@mapNotNull null
                val title = cleanText(card.selectFirst(".card-episode__title")?.text()) ?: return@mapNotNull null
                val label = cleanText(card.selectFirst(".card-episode__label")?.text())
                SourceEpisode(
                    number = Regex("(\\d+)\\s*серия").find(label ?: "")?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("^(\\d+)").find(title)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                    title = title,
                    // официальный плеер «Смотрим» без остальной страницы
                    url = "https://embed.smotrim.ru/iframe/video/id/$videoId",
                    durationMin = minutes(card.selectFirst(".card-episode__duration")?.text()),
                )
            }.distinctBy { it.url }
            val hydratedSeasons = nuxtSeasons(doc)
            val episodes = htmlEpisodes.ifEmpty { hydratedSeasons.flatMap { it.episodes } }
            val isMovie = ogTitle.startsWith("Фильм ") || (!ogTitle.startsWith("Сериал ") && doc.selectFirst("header[itemtype]")?.attr("itemtype")?.endsWith("/Movie") == true)
            val isSeries = !isMovie && episodes.isNotEmpty()
            return SourceDetails(
                sourceId = sourceId,
                sourceName = sourceName,
                url = doc.baseUri(),
                title = name,
                poster = og(doc, "og:image"),
                year = yearFromTitle,
                // реальное описание бренда, а не SEO-шаблон оглавления
                description = meta(doc, "meta[itemprop=description]")?.let { cleanText(it) }
                    ?: og(doc, "og:description")?.let { cleanText(it) },
                isSeries = isSeries,
                seasons = if (isSeries) hydratedSeasons.ifEmpty { listOf(SourceSeason(1, "Серии", doc.baseUri(), episodes.take(1000))) } else emptyList(),
                play = if (!isSeries) {
                    val videoId = Regex("/video/(\\d+)").find(doc.baseUri())?.groupValues?.get(1)
                    val address = videoId?.let { "https://embed.smotrim.ru/iframe/video/id/$it" } ?: episodes.firstOrNull()?.url
                    address?.let { SourcePlayOption("Смотреть", SourcePlayKind.EMBED, it, doc.baseUri()) }
                } else null,
            )
        }
    }

    /** Public SSR state uses references into an array. Read only this brand's episode list. */
    private fun nuxtSeasons(doc: Document): List<SourceSeason> = runCatching {
        val brandId = Regex("/brand/(\\d+)").find(doc.baseUri())?.groupValues?.get(1) ?: return emptyList()
        val raw = doc.selectFirst("script#__NUXT_DATA__")?.data() ?: return emptyList()
        if (raw.length > 1024 * 1024) return emptyList()
        val pool = org.json.JSONArray(raw)
        if (pool.length() > 40000) return emptyList()
        fun reference(o: org.json.JSONObject, key: String): Any? = pool.opt(o.optInt(key, -1))
        val key = "brand-episodes-$brandId"
        val root = (0 until pool.length()).asSequence().mapNotNull { pool.optJSONObject(it) }.firstOrNull { it.has(key) }
            ?: return emptyList()
        val container = reference(root, key) as? org.json.JSONObject ?: return emptyList()
        val groups = reference(container, "list") as? org.json.JSONArray ?: return emptyList()
        (0 until minOf(groups.length(), 100)).mapNotNull { groupIndex ->
            val group = pool.optJSONObject(groups.optInt(groupIndex, -1)) ?: return@mapNotNull null
            val epContainer = reference(group, "episodes") as? org.json.JSONObject ?: return@mapNotNull null
            val rows = reference(epContainer, "list") as? org.json.JSONArray ?: return@mapNotNull null
            val label = (reference(group, "title") as? String)?.takeIf { it.isNotBlank() }
            val season = label?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() } ?: groupIndex + 1
            val episodes = (0 until minOf(rows.length(), 1000)).mapNotNull { rowIndex ->
                val row = pool.optJSONObject(rows.optInt(rowIndex, -1)) ?: return@mapNotNull null
                val id = (reference(row, "publicId") as? Number)?.toLong()?.takeIf { it > 0 } ?: return@mapNotNull null
                val title = (reference(row, "title") as? String)?.take(180) ?: return@mapNotNull null
                val number = (reference(row, "number") as? Number)?.toInt()?.takeIf { it > 0 } ?: rowIndex + 1
                SourceEpisode(number, title.ifBlank { "Серия $number" }, "https://embed.smotrim.ru/iframe/video/id/$id",
                    ((reference(row, "durationSeconds") as? Number)?.toInt() ?: 0) / 60)
            }.distinctBy { it.url }
            SourceSeason(season, label ?: "Серии", episodes = episodes)
        }.filter { it.episodes.isNotEmpty() }
    }.getOrDefault(emptyList())
}

/** Seasonvar: h1/og + плейлисты `var pl` → plist.txt (публичный JSON серий). */
internal class SeasonvarDetailsParser : SourceDetailsParser {
    override val sourceId = "seasonvar"
    override val sourceName = "Seasonvar"

    override fun parse(doc: Document): SourceDetails {
        with(DetailsParsing) {
            val base = doc.baseUri().toHttpUrlOrNull()
            // старый движок (.ru): «Сериал X/Original N сезон онлайн»;
            // новый движок (.net): og:title «X (2025) смотреть на SeasonVar»
            val h1 = doc.selectFirst("h1.pgs-sinfo-title")?.text()
                ?: doc.selectFirst("h1")?.text()?.takeIf { !it.equals(doc.title(), true) }
                ?: og(doc, "og:title")?.substringBefore(" смотреть")
                ?: doc.title().substringBefore(" смотреть")
            val stripped = h1.removePrefix("Сериал ").trim()
            val parts = stripped.split("/")
            var title = parts.first().trim()
            val original = parts.getOrNull(1)?.trim()
                ?.removeSuffix("онлайн")?.trim()
                ?.replace(Regex("\\s+\\d+(-\\d+)?\\s+сезон\\s*$"), "")
                ?.trim()?.ifEmpty { null }
            // SEO- и сезонные хвосты: «… онлайн», «… 1-2 сезон», «… смотреть сериал тут …»
            title = title.removeSuffix("онлайн").trim()
                .replace(Regex("\\s+\\d+(-\\d+)?\\s+сезон\\s*$"), "")
                .replace(Regex("\\s+смотреть\\s+.*$"), "").trim()
            val year = Regex("\\((\\d{4})").find(og(doc, "og:title") ?: title)?.groupValues?.get(1)
                ?: Regex("\\((\\d{4})").find(title)?.groupValues?.get(1)
            // год из og:title не должен остаться в названии
            title = title.replace(Regex("\\s*\\(\\d{4}\\)\\s*$"), "").trim()
            val poster = og(doc, "og:image")?.let { raw -> base?.resolve(raw)?.toString() ?: raw }
            val seasonsDoc = seasonPlaylists(doc)
            val seasons = seasonsDoc.mapIndexed { idx, plistUrl ->
                val number = if (seasonsDoc.size == 1) 1 else idx + 1
                SourceSeason(number, "$number-й сезон", plistUrl)
            }
            val isNetEngine = base?.host?.endsWith("seasonvar.net") == true && seasons.isEmpty()
            return SourceDetails(
                sourceId = sourceId,
                sourceName = sourceName,
                url = doc.baseUri(),
                title = title,
                originalTitle = original,
                poster = poster,
                year = year,
                description = cleanText(meta(doc, "meta[name=description]")),
                isSeries = true,
                seasons = seasons,
                play = null,
                playbackNote = when {
                    // новый движок отдаёт сезоны и плееры только через JavaScript
                    isNetEngine -> "Список сезонов Seasonvar подгружается плеером сайта. Нативное воспроизведение не реализовано — откройте страницу источника по явному выбору."
                    else -> "Нативное воспроизведение Seasonvar не реализовано: «Открыть страницу источника» открывает сайт по явному выбору."
                },
            )
        }
    }

    /** Ссылки plist.txt из `var pl = {'0': "/playls2/…/plist.txt?…"};` — по одному на сезон. */
    private fun seasonPlaylists(doc: Document): List<String> {
        val body = doc.body()?.html() ?: return emptyList()
        val m = Regex("var\\s+pl\\s*=\\s*\\{(.+?)\\}\\s*;").find(body) ?: return emptyList()
        val entries = Regex("'(\\d+)'\\s*:\\s*\"([^\"]+)\"").findAll(m.groupValues[1])
        val base = doc.baseUri().toHttpUrlOrNull() ?: return emptyList()
        return entries.map { base.resolve(it.groupValues[2])?.toString() }
            .filterNotNull().distinct().toList()
    }

    override suspend fun enrich(loader: SourceDetailsLoader, details: SourceDetails, doc: Document): SourceDetails {
        if (details.seasons.isNotEmpty()) return details
        val element = doc.selectFirst(".seasonvar-page__as [data-ajax-url]") ?: return details
        val address = PublicVideoMetadata.url(doc.baseUri(), element.attr("data-ajax-url")) ?: return details
        if (address.toHttpUrlOrNull()?.host != doc.baseUri().toHttpUrlOrNull()?.host) return details
        val html = loader.fetch(address, details.url) ?: return details
        val schedule = scheduleFromHtml(html)
        return if (schedule.isEmpty()) details else details.copy(seasons = schedule,
            playbackNote = "Список серий и расписание загружены из источника. Адреса видео источник отдаёт отдельно в своём плеере.")
    }

    internal fun scheduleFromHtml(html: String): List<SourceSeason> = Jsoup.parse(html)
        .select(".seasonvar-page__as-item").take(100).mapNotNull { block ->
            val number = Regex("\\d+").find(block.selectFirst(".seasonvar-page__as-caption")?.text().orEmpty())?.value?.toIntOrNull()
                ?: return@mapNotNull null
            val episodes = block.select("li").take(1000).mapNotNull { row ->
                val text = row.selectFirst("span")?.text() ?: return@mapNotNull null
                val episode = Regex("\\d+").find(text)?.value?.toIntOrNull() ?: return@mapNotNull null
                SourceEpisode(episode, row.text())
            }
            SourceSeason(number, "Сезон $number", episodes = episodes)
        }.sortedBy { it.number }

    /** Публичный JSON плейлиста сезона → серии (только названия; потоки защищены плеером источника). */
    fun episodesFromPlaylist(json: String): List<SourceEpisode> {
        val arr = runCatching { org.json.JSONArray(json) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<SourceEpisode>()
        for (i in 0 until minOf(arr.length(), 1000)) {
            val o = arr.optJSONObject(i) ?: continue
            val raw = o.optString("title")
            val title = DetailsParsing.cleanText(raw) ?: continue
            val media = o.optString("file").ifBlank { o.optString("url") }.toHttpUrlOrNull()
                ?.takeIf { it.username.isEmpty() && it.password.isEmpty() && PublicVideoMetadata.direct(it.toString()) }?.toString()
            out += SourceEpisode(
                number = Regex("^(\\d+)\\s*серия").find(title)?.groupValues?.get(1)?.toIntOrNull()
                    ?: o.optInt("id", i + 1),
                title = title,
                url = media,
                playKind = SourcePlayKind.DIRECT,
            )
        }
        return out
    }

    override suspend fun loadSeason(loader: SourceDetailsLoader, details: SourceDetails, season: SourceSeason): SourceSeason {
        val plistUrl = season.url ?: return season
        val json = loader.fetch(plistUrl, referer = details.url) ?: return season
        val eps = episodesFromPlaylist(json)
        if (eps.isEmpty()) return season
        return season.copy(episodes = eps)
    }
}

/** Определение источника по адресу карточки. */
object SourceDetailsParsers {

    private val parsers: Map<String, SourceDetailsParser> = listOf(
        ZonaDetailsParser(), SmotrimDetailsParser(), SeasonvarDetailsParser(),
    ).associateBy { it.sourceId }

    /** sourceId, если адрес — карточка фильма/сериала поддержанного источника. */
    fun detect(url: String): String? {
        if (RutubeSource.videoId(url) != null) return "rutube"
        val u = url.toHttpUrlOrNull() ?: return null
        val host = u.host.lowercase()
        val path = u.encodedPath
        return when {
            host == "zona.plus" || host.endsWith(".zona.plus") || host == "zona.mobi" || host.endsWith(".zona.mobi") ->
                if (path.startsWith("/movies/") && path.length > "/movies/".length ||
                    path.startsWith("/tvseries/") && path.length > "/tvseries/".length) "zona" else null
            host == "smotrim.ru" || host.endsWith(".smotrim.ru") ->
                if (Regex("^/brand/\\d+$").matches(path) || Regex("^/video/\\d+$").matches(path)) "smotrim" else null
            host == "seasonvar.ru" || host.endsWith(".seasonvar.ru") ||
                host == "seasonvar.net" || host.endsWith(".seasonvar.net") ->
                if (Regex("^/serial[-/]\\d+.*\\.html$").matches(path)) "seasonvar" else null
            else -> {
                val known = when {
                    host == "lostfilm.download" || host.endsWith(".lostfilm.download") || host == "lostfilm.tv" || host.endsWith(".lostfilm.tv") -> "lostfilm"
                    host == "filmix.pub" || host.endsWith(".filmix.pub") -> "filmix"
                    host == "rezka.ag" || host.endsWith(".rezka.ag") -> "hdrezka"
                    host == "eneyida.tv" || host.endsWith(".eneyida.tv") -> "eneyida"
                    host == "aniliberty.top" || host.endsWith(".aniliberty.top") -> "anilibria"
                    host == "zet-flix.online" || host.endsWith(".zet-flix.online") || host == "zetflix.zone" || host.endsWith(".zetflix.zone") -> "zetflix"
                    else -> null
                }
                known?.takeIf { Regex("^/(series|movies)/[^/]+/?$|.*/\\d+[-/][^/]+(?:\\.html)?/?$").matches(path) }
            }
        }
    }

    fun isSupported(url: String): Boolean = detect(url) != null

    fun parserFor(sourceId: String): SourceDetailsParser? = parsers[sourceId]
        ?: GenericDetailsParser(sourceId)
}

/**
 * Загрузчик нативных карточек: HTML страницы источника без скриптов, рекламы
 * и остальных ресурсов полной страницы. Ограниченный кэш по источнику и
 * адресу, дедлайн, лимит размера, отмена при уходе с экрана.
 */
class SourceDetailsLoader(
    client: OkHttpClient,
    private val userAgent: String,
    /** Каталог файлового кэша карточек; null — без дискового кэша. */
    diskDir: java.io.File? = null,
) {
    private val http = client.newBuilder()
        .callTimeout(12, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private class Cached(val details: SourceDetails, val at: Long)

    /** Небольшой LRU-кэш: только данные карточек, без View/Activity и без HTML. */
    private val cache = object : LinkedHashMap<String, Cached>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>): Boolean = size > 8
    }

    private val disk = diskDir?.let { com.astraplay.tv.core.DiskJsonCache(it) }

    suspend fun load(url: String, sourceId: String, refresh: Boolean = false): SourceDetails {
        if (RutubeSource.videoId(url) != null) return RutubeSource(http, userAgent).details(url)
        val key = "native-video-v2|$sourceId|$url"
        if (!refresh) {
            synchronized(cache) { cache[key] }?.let { cached ->
                if (System.currentTimeMillis() - cached.at < CACHE_TTL_MS) return cached.details
            }
            // файловый кэш: карточка открывается мгновенно при повторном заходе
            if (disk != null) {
                val raw = disk.read(key)
                if (raw != null) {
                    val wrapped = runCatching { org.json.JSONObject(raw) }.getOrNull()
                    val at = wrapped?.optLong("at", 0L) ?: 0L
                    val details = wrapped?.optJSONObject("d")?.toString()
                        ?.let { SourceDetailsJson.read(it) }
                    if (details != null && System.currentTimeMillis() - at < DISK_TTL_MS) {
                        synchronized(cache) { cache[key] = Cached(details, System.currentTimeMillis()) }
                        return details
                    }
                }
            }
        }
        val parser = SourceDetailsParsers.parserFor(sourceId)
            ?: throw SourceDetailsException("Источник не поддерживает нативную карточку")
        val doc = withTimeout(TOTAL_TIMEOUT_MS) { document(url, headers = emptyMap()) }
        if (DetailsParsing.isAntibotOrLogin(doc)) {
            throw SourceDetailsException("Источник требует проверки или входа")
        }
        val parsed = try { withTimeout(5000) { parser.enrich(this@SourceDetailsLoader, parser.parse(doc), doc) } }
            catch (e: kotlinx.coroutines.TimeoutCancellationException) { kotlinx.coroutines.currentCoroutineContext().ensureActive(); parser.parse(doc) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { parser.parse(doc) }
        val metadata = GenericDetailsParser(sourceId).parse(doc)
        val direct = PublicVideoMetadata.media(doc).firstOrNull { it.kind == SourcePlayKind.DIRECT }
        val enriched = parsed.copy(durationMin = parsed.durationMin.takeIf { it > 0 } ?: metadata.durationMin,
            ageRating = parsed.ageRating ?: metadata.ageRating, country = parsed.country ?: metadata.country,
            genres = parsed.genres.ifEmpty { metadata.genres }, year = parsed.year ?: metadata.year)
        val details = if (direct != null && !enriched.isSeries) enriched.copy(play = direct, playbackNote = null) else enriched
        if (details.title.isBlank()) throw SourceDetailsException("Не удалось разобрать карточку источника")
        synchronized(cache) { cache[key] = Cached(details, System.currentTimeMillis()) }
        disk?.let { d ->
            runCatching {
                d.write(key, org.json.JSONObject()
                    .put("at", System.currentTimeMillis())
                    .put("d", org.json.JSONObject(SourceDetailsJson.write(details)))
                    .toString())
            }
        }
        return details
    }

    /** Серии одного сезона (ленивая догрузка для больших списков). */
    suspend fun loadSeason(details: SourceDetails, season: SourceSeason): SourceSeason {
        val parser = SourceDetailsParsers.parserFor(details.sourceId) ?: return season
        return parser.loadSeason(this, details, season)
    }

    /** Prefer public direct media. An official embed remains an explicit in-app fallback. */
    suspend fun resolve(play: SourcePlayOption): SourcePlayOption {
        require(OnlineSources.validWebUrl(play.url)) { "Некорректный адрес видео" }
        RutubeSource.videoId(play.url)?.let { id ->
            return try { RutubeSource(http, userAgent).resolve(play.url) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { SourcePlayOption("Плеер источника", SourcePlayKind.EMBED, RutubeSource.embedUrl(id), "https://rutube.ru/") }
        }
        SmotrimPlayback.videoId(play.url)?.let { id ->
            return try {
                val raw = fetch("https://player-api.smotrim.ru/api/v1/video/$id", "https://smotrim.ru/")
                SmotrimPlayback.parse(org.json.JSONObject(raw ?: "{}"), id)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { SourcePlayOption("Плеер источника", SourcePlayKind.EMBED, SmotrimPlayback.embedUrl(id), "https://smotrim.ru/") }
        }
        if (play.kind == SourcePlayKind.DIRECT || PublicVideoMetadata.direct(play.url))
            return play.copy(kind = SourcePlayKind.DIRECT)
        val doc = try {
            withTimeout(8000) { document(play.url, play.referer?.let { mapOf("Referer" to it) } ?: emptyMap()) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (play.kind == SourcePlayKind.EMBED) return play else throw e
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { if (play.kind == SourcePlayKind.EMBED) return play else throw e }
        val options = PublicVideoMetadata.media(doc)
        return options.firstOrNull { it.kind == SourcePlayKind.DIRECT }?.copy(fallbackUrl =
            if (play.kind == SourcePlayKind.EMBED) play.url else options.firstOrNull { it.kind == SourcePlayKind.EMBED }?.url)
            ?: if (play.kind == SourcePlayKind.EMBED) play
            else options.firstOrNull() ?: throw SourceDetailsException("На странице серии нет доступного встроенному плееру видео")
    }

    /** JSON/текст по адресу с указанным Referer — для публичных плейлистов. */
    suspend fun fetch(url: String, referer: String? = null): String? {
        val body = withTimeoutOrNull(TOTAL_TIMEOUT_MS) { string(url, referer) } ?: return null
        return body.takeIf { it.length <= MAX_BYTES }
    }

    private suspend fun string(url: String, referer: String?): String = suspendCancellableCoroutine { cont ->
        val rb = Request.Builder().url(url).header("User-Agent", userAgent)
        referer?.let { rb.header("Referer", it) }
        val call = http.newCall(rb.build())
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (cont.isActive) cont.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val text = response.use {
                        if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                        val body = it.body ?: throw IOException("Пустой ответ")
                        val bytes = ByteArrayOutputStream()
                        body.byteStream().use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                if (!cont.isActive) throw IOException("Отменено")
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (bytes.size() + count > MAX_BYTES) throw IOException("Слишком большой ответ")
                                bytes.write(buffer, 0, count)
                            }
                        }
                        bytes.toString(body.contentType()?.charset()?.name() ?: "UTF-8")
                    }
                    if (cont.isActive) cont.resume(text)
                } catch (e: Exception) { if (cont.isActive) cont.resumeWithException(e) }
            }
        })
    }

    suspend fun document(url: String, headers: Map<String, String> = emptyMap()): Document =
        suspendCancellableCoroutine { cont ->
            val rb = Request.Builder().url(url).header("User-Agent", userAgent)
            headers.forEach { (k, v) -> rb.header(k, v) }
            val call = http.newCall(rb.build())
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (cont.isActive) cont.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val doc = response.use {
                            check(it.isSuccessful) { "HTTP ${it.code}" }
                            val body = it.body ?: error("Пустой ответ")
                            val bytes = ByteArrayOutputStream()
                            body.byteStream().use { input ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    if (!cont.isActive) throw IOException("Отменено")
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    check(bytes.size() + count <= MAX_BYTES) { "Слишком большая страница" }
                                    bytes.write(buffer, 0, count)
                                }
                            }
                            // baseUri — конечный адрес после редиректов: относительные ссылки резолвятся по нему
                            Jsoup.parse(ByteArrayInputStream(bytes.toByteArray()),
                                body.contentType()?.charset()?.name(), it.request.url.toString())
                        }
                        if (cont.isActive) cont.resume(doc)
                    } catch (error: Exception) {
                        if (cont.isActive) cont.resumeWithException(error)
                    }
                }
            })
        }

    private companion object {
        const val CACHE_TTL_MS = 10 * 60_000L
        const val DISK_TTL_MS = 3 * 60 * 60_000L
        const val TOTAL_TIMEOUT_MS = 20_000L
        const val MAX_BYTES = 2 * 1024 * 1024
    }
}
