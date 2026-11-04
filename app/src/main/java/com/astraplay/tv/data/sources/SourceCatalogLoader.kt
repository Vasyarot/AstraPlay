package com.astraplay.tv.data.sources

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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

/**
 * Загрузчик HTML-каталогов. Транспортный дедлайн на запрос, общий дедлайн
 * на вызов и отмена, связанная с экраном. Каждый адрес пробуется сначала
 * с UA приложения, затем с браузерным UA (часть сайтов требует один из двух).
 */
class SourceCatalogLoader(
    client: OkHttpClient,
    private val userAgent: String,
    private val fallbackUserAgent: String = userAgent,
    /** Таймауты запроса: null — оставить настройки переданного клиента. */
    private val callTimeoutMs: Long? = 15000,
    private val readTimeoutMs: Long? = 10000,
) {
    private val http = run {
        val b = client.newBuilder()
        callTimeoutMs?.let { b.callTimeout(it, TimeUnit.MILLISECONDS) }
        readTimeoutMs?.let { b.readTimeout(it, TimeUnit.MILLISECONDS) }
        b.build()
    }

    /** Для проверки таймаутов в юнит-тестах: настройки клиента не должны перезаписываться. */
    internal val effectiveClient: OkHttpClient get() = http

    /** [pathOverride] — путь раздела; [totalTimeoutMs] — общий дедлайн вызова. */
    suspend fun load(source: OnlineSource, type: String, pathOverride: String? = null, totalTimeoutMs: Long = 30000): SourceCatalog = withTimeout(totalTimeoutMs) {
        if (RutubeSource.isChannelSource(source.id)) return@withTimeout RutubeSource(http, userAgent).catalog(source)
        val candidates = candidateAddresses(source, type, pathOverride).toMutableSet()
        if (pathOverride == null && source.id == "zetflix") {
            runCatching { document("https://t.me/s/zetflixpro", userAgent) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()?.let { page ->
                candidates += publishedZetflixAddresses(page)
            }
        }
        var lastError: Exception = IOException("Источник недоступен")
        var fallback: SourceCatalog? = null
        // Бюджет каждого кандидата: медленный основной адрес не должен лишать
        // зеркало шанса ответить. Общий дедлайн остаётся жёсткой границей.
        val list = candidates.take(4).toList()
        val budgetMs = maxOf(3500L, totalTimeoutMs / maxOf(1, list.size))
        for (candidate in list) {
            // Выбранный путь раздела сохраняется: перезапись пути категорией
            // допускается только когда раздел не задан явно.
            val address = if (pathOverride != null) candidate else categoryAddress(source.id, candidate, type)
            val result = withTimeoutOrNull(budgetMs) {
                // второй UA — страховка для сайтов, фильтрующих по User-Agent
                for (ua in if (fallbackUserAgent == userAgent) listOf(userAgent) else listOf(userAgent, fallbackUserAgent)) {
                    // Некоторые серверы сбрасывают первые соединения (сброс TLS/HTTP2-стрима) —
                    // как браузеры, повторяем транспортную попытку до 3 раз с паузой.
                    var attempt = 0
                    while (true) {
                        attempt++
                        try {
                            val page = document(address, ua)
                            val entries = OnlineCatalogParser.parse(page, source.id)
                            if (entries.isNotEmpty()) {
                                android.util.Log.i("SourceCatalog", "${source.id}: OK ${entries.size} карточек ← $address (UA=${ua.take(16)}…, попытка $attempt)")
                                return@withTimeoutOrNull SourceCatalog(entries, page.baseUri())
                            }
                            fallback = SourceCatalog(entries, page.baseUri())
                            android.util.Log.w("SourceCatalog", "${source.id}: 0 карточек (UA=${ua.take(24)}…, ${page.baseUri()})")
                            break
                        } catch (error: Exception) {
                            // любая отмена (бюджет кандидата, общий дедлайн, уход с экрана)
                            // распространяется корректно: withTimeoutOrNull вернёт null,
                            // внешний withTimeout бросит TimeoutCancellationException
                            if (error is kotlinx.coroutines.CancellationException) throw error
                            lastError = error
                            android.util.Log.w("SourceCatalog", "${source.id}: $address UA=${ua.take(24)}… попытка $attempt → ${error.javaClass.simpleName}: ${error.message}")
                            if (attempt < 3 && error is java.io.IOException) {
                                delay(1000L * attempt)
                            } else break
                        }
                    }
                }
                null
            }
            if (result != null) return@withTimeout result
        }
        fallback ?: throw lastError
    }

    private suspend fun document(url: String, userAgent: String): Document = suspendCancellableCoroutine { continuation ->
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
        /** Запасные зеркала на случай падения основного адреса (пробуются в порядке перечисления). */
        private fun alternateHosts(id: String): List<String> = when (id) {
            "seasonvar" -> listOf("https://seasonvar.ru")
            "zona" -> listOf("https://w140.zona.plus", "https://g4.zona.plus")
            "lostfilm" -> listOf("https://www.lostfilm.tv")
            "hdrezka" -> listOf("https://rezka.ag")
            "filmix" -> listOf("https://filmix.pub")
            "eneyida" -> listOf("https://eneyida.tv")
            else -> emptyList()
        }

        /** Разделы каталога под каждый сайт: [movies, series]. null — страница по умолчанию. */
        fun categoryAddress(id: String, base: String, type: String): String {
            val path = when (id) {
                "lostfilm" -> if (type == "movies") "/movies/" else "/series/"
                "filmix" -> if (type == "movies") "/filmi/" else "/seria/"
                "hdrezka", "eneyida" -> if (type == "movies") "/films/" else "/series/"
                "zona" -> if (type == "movies") "/movies/filter/sort-rating" else "/tvseries/filter/sort-rating"
                "smotrim" -> if (type == "movies") "/movie" else "/serials"
                "seasonvar" -> if (type == "movies") null else "/"
                "anilibria" -> "/anime/releases/release"
                else -> return base
            } ?: return base
            return base.toHttpUrl().newBuilder().encodedPath(path).query(null).fragment(null).build().toString()
        }

        /**
         * Адреса-кандидаты каталога: основной + зеркала. Выбранный пользователем
         * путь раздела ([pathOverride]) сохраняется как есть и не перезаписывается
         * путём по умолчанию — иначе «Новинки» Zona тихо превращались в «По рейтингу».
         */
        internal fun candidateAddresses(source: OnlineSource, type: String, pathOverride: String?): List<String> {
            val out = linkedSetOf<String>()
            source.address?.let { address ->
                out += if (pathOverride != null)
                    address.toHttpUrl().newBuilder().encodedPath(pathOverride).query(null).fragment(null).build().toString()
                else categoryAddress(source.id, address, type)
                for (mirror in alternateHosts(source.id)) {
                    if (address.startsWith(mirror, ignoreCase = true)) continue
                    out += if (pathOverride != null)
                        mirror.toHttpUrl().newBuilder().encodedPath(pathOverride).query(null).fragment(null).build().toString()
                    else categoryAddress(source.id, mirror, type)
                }
            }
            return out.toList()
        }

        fun publishedZetflixAddresses(page: Document): List<String> {
            val links = page.select(".tgme_page_description a[href], .tgme_channel_info_description a[href], .tgme_widget_message_text a[href]")
            return links.mapNotNull { link ->
                runCatching { link.absUrl("href").toHttpUrl() }.getOrNull()?.takeIf {
                    val host = it.host
                    val zetflix = host == "zetflix.zone" || host.endsWith(".zetflix.zone") || host == "zetflix.pro" ||
                        host == "zetlix.zone" || host == "zetflix.online" || host.endsWith(".zetflix.online") ||
                        host == "zet-flix.online" || host.endsWith(".zet-flix.online")
                    zetflix && it.username.isEmpty() && it.password.isEmpty()
                }?.newBuilder()?.encodedPath("/")?.query(null)?.fragment(null)?.build()?.toString()
            }.asReversed().distinct()
        }
    }
}
