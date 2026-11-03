package com.astraplay.tv.data.iptv

/**
 * Канал IPTV. [id] детерминированный — нужен для избранного и позиций.
 */
data class Channel(
    val id: Long = 0L,
    val playlistId: Long = 0L,
    val name: String,
    val url: String,
    val logo: String? = null,
    val group: String? = null,
    val epgId: String? = null,
    val userAgent: String? = null,
    val referrer: String? = null,
) {
    val key: String get() = "ch_${url.hashCode()}_${name.hashCode()}"
}

data class Playlist(
    val id: Long,
    val name: String,
    val url: String,
    val source: String = "url", // url | file
    val sortOrder: Int = 0,
    val updatedAt: Long = 0,
)

/**
 * Парсер M3U/M3U8-плейлистов с IPTV-атрибутами:
 * #EXTINF:-1 tvg-id="1" tvg-logo="http://..." group-title="Новости",Первый канал
 */
object M3uParser {

    fun forPlaylist(text: String, playlistUrl: String): List<Channel> {
        val category = playlistUrl.substringAfter("#astra-category=", "").substringBefore('&')
        val parsed = parse(text)
        return if (category.isEmpty()) parsed else parsed.filter { channel ->
            channel.group.orEmpty().split(Regex("[;/|]")).any { it.trim().equals(category, ignoreCase = true) }
        }
    }

    fun epgUrl(text: String): String? {
        val header = text.lineSequence().firstOrNull { it.trim().removePrefix("\uFEFF").startsWith("#EXTM3U") } ?: return null
        return (attr(header, "x-tvg-url") ?: attr(header, "url-tvg") ?: attr(header, "tvg-url"))
            ?.split(',')?.firstOrNull()?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }

    fun parse(text: String): List<Channel> {
        val out = ArrayList<Channel>(256)
        var pendingName: String? = null
        var pendingLogo: String? = null
        var pendingGroup: String? = null
        var pendingEpg: String? = null
        var extGrp: String? = null
        var userAgent: String? = null
        var referrer: String? = null


        text.lineSequence().forEach { raw ->
            val line = raw.trim().removePrefix("\uFEFF")
            when {
                line.isEmpty() -> Unit

                line.startsWith("#EXTM3U") -> Unit

                line.startsWith("#EXTINF") -> {
                    var quoted = false
                    val separator = line.indexOfFirst { ch ->
                        if (ch == '"') quoted = !quoted
                        ch == ',' && !quoted
                    }
                    val attrs = if (separator >= 0) line.substring(separator + 1).trim() else ""
                    val tvgName = attr(line, "tvg-name")
                    pendingName = if (tvgName != null && tvgName.any { it in 'А'..'я' || it == 'Ё' || it == 'ё' }) tvgName else attrs.ifEmpty { tvgName }
                    pendingLogo = attr(line, "tvg-logo")
                    pendingEpg = attr(line, "tvg-id")
                    pendingGroup = attr(line, "group-title")
                }

                line.startsWith("#EXTVLCOPT:http-user-agent=", ignoreCase = true) -> {
                    userAgent = line.substringAfter('=').trim().ifEmpty { null }
                }
                line.startsWith("#EXTVLCOPT:http-referrer=", ignoreCase = true) ||
                    line.startsWith("#EXTVLCOPT:http-referer=", ignoreCase = true) -> {
                    referrer = line.substringAfter('=').trim().ifEmpty { null }
                }

                line.startsWith("#EXTGRP") -> {
                    extGrp = line.substringAfter(':').trim().ifEmpty { null }
                }

                line.startsWith("#") -> Unit // прочие директивы игнорируем

                else -> {
                    // Строка-URL: завершает запись
                    val name = pendingName ?: line.substringBeforeLast('/')
                        .substringAfterLast('/').ifEmpty { "Канал" }
                    out += Channel(
                        name = name,
                        url = line,
                        logo = pendingLogo,
                        group = pendingGroup ?: extGrp,
                        epgId = pendingEpg,
                        userAgent = userAgent, referrer = referrer,
                    )
                    pendingName = null; pendingLogo = null
                    pendingGroup = null; pendingEpg = null; extGrp = null
                    userAgent = null; referrer = null
                }
            }
        }
        return out
    }

    /** Достаёт значение атрибута вида key="value". */
    private fun attr(line: String, key: String): String? {
        val idx = line.indexOf("$key=\"", ignoreCase = true)
        if (idx < 0) return null
        val start = idx + key.length + 2
        val end = line.indexOf('"', start)
        return if (end > start) line.substring(start, end) else null
    }
}
