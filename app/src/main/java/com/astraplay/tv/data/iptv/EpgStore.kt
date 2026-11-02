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
    private var byId: Map<String, List<Programme>> = emptyMap()
    private var loadedFromUrl: String? = null
    private val mutex = Mutex()

    val isLoaded: Boolean get() = byId.isNotEmpty()

    /** Загружает XMLTV по URL (поддерживает .gz). Повторный вызов с тем же URL — из кэша в памяти. */
    suspend fun ensureLoaded(url: String, force: Boolean = false): Result<Unit> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!force && isLoaded && loadedFromUrl == url) return@withLock Result.success(Unit)
                try {
                    val programs = parseXmltv(http.client.newCall(
                        okhttp3.Request.Builder().url(url).build()
                    ).execute().use { resp ->
                        if (!resp.isSuccessful) error("HTTP ${resp.code}")
                        resp.body!!.byteStream()
                    })
                    byId = programs.groupBy { it.channelId }
                    loadedFromUrl = url
                    Result.success(Unit)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
        }

    /** Передача, идущая сейчас на канале. */
    fun nowFor(epgId: String?, nowMs: Long = System.currentTimeMillis()): Programme? =
        epgId?.let { id ->
            byId[id]?.firstOrNull { nowMs in it.startMs until it.stopMs }
        }

    /** Ближайшие N передач канала, начиная с текущей. */
    fun upcoming(epgId: String?, count: Int = 6): List<Programme> {
        if (epgId == null) return emptyList()
        val now = System.currentTimeMillis()
        return byId[epgId]?.filter { it.stopMs > now }?.take(count) ?: emptyList()
    }

    fun clear() { byId = emptyMap(); loadedFromUrl = null }

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
                    }
                    "display-name" -> if (curChannel != null) {
                        // отображаемое имя канала маппим как дополнительный ключ
                        val name = parser.nextText().trim()
                        if (name.isNotEmpty()) channelMap[name] = channelMap[curChannel] ?: curChannel!!
                    }
                    "programme" -> {
                        curChannel = parser.getAttributeValue(null, "channel")
                        curStart = parseTime(parser.getAttributeValue(null, "start"))
                        curStop = parseTime(parser.getAttributeValue(null, "stop"))
                        curTitle = null; curDesc = null
                    }
                    "title" -> { inTitle = true; curTitle = parser.nextText().trim(); inTitle = false }
                    "desc" -> { inDesc = true; curDesc = parser.nextText().trim().ifEmpty { null }; inDesc = false }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "programme" -> {
                        val ch = curChannel
                        val title = curTitle
                        if (ch != null && title != null && curStart >= dayStart && curStart <= dayEnd) {
                            out += Programme(ch, curStart, curStop, title, curDesc)
                        }
                    }
                }
            }
            event = parser.next()
        }
        return out
    }

    /** XMLTV-время: 20240101120000 +0000 (зона опциональна). */
    private fun parseTime(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        return try {
            val normalized = if (raw.length >= 15) raw else raw + " +0000"
            fmtUtc.parse(normalized)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
