package com.astraplay.tv.data.torrent

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class TorrentVideo(val index: Int, val name: String, val size: Long)

class TorrServerClient(base: String, client: OkHttpClient) {
    private val server = base.trimEnd('/').toHttpUrl()
    private val http = client.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()

    suspend fun videos(link: String): Pair<String, List<TorrentVideo>> {
        require(validLink(link)) { "Нужна magnet-ссылка или HTTP-ссылка на .torrent" }
        val added = request(JSONObject().put("action", "add").put("link", link).put("save_to_db", false))
        val hash = added.optString("hash").takeIf { Regex("[0-9a-fA-F]{40}").matches(it) } ?: error("Сервер не вернул хеш торрента")
        var status = added
        repeat(30) {
            val rows = status.optJSONArray("file_stats")
            if (rows != null && rows.length() > 0) {
                val files = (0 until rows.length()).mapNotNull { index ->
                    val file = rows.getJSONObject(index)
                    val path = file.optString("path")
                    if (path.substringAfterLast('.').lowercase() in setOf("mkv", "mp4", "avi", "mov", "webm", "m4v", "ts", "m2ts", "mpg", "mpeg"))
                        TorrentVideo(file.optInt("id", index + 1), path, file.optLong("length")) else null
                }
                check(files.isNotEmpty()) { "В торренте нет поддерживаемых видеофайлов" }
                return hash to files
            }
            delay(1000)
            status = request(JSONObject().put("action", "get").put("hash", hash))
        }
        error("Не удалось получить список файлов. Проверьте наличие раздающих.")
    }

    fun stream(hash: String, file: TorrentVideo): String = streamAddress(server.toString(), hash, file.index, file.name)

    private suspend fun request(json: JSONObject): JSONObject = suspendCancellableCoroutine { continuation ->
        val url = server.newBuilder().addPathSegment("torrents").build()
        val call = http.newCall(Request.Builder().url(url).post(json.toString().toRequestBody("application/json".toMediaType())).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) { if (continuation.isActive) continuation.resumeWithException(error) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        check(it.isSuccessful) { if (it.code == 401) "TorrServer требует авторизацию" else "TorrServer: HTTP ${it.code}" }
                        JSONObject(it.body?.string() ?: error("Пустой ответ TorrServer"))
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
            }
        })
    }

    companion object {
        fun validLink(link: String): Boolean = if (link.startsWith("magnet:?", true)) {
            Regex("(?:[?&])xt=urn:btih:(?:[0-9a-fA-F]{40}|[A-Z2-7a-z]{32})(?:&|$)", RegexOption.IGNORE_CASE).containsMatchIn(link)
        } else runCatching { link.toHttpUrl().encodedPath.endsWith(".torrent", true) }.getOrDefault(false)

        fun streamAddress(server: String, hash: String, index: Int, name: String): String = server.toHttpUrl().newBuilder()
            .addPathSegment("stream").addPathSegment(name.substringAfterLast('/'))
            .addQueryParameter("link", hash).addQueryParameter("index", index.toString()).addQueryParameter("play", "").build().toString()
    }
}
