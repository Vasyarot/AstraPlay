package com.astraplay.tv.data.sources

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class SourceCatalog(val entries: List<OnlineTitle>, val address: String)

/** Each call has a transport deadline, a total deadline and cancellation linked to the screen. */
class SourceCatalogLoader(client: OkHttpClient, private val userAgent: String) {
    private val http = client.newBuilder().callTimeout(7, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).build()

    suspend fun load(source: OnlineSource, type: String): SourceCatalog = withTimeout(20000) {
        val candidates = linkedSetOf<String>()
        source.address?.let { candidates += it }
        if (source.id == "zetflix") {
            runCatching { document("https://t.me/s/zetflixpro") }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()?.let { page ->
                candidates += publishedZetflixAddresses(page)
            }
        }
        var lastError: Exception = IOException("Источник недоступен")
        var fallback: SourceCatalog? = null
        for (candidate in candidates.take(3)) {
            try {
                val page = document(categoryAddress(source.id, candidate, type))
                val entries = OnlineCatalogParser.parse(page, source.id)
                val result = SourceCatalog(entries, page.baseUri())
                if (entries.isNotEmpty()) return@withTimeout result
                fallback = result
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                lastError = error
            }
        }
        fallback ?: throw lastError
    }

    private suspend fun document(url: String): Document = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
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
                                if (!continuation.isActive) throw IOException("Отменено")
                                val count = input.read(buffer)
                                if (count < 0) break
                                check(bytes.size() + count <= 2 * 1024 * 1024) { "Слишком большая страница" }
                                bytes.write(buffer, 0, count)
                            }
                        }
                        Jsoup.parse(ByteArrayInputStream(bytes.toByteArray()), body.contentType()?.charset()?.name(), it.request.url.toString())
                    }
                    if (continuation.isActive) continuation.resume(doc)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    companion object {
        fun categoryAddress(id: String, base: String, type: String): String {
            val path = when (id) {
                "lostfilm" -> if (type == "movies") "/movies/" else "/series/"
                "filmix" -> if (type == "movies") "/filmi/" else "/seria/"
                "hdrezka", "eneyida" -> if (type == "movies") "/films/" else "/series/"
                "zona" -> if (type == "movies") "/movies" else "/tvseries"
                else -> return base
            }
            return base.toHttpUrl().newBuilder().encodedPath(path).query(null).fragment(null).build().toString()
        }

        fun publishedZetflixAddresses(page: Document): List<String> {
            val links = page.select(".tgme_page_description a[href], .tgme_channel_info_description a[href], .tgme_widget_message_text a[href]")
            return links.mapNotNull { link ->
                runCatching { link.absUrl("href").toHttpUrl() }.getOrNull()?.takeIf {
                    val host = it.host
                    (host == "zetflix.zone" || host.endsWith(".zetflix.zone") || host == "zetflix.pro" || host == "zetlix.zone") && it.username.isEmpty() && it.password.isEmpty()
                }?.newBuilder()?.encodedPath("/")?.query(null)?.fragment(null)?.build()?.toString()
            }.asReversed().distinct()
        }
    }
}
