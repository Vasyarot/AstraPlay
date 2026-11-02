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

    fun parse(text: String): List<Channel> {
        val out = ArrayList<Channel>(256)
        var pendingName: String? = null
        var pendingLogo: String? = null
        var pendingGroup: String? = null
        var pendingEpg: String? = null
        var extGrp: String? = null
        var seenHeader = false

        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit

                line.startsWith("#EXTM3U") -> { seenHeader = true }

                line.startsWith("#EXTINF") -> {
                    val attrs = line.substringAfter(',', "").trim()
                    pendingName = attrs.ifEmpty { null }
                    pendingLogo = attr(line, "tvg-logo")
                    pendingEpg = attr(line, "tvg-id")
                    pendingGroup = attr(line, "group-title")
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
                    )
                    pendingName = null; pendingLogo = null
                    pendingGroup = null; pendingEpg = null; extGrp = null
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
