package com.astraplay.tv.data.torrent

import com.astraplay.tv.data.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URLEncoder

/**
 * Источник видео: Internet Archive — легальный открытый архив
 * (public domain / свободные лицензии). Отдаёт и прямые MP4-ссылки для
 * стрима, и .torrent-файлы для внешних торрент-приложений.
 *
 * Архитектура допускает добавление своих источников (см. README).
 */
data class ArchiveFile(
    val name: String,
    val format: String,
    val sizeBytes: Long,
    val playUrl: String?,     // прямой mp4/mkv для стрима
    val torrentUrl: String?,  // .torrent для внешнего приложения
)

data class ArchiveItem(
    val identifier: String,
    val title: String,
    val year: String,
    val description: String,
    val posterUrl: String,
) {
    val key: String get() = "ia_$identifier"
}

class ArchiveSource(private val http: Http) {

    /** Поиск фильмов по названию; пустой запрос — популярные классические фильмы. */
    suspend fun search(query: String, page: Int = 1): Result<List<ArchiveItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val q = if (query.isBlank()) {
                    "collection:(feature_films) AND mediatype:(movies)"
                } else {
                    "title:(\"${escape(query)}\") AND mediatype:(movies)"
                }
                val sort = if (query.isBlank()) "&sort[]=downloads+desc" else ""
                val url = "https://archive.org/advancedsearch.php" +
                        "?q=" + URLEncoder.encode(q, "UTF-8") +
                        "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=year&fl%5B%5D=description" +
                        "&rows=30&page=$page$sort&output=json"
                val json = http.getJson(url)
                val docs = json.optJSONObject("response")
                    ?.optJSONArray("docs") ?: JSONArray()
                (0 until docs.length()).mapNotNull { i ->
                    val d = docs.getJSONObject(i)
                    val identifier = d.optString("identifier")
                    if (identifier.isEmpty()) null
                    else ArchiveItem(
                        identifier = identifier,
                        title = d.optString("title").ifEmpty { identifier },
                        year = when (val y = d.opt("year")) {
                            is Int -> y.toString()
                            is String -> y
                            else -> ""
                        },
                        description = stripHtml(d.optString("description")),
                        posterUrl = "https://archive.org/services/img/$identifier",
                    )
                }
            }
        }

    /** Файлы элемента: прямые видео-ссылки + torrent. */
    suspend fun files(identifier: String): Result<List<ArchiveFile>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val json = http.getJson("https://archive.org/metadata/$identifier")
                val files = json.optJSONArray("files") ?: JSONArray()
                val out = ArrayList<ArchiveFile>(8)
                (0 until files.length()).forEach { i ->
                    val f = files.getJSONObject(i)
                    val name = f.optString("name")
                    val format = f.optString("format")
                    val size = f.optString("size").toLongOrNull() ?: 0L
                    val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
                    val isTorrent = name.endsWith(".torrent")
                    val isPlayable = listOf("h.264", "mpeg4", "512kb mpeg4", "matroska")
                        .any { format.equals(it, ignoreCase = true) } ||
                            name.endsWith(".mp4") || name.endsWith(".mkv")
                    if (isPlayable || isTorrent) {
                        out += ArchiveFile(
                            name = name,
                            format = format,
                            sizeBytes = size,
                            playUrl = if (isPlayable)
                                "https://archive.org/download/$identifier/$encoded" else null,
                            torrentUrl = if (isTorrent)
                                "https://archive.org/download/$identifier/$encoded" else null,
                        )
                    }
                }
                out
            }
        }

    private fun escape(q: String): String = q.replace("\"", " ")

    private fun stripHtml(s: String): String =
        s.replace(Regex("<[^>]*>"), "").trim().take(400)
}
