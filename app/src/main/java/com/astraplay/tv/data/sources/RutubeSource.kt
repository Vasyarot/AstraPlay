package com.astraplay.tv.data.sources

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Public RUTUBE channel/video/play-options endpoints. Signed stream addresses are resolved at playback. */
class RutubeSource(private val client: OkHttpClient, private val userAgent: String) {
    suspend fun catalog(source: OnlineSource): SourceCatalog {
        val channel = channelId(source.address.orEmpty()) ?: throw IOException("Нужен адрес канала RUTUBE")
        val entries = linkedMapOf<String, OnlineTitle>()
        for (page in 1..3) {
            val data = json("https://rutube.ru/api/video/person/$channel/?format=json&limit=50&page=$page")
            parseCatalog(data).forEach { entries[it.url] = it }
            if (entries.size >= 36 || !data.optBoolean("has_next")) break
        }
        return SourceCatalog(entries.values.take(60), source.address.orEmpty())
    }

    suspend fun details(url: String): SourceDetails {
        val id = videoId(url) ?: throw IOException("Некорректный адрес RUTUBE")
        return parseDetails(json("https://rutube.ru/api/video/$id/?format=json"), id)
    }

    suspend fun resolve(url: String): SourcePlayOption {
        val id = videoId(url) ?: throw IOException("Некорректный адрес RUTUBE")
        val options = json("https://rutube.ru/api/play/options/$id/?format=json")
        return parsePlayback(options, id)
    }

    private suspend fun json(url: String): JSONObject = withTimeout(12000) {
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(Request.Builder().url(url).header("User-Agent", userAgent)
                .header("Referer", "https://rutube.ru/").build())
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            if (!it.isSuccessful) throw IOException("RUTUBE: HTTP ${it.code}")
                            val body = it.body ?: throw IOException("Пустой ответ RUTUBE")
                            val output = ByteArrayOutputStream()
                            body.byteStream().use { input ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    if (!cont.isActive) throw IOException("Отменено")
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (output.size() + count > 2 * 1024 * 1024) throw IOException("Слишком большой ответ")
                                    output.write(buffer, 0, count)
                                }
                            }
                            JSONObject(output.toString("UTF-8"))
                        }
                        if (cont.isActive) cont.resume(result)
                    } catch (e: Exception) { if (cont.isActive) cont.resumeWithException(e) }
                }
            })
        }
    }

    companion object {
        fun isChannelSource(id: String) = id in setOf("mosfilm_rt", "lenfilm_rt")
        fun channelId(url: String): String? {
            val address = url.toHttpUrlOrNull() ?: return null
            if (address.username.isNotEmpty() || address.password.isNotEmpty()) return null
            if (address.host != "rutube.ru" && address.host != "www.rutube.ru") return null
            return Regex("^/channel/(\\d+)/?$").find(address.encodedPath)?.groupValues?.get(1)
        }
        fun videoId(url: String): String? {
            val address = url.toHttpUrlOrNull() ?: return null
            if (address.username.isNotEmpty() || address.password.isNotEmpty()) return null
            if (address.host != "rutube.ru" && address.host != "www.rutube.ru") return null
            return Regex("^/(?:video|play/embed)/([0-9a-f]{32})/?$").find(address.encodedPath)?.groupValues?.get(1)
        }
        fun embedUrl(id: String) = "https://rutube.ru/play/embed/$id/"

        fun parseCatalog(data: JSONObject): List<OnlineTitle> {
            val rows = data.optJSONArray("results") ?: return emptyList()
            return (0 until minOf(rows.length(), 100)).mapNotNull { index ->
                val video = rows.optJSONObject(index) ?: return@mapNotNull null
                val id = video.optString("id").takeIf { Regex("[0-9a-f]{32}").matches(it) } ?: return@mapNotNull null
                if (video.optDouble("duration", 0.0) < 1800 || video.optBoolean("is_livestream") || video.optBoolean("is_audio")) return@mapNotNull null
                val title = video.optString("title").take(220).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                OnlineTitle(title, "https://rutube.ru/video/$id/", PublicVideoMetadata.url("https://rutube.ru/", video.optString("thumbnail_url")))
            }.distinctBy { it.url }
        }

        fun parseDetails(video: JSONObject, id: String): SourceDetails {
            require(Regex("[0-9a-f]{32}").matches(id))
            val url = "https://rutube.ru/video/$id/"
            val title = video.optString("title").take(220)
            return SourceDetails("rutube", video.optJSONObject("author")?.optString("name")?.ifBlank { null } ?: "RUTUBE",
                url, title, poster = PublicVideoMetadata.url(url, video.optString("thumbnail_url")),
                year = Regex("\\b(19\\d{2}|20\\d{2})\\b").find(title)?.value,
                durationMin = (video.optDouble("duration") / 60).toInt(),
                description = DetailsParsing.cleanText(video.optString("description"))?.take(8000),
                play = SourcePlayOption("Смотреть", SourcePlayKind.PAGE, url, url))
        }

        fun parsePlayback(options: JSONObject, id: String): SourcePlayOption {
            require(Regex("[0-9a-f]{32}").matches(id))
            val embed = embedUrl(id)
            val drm = options.optBoolean("is_drm") || options.optBoolean("drm") || options.optJSONObject("drm")?.length()?.let { it > 0 } == true
            val raw = if (drm) null else options.optJSONObject("video_balancer")?.optString("m3u8")
            val stream = raw?.toHttpUrlOrNull()?.takeIf { it.username.isEmpty() && it.password.isEmpty() }
            return if (stream != null) SourcePlayOption("Смотреть", SourcePlayKind.DIRECT, stream.toString(), "https://rutube.ru/", embed)
            else SourcePlayOption("Плеер источника", SourcePlayKind.EMBED, embed, "https://rutube.ru/")
        }
    }
}
