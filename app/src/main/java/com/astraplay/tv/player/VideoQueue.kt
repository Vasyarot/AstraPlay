package com.astraplay.tv.player

import com.astraplay.tv.data.db.MediaEntry
import com.astraplay.tv.data.sources.SourcePlayKind
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Only metadata is queued. The next video is not prepared or buffered in advance. */
data class VideoItem(val itemId: String, val title: String, val url: String,
                     val kind: String, val subtitle: String? = null, val poster: String? = null,
                     val playKind: SourcePlayKind = SourcePlayKind.DIRECT,
                     val pageUrl: String? = null, val referer: String? = null) {
    fun entry(positionMs: Long = 0, durationMs: Long = 0) = MediaEntry(
        kind, itemId, title, subtitle, poster, pageUrl ?: url,
        positionMs = positionMs, durationMs = durationMs)
}

/** Kept off Intent/Binder and bounded on disk, so long seasons do not create huge parcels. */
class VideoQueueStore(private val directory: File) {
    @Synchronized fun write(owner: String, items: List<VideoItem>): String {
        require(items.isNotEmpty() && items.size <= MAX_ITEMS) { "Слишком большой список серий" }
        val key = MessageDigest.getInstance("SHA-256").digest(owner.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val json = JSONArray().apply { items.forEach { item ->
            require(validUrl(item.url)) { "Некорректный адрес видео" }
            put(JSONObject().put("id", item.itemId).put("title", item.title).put("url", item.url)
                .put("kind", item.kind).put("subtitle", item.subtitle).put("poster", item.poster)
                .put("playKind", item.playKind.name).put("page", item.pageUrl).put("referer", item.referer))
        } }.toString().toByteArray(Charsets.UTF_8)
        require(json.size <= MAX_BYTES) { "Слишком большой список серий" }
        directory.mkdirs()
        val target = File(directory, key)
        val temp = File(directory, "$key.tmp")
        temp.writeBytes(json)
        check(temp.renameTo(target)) { "Не удалось сохранить список серий" }
        directory.listFiles()?.filter { it.name != key && !it.name.endsWith(".tmp") }
            ?.sortedByDescending { it.lastModified() }?.drop(15)?.forEach { it.delete() }
        return key
    }

    @Synchronized fun read(key: String?): List<VideoItem> {
        if (key == null || !Regex("[a-f0-9]{64}").matches(key)) return emptyList()
        return runCatching {
            val file = File(directory, key)
            require(file.length() in 1..MAX_BYTES.toLong())
            val array = JSONArray(file.readText())
            require(array.length() in 1..MAX_ITEMS)
            (0 until array.length()).map { index ->
                val o = array.getJSONObject(index)
                val url = o.getString("url")
                require(validUrl(url))
                fun optional(name: String) = o.optString(name).takeIf { it.isNotBlank() && it != "null" }
                VideoItem(o.getString("id"), o.getString("title"), url, o.getString("kind"),
                    optional("subtitle"), optional("poster"),
                    SourcePlayKind.valueOf(o.getString("playKind")), optional("page"), optional("referer"))
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        const val MAX_ITEMS = 1000
        private const val MAX_BYTES = 1024 * 1024
        fun validUrl(url: String): Boolean = runCatching {
            val uri = java.net.URI(url)
            uri.scheme?.lowercase() in setOf("https", "http", "content", "file", "smb") &&
                (uri.scheme !in setOf("https", "http", "smb") || !uri.host.isNullOrBlank()) &&
                uri.rawUserInfo == null
        }.getOrDefault(false)
    }
}
