package com.astraplay.tv.data.iptv

import android.content.Context
import android.util.Xml
import com.astraplay.tv.data.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class Programme(
    val channelId: String,
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val desc: String? = null,
)

/**
 * Телепрограмма (XMLTV). Держим в памяти только текущие сутки вокруг «сейчас» —
 * критично для 1 ГБ RAM. Кэш на диск не пишем: EPG обновляется дёшево.
 */
class EpgStore(private val http: Http) {

    /** epgId (tvg-id / channel id) -> передачи текущего окна. */
    @Volatile private var byId: Map<String, List<Programme>> = emptyMap()
    private var loadedFromUrl: String? = null
    private var loadedAt = 0L
    private var aliases: Map<String, String> = emptyMap()
    private var wantedKeys: Set<String>? = null
    private var loadedKeys: Set<String>? = null
    private var automaticAttempt = 0L
    private val autoMutex = Mutex()
    private val mutex = Mutex()

    val isLoaded: Boolean get() = byId.isNotEmpty()
    val sourceUrl: String? get() = loadedFromUrl

    suspend fun ensureForChannels(channels: List<Channel>, preferredUrl: String = "", force: Boolean = false): Result<Unit> = autoMutex.withLock {
        if (channels.isEmpty()) return@withLock Result.failure(IllegalStateException("Нет каналов"))
        val keys = channels.flatMap { EpgKeys.channel(it) }.toSet()
        if (!force && loadedKeys == keys && System.currentTimeMillis() - automaticAttempt < 15 * 60_000L)
            return@withLock if (isLoaded) Result.success(Unit) else Result.failure(IllegalStateException("Нет программы"))
        automaticAttempt = System.currentTimeMillis()
        var failure: Throwable = IllegalStateException("Нет программы для этих каналов")
        val urls = (listOf(preferredUrl).filter { it.isNotBlank() } + listOf(
            "https://www.teleguide.info/download/new3/xmltv.xml.gz",
            "https://iptvx.one/epg/epg_lite.xml.gz")).distinct()
        for (url in urls) {
            val result = ensureLoaded(url, force, keys)
            if (result.isSuccess && isLoaded) return@withLock result
            failure = result.exceptionOrNull() ?: failure
        }
        Result.failure(failure)
    }

    /** Загружает XMLTV по URL (поддерживает .gz). Повторный вызов с тем же URL — из кэша в памяти. */
    suspend fun ensureLoaded(url: String, force: Boolean = false, channelKeys: Set<String>? = null): Result<Unit> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!force && isLoaded && loadedFromUrl == url && loadedKeys == channelKeys && System.currentTimeMillis() - loadedAt < 6 * 3600_000L) return@withLock Result.success(Unit)
                try {
                    wantedKeys = channelKeys
                    val programs = http.client.newBuilder().callTimeout(45, java.util.concurrent.TimeUnit.SECONDS).build().newCall(
                        okhttp3.Request.Builder().url(url).build()
                    ).execute().use { resp ->
                        if (!resp.isSuccessful) error("HTTP ${resp.code}")
                        val stream = java.io.PushbackInputStream(resp.body!!.byteStream(), 2)
                        val first = stream.read()
                        val second = stream.read()
                        if (second != -1) stream.unread(second)
                        if (first != -1) stream.unread(first)
                        val xml = if (first == 0x1f && second == 0x8b) java.util.zip.GZIPInputStream(stream) else stream
                        xml.use { parseXmltv(it) }
                    }
                    val grouped = programs.groupBy { it.channelId }.mapValues { (_, rows) -> rows.sortedBy { it.startMs } }
                    val indexed = HashMap<String, List<Programme>>(grouped)
                    for ((name, id) in aliases) grouped[id]?.let { rows ->
                        indexed[name] = rows
                        indexed[EpgKeys.normalize(name)] = rows
                    }
                    for ((id, rows) in grouped) indexed[EpgKeys.normalize(id)] = rows
                    byId = indexed
                    loadedFromUrl = url
                    loadedAt = System.currentTimeMillis()
                    loadedKeys = channelKeys
                    if (isLoaded) Result.success(Unit) else Result.failure(IllegalStateException("Подходящая программа не найдена"))
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Result.failure(e)
                }
            }
        }

    /** Передача, идущая сейчас на канале. */
    fun nowFor(epgId: String?, nowMs: Long = System.currentTimeMillis()): Programme? =
        epgId?.let { id ->
            (byId[id] ?: byId[EpgKeys.normalize(id)])?.firstOrNull { nowMs in it.startMs until it.stopMs }
        }

    /** Ближайшие N передач канала, начиная с текущей. */
    fun upcoming(epgId: String?, count: Int = 6): List<Programme> {
        if (epgId == null) return emptyList()
        val now = System.currentTimeMillis()
        return (byId[epgId] ?: byId[EpgKeys.normalize(epgId)])?.filter { it.stopMs > now }?.take(count) ?: emptyList()
    }

    /** Все передачи канала (по tvg-id или имени) в окне [fromMs..toMs]. */
    fun forChannel(epgId: String?, name: String?, fromMs: Long, toMs: Long): List<Programme> {
        val list = byId[epgId ?: ""] ?: byId[name ?: ""] ?: byId[EpgKeys.normalize(epgId.orEmpty())] ?: byId[EpgKeys.normalize(name.orEmpty())] ?: return emptyList()
        return list.filter { it.stopMs > fromMs && it.startMs < toMs }.sortedBy { it.startMs }
    }

    fun clear() { byId = emptyMap(); loadedFromUrl = null; automaticAttempt = 0 }

    // ---------- XMLTV ----------

    private val fmtUtc = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun parseXmltv(input: InputStream): List<Programme> {
        val dayStart = System.currentTimeMillis() - 12L * 3600_000
        val dayEnd = System.currentTimeMillis() + 36L * 3600_000
        val out = ArrayList<Programme>(512)
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
        var channelMap = HashMap<String, String>(64) // xml id -> xml id (проброс), может расширяться displayName
        val relevant = HashSet<String>()
        val allowedChannels = HashMap<String, Boolean>()
        var definition: String? = null
        var curChannel: String? = null
        var curStart = 0L; var curStop = 0L; var curTitle: String? = null; var curDesc: String? = null
        var inTitle = false; var inDesc = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "channel" -> {
                        val id = parser.getAttributeValue(null, "id")
                        if (id != null) channelMap[id] = id
                        curChannel = id
                        definition = id
                        if (id != null) {
                            val allowed = wantedKeys == null || EpgKeys.normalize(id) in wantedKeys!!
                            allowedChannels[id] = allowed
                            if (allowed) relevant += id
                        }
                    }
                    "display-name" -> if (curChannel != null) {
                        // отображаемое имя канала маппим как дополнительный ключ
                        val name = parser.nextText().trim()
                        if (name.isNotEmpty()) channelMap[name] = channelMap[curChannel] ?: curChannel!!
                        definition?.let { if (wantedKeys == null || EpgKeys.normalize(name) in wantedKeys!!) { relevant += it; allowedChannels[it] = true } }
                    }
                    "programme" -> {
                        curChannel = parser.getAttributeValue(null, "channel")
                        curStart = parseTime(parser.getAttributeValue(null, "start"))
                        curStop = parseTime(parser.getAttributeValue(null, "stop"))
                        curTitle = null; curDesc = null
                        val allowed = wantedKeys == null || allowedChannels.getOrPut(curChannel.orEmpty()) { EpgKeys.normalize(curChannel.orEmpty()) in wantedKeys!! }
                        if (!allowed || curStop <= dayStart || curStart >= dayEnd) {
                            var depth = 1
                            while (depth > 0) when (parser.next()) {
                                XmlPullParser.START_TAG -> depth++
                                XmlPullParser.END_TAG -> depth--
                                XmlPullParser.END_DOCUMENT -> break
                            }
                            curChannel = null
                        }
                    }
                    "title" -> { inTitle = true; curTitle = parser.nextText().trim(); inTitle = false }
                    "desc" -> { inDesc = true; curDesc = parser.nextText().trim().ifEmpty { null }; inDesc = false }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "channel" -> { curChannel = null; definition = null }
                    "programme" -> {
                        val ch = curChannel
                        val title = curTitle
                        if (ch != null && title != null && curStop > dayStart && curStart < dayEnd && curStop > curStart && out.size < 50000) {
                            out += Programme(ch, curStart, curStop, title.take(180), curDesc?.take(512))
                        }
                    }
                }
            }
            event = parser.next()
        }
        aliases = channelMap
        return out
    }

    /** XMLTV-время: 20240101120000 +0000 (зона опциональна). */
    private fun parseTime(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        return try {
            val value = raw.trim()
            val normalized = if (value.length > 14) value else value + " +0000"
            fmtUtc.parse(normalized)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
