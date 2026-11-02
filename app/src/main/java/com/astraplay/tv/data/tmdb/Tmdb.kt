package com.astraplay.tv.data.tmdb

import androidx.annotation.StringRes
import com.astraplay.tv.R
import com.astraplay.tv.core.Prefs
import com.astraplay.tv.data.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class TmdbItem(
    val id: Int,
    val mediaType: String,            // movie | tv
    val title: String,
    val overview: String,
    val posterPath: String?,
    val backdropPath: String?,
    val rating: Double,
    val year: String,
    val genreIds: List<Int> = emptyList(),
) {
    val key: String get() = "tmdb_${mediaType}_$id"
}

data class Episode(
    val number: Int,
    val name: String,
    val airDate: String,
    val still: String?,
)

data class Season(
    val number: Int,
    val name: String,
    val episodes: List<Episode>,
)

data class TmdbDetail(
    val item: TmdbItem,
    val tagline: String,
    val runtimeMin: Int,              // для movie
    val genres: List<String>,
    val trailerKey: String?,          // YouTube
    val seasons: List<Season>,        // для tv
)

/** Разделы каталога. */
enum class CatalogSection(val path: String, val titleRes: Int) {
    TRENDING("/trending/all/week", R.string.section_trending),
    POPULAR_MOVIES("/movie/popular", R.string.section_popular_movies),
    TOP_MOVIES("/movie/top_rated", R.string.section_top_movies),
    NOW_PLAYING("/movie/now_playing", R.string.section_now_playing),
    POPULAR_TV("/tv/popular", R.string.section_popular_tv),
    TOP_TV("/tv/top_rated", R.string.section_top_tv),
}

/** Клиент TMDB (бесплатный API). Ключ задаётся в настройках или gradle.properties. */
class TmdbClient(private val http: Http, private val prefs: Prefs) {

    private val base = "https://api.themoviedb.org/3"

    val available: Boolean get() = prefs.tmdbKey.isNotBlank()

    private fun lang() = "ru-RU"

    private suspend fun call(path: String, params: String = ""): JSONObject =
        withContext(Dispatchers.IO) {
            val key = URLEncoder.encode(prefs.tmdbKey, "UTF-8")
            val sep = if (params.isEmpty()) "" else "&"
            http.getJson("$base$path?api_key=$key&language=${lang()}$sep$params")
        }

    suspend fun section(section: CatalogSection, page: Int = 1): Result<List<TmdbItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val json = call(section.path, "page=$page")
                parseItems(json.optJSONArray("results") ?: JSONArray())
            }
        }

    suspend fun search(query: String, page: Int = 1): Result<List<TmdbItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val q = URLEncoder.encode(query, "UTF-8")
                val json = call("/search/multi", "query=$q&page=$page&include_adult=false")
                parseItems(json.optJSONArray("results") ?: JSONArray())
                    .filter { it.mediaType == "movie" || it.mediaType == "tv" }
            }
        }

    suspend fun detail(id: Int, mediaType: String): Result<TmdbDetail> =
        withContext(Dispatchers.IO) {
            runCatching {
                val json = call("/$mediaType/$id", "append_to_response=videos")
                val item = parseItem(json, mediaType)
                val genres = json.optJSONArray("genres")?.let { arr ->
                    (0 until arr.length()).map { arr.getJSONObject(it).optString("name") }
                } ?: emptyList()
                val trailerKey = json.optJSONObject("videos")
                    ?.optJSONArray("results")?.let { vids ->
                        (0 until vids.length())
                            .asSequence()
                            .map { vids.getJSONObject(it) }
                            .filter { it.optString("site") == "YouTube" }
                            .sortedByDescending { it.optString("official").toBoolean() }
                            .firstOrNull()?.optString("key")
                    }
                val seasons = if (mediaType == "tv") {
                    json.optJSONArray("seasons")?.let { arr ->
                        (0 until arr.length()).mapNotNull { s ->
                            val so = arr.getJSONObject(s)
                            val epsJson = so.optJSONArray("episodes")
                            if (epsJson == null) null
                            else Season(
                                number = so.optInt("season_number"),
                                name = so.optString("name"),
                                episodes = (0 until epsJson.length()).map { e ->
                                    val eo = epsJson.getJSONObject(e)
                                    Episode(
                                        number = eo.optInt("episode_number"),
                                        name = eo.optString("name"),
                                        airDate = eo.optString("air_date"),
                                        still = eo.optString("still_path").ifEmpty { null },
                                    )
                                }
                            )
                        }
                    } ?: emptyList()
                } else emptyList()
                TmdbDetail(
                    item = item,
                    tagline = json.optString("tagline"),
                    runtimeMin = json.optInt("runtime"),
                    genres = genres,
                    trailerKey = trailerKey,
                    seasons = seasons,
                )
            }
        }

    // ---------- парсинг ----------

    private fun parseItems(arr: JSONArray): List<TmdbItem> =
        (0 until arr.length()).mapNotNull { i ->
            runCatching { parseItem(arr.getJSONObject(i), null) }.getOrNull()
        }

    private fun parseItem(o: JSONObject, forcedType: String?): TmdbItem {
        val type = forcedType ?: o.optString("media_type", "movie")
        val title = when (type) {
            "tv" -> o.optString("name")
            else -> o.optString("title")
        }.ifEmpty { o.optString("original_title").ifEmpty { o.optString("original_name") } }
        val date = when (type) {
            "tv" -> o.optString("first_air_date")
            else -> o.optString("release_date")
        }
        return TmdbItem(
            id = o.optInt("id"),
            mediaType = type,
            title = title,
            overview = o.optString("overview"),
            posterPath = o.optString("poster_path").ifEmpty { null },
            backdropPath = o.optString("backdrop_path").ifEmpty { null },
            rating = o.optDouble("vote_average", 0.0),
            year = if (date.length >= 4) date.substring(0, 4) else "",
            genreIds = o.optJSONArray("genre_ids")?.let { g ->
                (0 until g.length()).map { g.getInt(it) }
            } ?: emptyList(),
        )
    }

    // ---------- картинки ----------

    fun posterUrl(path: String?, width: Int = 342): String? =
        path?.let { "https://image.tmdb.org/t/p/w$width$it" }

    fun backdropUrl(path: String?): String? =
        path?.let { "https://image.tmdb.org/t/p/w780$it" }
}
