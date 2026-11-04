package com.astraplay.tv.data.sources

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Reads public markup only. No script execution, private player API or encrypted URLs. */
internal object PublicVideoMetadata {
    fun url(base: String, raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { base.toHttpUrlOrNull()?.resolve(it) }
        ?.takeIf { it.username.isEmpty() && it.password.isEmpty() }?.toString()

    fun direct(address: String): Boolean = address.toHttpUrlOrNull()?.encodedPath?.lowercase()
        ?.let { path -> listOf(".mp4", ".mkv", ".webm", ".m3u8", ".mpd", ".m4v", ".ts").any(path::endsWith) } ?: false

    fun objects(doc: Document): List<JSONObject> {
        val result = ArrayList<JSONObject>()
        fun visit(value: Any?, depth: Int) {
            if (depth > 6 || result.size >= 1500) return
            when (value) {
                is JSONObject -> {
                    result += value
                    for (key in value.keys()) visit(value.opt(key), depth + 1)
                }
                is JSONArray -> for (i in 0 until minOf(value.length(), 1000)) visit(value.opt(i), depth + 1)
            }
        }
        for (script in doc.select("script[type=application/ld+json]").take(8)) {
            val raw = script.data().trim()
            if (raw.length > 256 * 1024) continue
            runCatching { visit(if (raw.startsWith("[")) JSONArray(raw) else JSONObject(raw), 0) }
        }
        return result
    }

    fun media(doc: Document): List<SourcePlayOption> {
        val result = linkedMapOf<String, SourcePlayOption>()
        fun add(raw: String?, type: String? = null, embedded: Boolean = false) {
            val address = url(doc.baseUri(), raw) ?: return
            val kind = when {
                direct(address) || type in setOf("video/mp4", "video/webm", "application/x-mpegURL", "application/vnd.apple.mpegurl", "application/dash+xml") -> SourcePlayKind.DIRECT
                embedded -> SourcePlayKind.EMBED
                else -> return
            }
            result.putIfAbsent(address, SourcePlayOption("Смотреть", kind, address, doc.baseUri()))
        }
        doc.select("video[src], video source[src], audio source[src]").take(20).forEach { add(it.attr("src"), it.attr("type")) }
        doc.select("[itemprop=contentUrl], meta[property=og:video], meta[property=og:video:url]").take(10)
            .forEach { add(it.attr("content").ifEmpty { it.attr("href") }) }
        for (o in objects(doc)) if (o.optString("@type").contains("VideoObject")) {
            add(o.optString("contentUrl"))
            add(o.optString("embedUrl"), embedded = true)
        }
        doc.select("[itemprop=embedUrl], #player iframe[src], .player iframe[src], .video-player iframe[src], .player-main-block iframe[src], iframe[itemprop=video][src], iframe[data-player][src]")
            .take(8).forEach { add(it.attr("src").ifEmpty { it.attr("href").ifEmpty { it.attr("content") } }, embedded = true) }
        return result.values.toList()
    }
}

/** Shared metadata adapter for providers without dedicated markup rules. */
internal class GenericDetailsParser(override val sourceId: String = "generic") : SourceDetailsParser {
    override val sourceName = when (sourceId) {
        "lostfilm" -> "LostFilm"; "filmix" -> "Filmix"; "hdrezka" -> "HDRezka"
        "eneyida" -> "Eneyida"; "anilibria" -> "AniLibria"; "zetflix" -> "Zetflix"
        else -> "Источник"
    }

    override fun parse(doc: Document): SourceDetails {
        if (DetailsParsing.isAntibotOrLogin(doc)) throw SourceDetailsException("Источник требует входа или проверки")
        val objects = PublicVideoMetadata.objects(doc)
        val data = objects.firstOrNull { it.optString("@type") in setOf("Movie", "TVSeries", "TVSeason", "TVEpisode") }
            ?: objects.firstOrNull { it.optString("@type") == "VideoObject" }
        fun clean(raw: String?) = DetailsParsing.cleanText(raw?.takeIf { it != "null" })
        fun value(key: String) = clean(data?.optString(key))
        fun property(key: String): String? = doc.selectFirst("[itemprop=$key]")?.let {
            clean(it.attr("content").ifBlank { it.attr("datetime").ifBlank { it.text() } })
        }
        fun text(css: String) = clean(doc.selectFirst(css)?.text())
        fun meta(name: String) = DetailsParsing.og(doc, name)
        val title = value("name") ?: text("h1") ?: clean(meta("og:title"))
            ?: clean(doc.title().substringBefore(" смотреть")) ?: throw SourceDetailsException("Не удалось прочитать название")
        fun names(value: Any?): List<String> = when (value) {
            is JSONArray -> (0 until minOf(value.length(), 30)).flatMap { names(value.opt(it)) }
            is JSONObject -> listOfNotNull(clean(value.optString("name")))
            is String -> listOfNotNull(clean(value))
            else -> emptyList()
        }
        val imageRaw = when (val image = data?.opt("image")) {
            is JSONObject -> image.optString("url")
            is JSONArray -> image.optString(0)
            is String -> image
            else -> doc.selectFirst("img[itemprop=image], .full-poster img, .poster img, .seasonvar-page__img img")
                ?.let { it.attr("data-src").ifBlank { it.attr("src") } } ?: meta("og:image")
        }
        val seasons = linkedMapOf<Int, MutableList<SourceEpisode>>()
        for (o in objects.filter { it.optString("@type") == "TVEpisode" }.take(1000)) {
            val number = o.optInt("episodeNumber", 0)
            val season = o.optJSONObject("partOfSeason")?.optInt("seasonNumber", 1) ?: 1
            val video = o.optJSONObject("video")
            val raw = video?.optString("contentUrl")?.takeIf { it.isNotBlank() }
                ?: video?.optString("embedUrl")?.takeIf { it.isNotBlank() } ?: o.optString("url")
            val address = PublicVideoMetadata.url(doc.baseUri(), raw)
            val kind = if (address != null && PublicVideoMetadata.direct(address)) SourcePlayKind.DIRECT
                else if (!video?.optString("embedUrl").isNullOrBlank()) SourcePlayKind.EMBED else SourcePlayKind.PAGE
            seasons.getOrPut(season) { mutableListOf() } += SourceEpisode(number,
                clean(o.optString("name")) ?: "Серия $number", address, playKind = kind)
        }
        if (seasons.isEmpty()) {
            doc.select(".episodes a[href], .episode-list a[href], a[data-episode][href]").take(1000).forEachIndexed { i, link ->
                val address = PublicVideoMetadata.url(doc.baseUri(), link.attr("href")) ?: return@forEachIndexed
                if (!PublicVideoMetadata.direct(address) && address.toHttpUrlOrNull()?.host != doc.baseUri().toHttpUrlOrNull()?.host) return@forEachIndexed
                val number = link.attr("data-episode").toIntOrNull() ?: Regex("\\d+").find(link.text())?.value?.toIntOrNull() ?: i + 1
                seasons.getOrPut(link.attr("data-season").toIntOrNull() ?: 1) { mutableListOf() } += SourceEpisode(number,
                    clean(link.text()) ?: "Серия $number", address, playKind = if (PublicVideoMetadata.direct(address)) SourcePlayKind.DIRECT else SourcePlayKind.PAGE)
            }
        }
        val series = data?.optString("@type") in setOf("TVSeries", "TVSeason", "TVEpisode") || seasons.isNotEmpty()
        val options = PublicVideoMetadata.media(doc)
        return SourceDetails(sourceId, if (sourceName == "Источник") doc.baseUri().toHttpUrlOrNull()?.host ?: sourceName else sourceName,
            doc.baseUri(), title, value("alternativeHeadline") ?: property("alternativeHeadline"), PublicVideoMetadata.url(doc.baseUri(), imageRaw),
            year = (value("datePublished") ?: property("dateCreated") ?: property("datePublished"))?.take(4),
            country = names(data?.opt("countryOfOrigin")).joinToString(", ").ifBlank { property("countryOfOrigin") ?: property("country") },
            genres = names(data?.opt("genre")).ifEmpty { doc.select("[itemprop=genre]").mapNotNull { clean(it.attr("content").ifBlank { it.text() }) }.distinct().take(8) },
            durationMin = DetailsParsing.durationMinutes(value("duration") ?: property("duration")),
            ageRating = value("contentRating") ?: property("contentRating"), rating = data?.optJSONObject("aggregateRating")?.let { "★ ${it.optString("ratingValue")} · $sourceName" }
                ?: property("ratingValue")?.let { "★ $it · $sourceName" },
            description = value("description") ?: text("[itemprop=description], .full-text, .b-post__description_text, .description, .full-story") ?: clean(meta("og:description")),
            director = names(data?.opt("director")).joinToString(", ").ifBlank { null }, actors = names(data?.opt("actor")),
            isSeries = series, seasons = seasons.entries.sortedBy { it.key }.map { (number, episodes) ->
                SourceSeason(number, "Сезон $number", episodes = episodes.distinctBy { it.url ?: "${it.number}|${it.title}" }.sortedBy { it.number }) },
            play = if (series && seasons.isNotEmpty()) null else options.firstOrNull { it.kind == SourcePlayKind.DIRECT } ?: options.firstOrNull(),
            playbackNote = if (options.isEmpty() && seasons.isEmpty()) "Источник не опубликовал доступное видео в разметке страницы." else null)
    }
}
