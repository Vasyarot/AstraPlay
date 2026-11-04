package com.astraplay.tv.data.db

import android.content.ContentValues
import android.content.Context
import com.astraplay.tv.data.iptv.Channel
import com.astraplay.tv.data.iptv.Playlist

/** Типы медиа-записей. */
object Kinds {
    const val CHANNEL = "channel"
    const val MOVIE = "movie"
    const val SERIES = "series"
    const val LOCAL = "local"
    const val TORRENT = "torrent"
    const val WEB = "web"
}

/** SMB-сервер из списка. */
data class SmbServer(
    val id: Long,
    val name: String,
    val base: String,   // smb://host/share без учётных данных
    val user: String?,  // null/пусто — гость
    val pass: String?,
) {
    /** Полный URL с учётными данными (только для подключения, не для хранения). */
    fun url(path: String = ""): String {
        val cleanBase = base.trimEnd('/')
        val suffix = if (path.startsWith("/")) path else "/$path"
        return if (user.isNullOrBlank()) "$cleanBase$suffix"
        else {
            val encUser = java.net.URLEncoder.encode(user, "UTF-8")
            val encPass = java.net.URLEncoder.encode(pass ?: "", "UTF-8")
            "smb://$encUser:$encPass@" + cleanBase.removePrefix("smb://") + suffix
        }
    }

    /** URL без учётных данных — для хранения в записях, истории и бэкапе. */
    fun urlNoAuth(path: String = ""): String {
        val cleanBase = base.trimEnd('/')
        val suffix = if (path.startsWith("/")) path else "/$path"
        return "$cleanBase$suffix"
    }
}

/** Единая модель для избранного/истории/позиций — упрощает адаптеры. */
data class MediaEntry(
    val kind: String,
    val itemId: String,
    val title: String,
    val subtitle: String? = null,
    val poster: String? = null,
    val url: String? = null,
    val category: String = "",
    val time: Long = 0L,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
)

/** Все операции с пользовательскими данными. */
class Store(context: Context) {

    private val db = Db(context).writableDatabase

    // ---------- Плейлисты IPTV ----------

    fun playlists(): List<Playlist> {
        val out = mutableListOf<Playlist>()
        db.rawQuery(
            "SELECT id,name,url,source,sortOrder,updatedAt FROM playlists ORDER BY sortOrder,id",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out += Playlist(
                    id = c.getLong(0), name = c.getString(1), url = c.getString(2),
                    source = c.getString(3), sortOrder = c.getInt(4), updatedAt = c.getLong(5)
                )
            }
        }
        return out
    }

    fun addPlaylist(name: String, url: String, source: String): Long {
        val cv = ContentValues().apply {
            put("name", name); put("url", url); put("source", source)
            put("sortOrder", (playlists().maxOfOrNull { it.sortOrder } ?: -1) + 1)
            put("updatedAt", System.currentTimeMillis())
        }
        return db.insert("playlists", null, cv)
    }

    fun deletePlaylist(id: Long) {
        db.delete("channels", "playlistId=?", arrayOf(id.toString()))
        db.delete("playlists", "id=?", arrayOf(id.toString()))
    }

    fun touchPlaylist(id: Long) =
        db.execSQL("UPDATE playlists SET updatedAt=? WHERE id=?",
            arrayOf(System.currentTimeMillis(), id))

    fun replaceChannels(playlistId: Long, list: List<Channel>) {
        db.beginTransaction()
        try {
            db.delete("channels", "playlistId=?", arrayOf(playlistId.toString()))
            val cv = ContentValues()
            list.forEachIndexed { i, ch ->
                cv.clear()
                cv.put("playlistId", playlistId)
                cv.put("name", ch.name)
                cv.put("url", ch.url)
                cv.put("logo", ch.logo)
                cv.put("grp", ch.group)
                cv.put("epgId", ch.epgId)
                cv.put("userAgent", ch.userAgent)
                cv.put("referrer", ch.referrer)
                cv.put("sortOrder", i)
                db.insert("channels", null, cv)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun channels(playlistId: Long): List<Channel> {
        val out = mutableListOf<Channel>()
        db.rawQuery(
            "SELECT id,name,url,logo,grp,epgId,userAgent,referrer FROM channels WHERE playlistId=? ORDER BY sortOrder",
            arrayOf(playlistId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out += Channel(
                    id = c.getLong(0), playlistId = playlistId, name = c.getString(1),
                    url = c.getString(2), logo = c.getString(3),
                    group = c.getString(4), epgId = c.getString(5),
                    userAgent = c.getString(6), referrer = c.getString(7)
                )
            }
        }
        return out
    }

    // ---------- Избранное ----------

    fun addFavorite(e: MediaEntry) {
        val cv = ContentValues().apply {
            put("kind", e.kind); put("itemId", e.itemId); put("title", e.title)
            put("subtitle", e.subtitle); put("poster", e.poster); put("url", e.url)
            put("category", e.category); put("addedAt", System.currentTimeMillis())
        }
        db.insertWithOnConflict("favorites", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun removeFavorite(kind: String, itemId: String, category: String = "") =
        db.delete("favorites", "kind=? AND itemId=? AND category=?",
            arrayOf(kind, itemId, category))

    fun isFavorite(kind: String, itemId: String): Boolean =
        db.rawQuery("SELECT 1 FROM favorites WHERE kind=? AND itemId=? LIMIT 1",
            arrayOf(kind, itemId)).use { it.moveToFirst() }

    fun favorites(kind: String? = null, category: String? = null): List<MediaEntry> {
        val sel = StringBuilder("1=1"); val args = mutableListOf<String>()
        kind?.let { sel.append(" AND kind=?"); args += it }
        category?.let { sel.append(" AND category=?"); args += it }
        return query("SELECT kind,itemId,title,subtitle,poster,url,category,addedAt,0,0 FROM favorites WHERE $sel ORDER BY addedAt DESC", args)
    }

    fun favoriteCategories(): List<String> =
        db.rawQuery("SELECT DISTINCT category FROM favorites ORDER BY category", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    // ---------- История ----------

    fun addHistory(e: MediaEntry) {
        addHistory(e, System.currentTimeMillis())
    }

    /** Импорт истории: дата из бэкапа сохраняется, а не подменяется текущей. */
    private fun addHistory(e: MediaEntry, viewedAt: Long) {
        // одна запись на элемент — более свежая дата поверх старой
        val existing = db.rawQuery(
            "SELECT viewedAt FROM history WHERE kind=? AND itemId=?", arrayOf(e.kind, e.itemId)
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }
        if (existing >= viewedAt) return // локальная запись свежее — не откатываем
        db.execSQL("DELETE FROM history WHERE kind=? AND itemId=?", arrayOf(e.kind, e.itemId))
        val cv = ContentValues().apply {
            put("kind", e.kind); put("itemId", e.itemId); put("title", e.title)
            put("subtitle", e.subtitle); put("poster", e.poster); put("url", e.url)
            put("viewedAt", viewedAt)
        }
        db.insert("history", null, cv)
        trimHistory()
    }

    private fun trimHistory(limit: Int = 300) =
        db.execSQL(
            "DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY viewedAt DESC LIMIT ?)",
            arrayOf(limit)
        )

    fun history(kind: String? = null): List<MediaEntry> {
        val sel = if (kind != null) "kind=?" else "1=1"
        val args = if (kind != null) arrayOf(kind) else null
        return query("SELECT kind,itemId,title,subtitle,poster,url,'',viewedAt,0,0 FROM history WHERE $sel ORDER BY viewedAt DESC", args?.toMutableList())
    }

    fun searchHistoryTerms(limit: Int = 20): List<String> =
        db.rawQuery("SELECT DISTINCT title FROM history WHERE kind='search' ORDER BY viewedAt DESC LIMIT ?",
            arrayOf(limit.toString())).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    fun clearHistory() = db.delete("history", null, null)

    fun removeHistory(kind: String, itemId: String) =
        db.delete("history", "kind=? AND itemId=?", arrayOf(kind, itemId))

    // ---------- Позиции просмотра («Продолжить») ----------

    fun savePosition(e: MediaEntry) {
        savePosition(e, System.currentTimeMillis())
    }

    /** Импорт позиции: побеждает более свежая запись, её дата сохраняется. */
    private fun savePosition(e: MediaEntry, updatedAt: Long) {
        val existing = position(e.itemId)
        if (existing != null && existing.time >= updatedAt) return // локальная свежее
        val cv = ContentValues().apply {
            put("itemId", e.itemId); put("kind", e.kind); put("title", e.title)
            put("subtitle", e.subtitle); put("poster", e.poster); put("url", e.url)
            put("positionMs", e.positionMs); put("durationMs", e.durationMs)
            put("updatedAt", updatedAt)
        }
        db.insertWithOnConflict("positions", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun position(itemId: String): MediaEntry? =
        query("SELECT kind,itemId,title,subtitle,poster,url,'',updatedAt,positionMs,durationMs FROM positions WHERE itemId=?",
            mutableListOf(itemId)).firstOrNull()

    fun positions(): List<MediaEntry> =
        query("SELECT kind,itemId,title,subtitle,poster,url,'',updatedAt,positionMs,durationMs FROM positions ORDER BY updatedAt DESC LIMIT 50")

    /** Полная выборка для экспорта — без LIMIT, в отличие от [positions] для интерфейса. */
    fun positionsAll(): List<MediaEntry> =
        query("SELECT kind,itemId,title,subtitle,poster,url,'',updatedAt,positionMs,durationMs FROM positions ORDER BY updatedAt DESC")

    fun deletePosition(itemId: String) =
        db.delete("positions", "itemId=?", arrayOf(itemId))

    // ---------- Отметки о просмотре ----------

    fun isWatched(itemId: String): Boolean =
        db.rawQuery("SELECT 1 FROM watched WHERE itemId=? LIMIT 1", arrayOf(itemId)).use { it.moveToFirst() }

    fun setWatched(itemId: String, kind: String, title: String, watched: Boolean) {
        if (watched) {
            db.insertWithOnConflict("watched", null,
                ContentValues().apply {
                    put("itemId", itemId); put("kind", kind)
                    put("title", title); put("markedAt", System.currentTimeMillis())
                },
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        } else {
            db.delete("watched", "itemId=?", arrayOf(itemId))
        }
    }

    /** Все отметки для бэкапа: kind/itemId/title + time=markedAt. */
    fun watched(): List<MediaEntry> {
        val out = mutableListOf<MediaEntry>()
        db.rawQuery("SELECT kind,itemId,title,markedAt FROM watched", null).use { c ->
            while (c.moveToNext()) {
                out += MediaEntry(
                    kind = c.getString(0), itemId = c.getString(1),
                    title = c.getString(2), time = c.getLong(3)
                )
            }
        }
        return out
    }

    // ---------- Общее ----------

    private fun query(sql: String, args: MutableList<String>? = null): MutableList<MediaEntry> {
        val out = mutableListOf<MediaEntry>()
        db.rawQuery(sql, args?.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                out += MediaEntry(
                    kind = c.getString(0), itemId = c.getString(1), title = c.getString(2),
                    subtitle = c.getString(3), poster = c.getString(4), url = c.getString(5),
                    category = c.getString(6), time = c.getLong(7),
                    positionMs = c.getLong(8), durationMs = c.getLong(9)
                )
            }
        }
        return out
    }

    /**
     * Экспорт пользовательских данных в JSON (бэкап). Состав: избранное,
     * история, все позиции просмотра, отметки «Просмотрено», плейлисты
     * и каналы IPTV. Учётные данные (SMB/WebDAV) в бэкап не попадают.
     */
    fun exportJson(): String {
        val sb = StringBuilder(4096)
        sb.append("{\"v\":").append(BACKUP_VERSION).append(',')
        fun list(name: String, rows: List<MediaEntry>) {
            sb.append('"').append(name).append("\":[")
            rows.forEachIndexed { i, e ->
                if (i > 0) sb.append(',')
                sb.append("{\"k\":\"").append(e.kind).append("\",\"i\":\"").append(jsonEsc(e.itemId))
                    .append("\",\"t\":\"").append(jsonEsc(e.title)).append('"')
                e.subtitle?.let { sb.append(",\"s\":\"").append(jsonEsc(it)).append('"') }
                e.poster?.let { sb.append(",\"p\":\"").append(jsonEsc(it)).append('"') }
                // страховка: в бэкапе не должно быть паролей даже в старых записях
                e.url?.let { sb.append(",\"u\":\"").append(jsonEsc(stripCredentials(it))).append('"') }
                sb.append(",\"c\":\"").append(jsonEsc(e.category)).append('"')
                    .append(",\"d\":").append(e.time)
                    .append(",\"pos\":").append(e.positionMs)
                    .append(",\"dur\":").append(e.durationMs)
                    .append('}')
            }
            sb.append(']').append(',')
        }
        list("favorites", favorites())
        list("history", history())
        list("positions", positionsAll())
        list("watched", watched())
        // плейлисты и каналы IPTV
        sb.append("\"playlists\":[")
        val pls = playlists()
        pls.forEachIndexed { i, p ->
            if (i > 0) sb.append(',')
            sb.append("{\"id\":").append(p.id)
                .append(",\"name\":\"").append(jsonEsc(p.name)).append('"')
                .append(",\"url\":\"").append(jsonEsc(stripCredentials(p.url))).append('"')
                .append(",\"source\":\"").append(jsonEsc(p.source)).append('"')
                .append(",\"sort\":").append(p.sortOrder)
                .append(",\"d\":").append(p.updatedAt).append('}')
        }
        sb.append("],\"channels\":[")
        pls.forEachIndexed { pi, p ->
            channels(p.id).forEachIndexed { i, ch ->
                if (pi > 0 || i > 0) sb.append(',')
                sb.append("{\"p\":").append(ch.playlistId)
                    .append(",\"name\":\"").append(jsonEsc(ch.name)).append('"')
                    .append(",\"url\":\"").append(jsonEsc(stripCredentials(ch.url))).append('"')
                ch.logo?.let { sb.append(",\"logo\":\"").append(jsonEsc(it)).append('"') }
                ch.group?.let { sb.append(",\"g\":\"").append(jsonEsc(it)).append('"') }
                ch.epgId?.let { sb.append(",\"epg\":\"").append(jsonEsc(it)).append('"') }
                ch.userAgent?.let { sb.append(",\"ua\":\"").append(jsonEsc(it)).append('"') }
                ch.referrer?.let { sb.append(",\"ref\":\"").append(jsonEsc(it)).append('"') }
                sb.append('}')
            }
        }
        sb.append(']').append('}')
        return sb.toString()
    }

    /** Импорт из JSON бэкапа с проверкой типов и слиянием по датам:
     *  более свежая запись побеждает, дата сохраняется; локально удалённое
     *  при повторном импорте не воскрешается (только добавление и обновление). */
    fun importJson(json: String) {
        require(json.length <= 64 * 1024 * 1024) { "Слишком большой файл бэкапа" }
        val root = org.json.JSONObject(json)
        fun rows(name: String): List<MediaEntry> {
            val arr = root.optJSONArray(name) ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val kind = o.optString("k").takeIf { it in listOf(
                    Kinds.CHANNEL, Kinds.MOVIE, Kinds.SERIES, Kinds.LOCAL, Kinds.TORRENT, Kinds.WEB) }
                    ?: return@mapNotNull null
                val itemId = o.optString("i")
                val title = o.optString("t")
                if (itemId.isEmpty() || title.isEmpty()) return@mapNotNull null
                val url = o.optString("u").ifEmpty { null }?.takeIf {
                    it.startsWith("http") || it.startsWith("smb://") || it.startsWith("rtmp")
                }
                MediaEntry(
                    kind = kind, itemId = itemId, title = title,
                    subtitle = o.optString("s").ifEmpty { null },
                    poster = o.optString("p").ifEmpty { null },
                    url = url,
                    category = o.optString("c"), time = o.optLong("d"),
                    positionMs = o.optLong("pos").coerceIn(0, Long.MAX_VALUE),
                    durationMs = o.optLong("dur").coerceIn(0, Long.MAX_VALUE),
                )
            }
        }
        db.beginTransaction()
        try {
            // избранное: только добавление недостающих — удалённое не воскрешается
            rows("favorites").forEach { e ->
                if (!db.rawQuery("SELECT 1 FROM favorites WHERE kind=? AND itemId=? AND category=?",
                        arrayOf(e.kind, e.itemId, e.category)).use { it.moveToFirst() }) {
                    addFavorite(e.copy(time = 0))
                }
            }
            // история и позиции: побеждает более свежая дата, дата сохраняется
            rows("history").forEach { addHistory(it, it.time) }
            rows("positions").forEach { savePosition(it, it.time) }
            // отметки только проставляем: «снять просмотр» при объединении бэкапа не делаем
            rows("watched").forEach {
                if (!db.rawQuery("SELECT 1 FROM watched WHERE itemId=?", arrayOf(it.itemId)).use { c -> c.moveToFirst() }) {
                    setWatched(it.itemId, it.kind, it.title, true)
                    db.execSQL("UPDATE watched SET markedAt=? WHERE itemId=?",
                        arrayOf(if (it.time > 0) it.time else System.currentTimeMillis(), it.itemId))
                }
            }
            // плейлисты и каналы IPTV: дополняем, не удаляя локальные
            root.optJSONArray("playlists")?.let { arr ->
                val existing = playlists()
                (0 until arr.length()).forEach { i ->
                    val o = arr.optJSONObject(i) ?: return@forEach
                    val name = o.optString("name")
                    val url = o.optString("url")
                    if (name.isEmpty() || url.isEmpty()) return@forEach
                    if (existing.none { it.name == name && it.url == url }) {
                        val id = addPlaylist(name, url, o.optString("source"))
                        o.optLong("d").takeIf { it > 0 }?.let {
                            db.execSQL("UPDATE playlists SET updatedAt=? WHERE id=?", arrayOf(it, id))
                        }
                    }
                }
            }
            root.optJSONArray("channels")?.let { arr ->
                val existing = playlists()
                (0 until arr.length()).forEach { i ->
                    val o = arr.optJSONObject(i) ?: return@forEach
                    val pl = existing.firstOrNull { it.id == o.optLong("p") } ?: return@forEach
                    val name = o.optString("name")
                    val url = o.optString("url")
                    if (name.isEmpty() || url.isEmpty()) return@forEach
                    val already = channels(pl.id).any { it.name == name && it.url == url }
                    if (!already) {
                        replaceChannels(pl.id, channels(pl.id) + Channel(
                            id = 0, playlistId = pl.id, name = name, url = url,
                            logo = o.optString("logo").ifEmpty { null },
                            group = o.optString("g").ifEmpty { null },
                            epgId = o.optString("epg").ifEmpty { null },
                            userAgent = o.optString("ua").ifEmpty { null },
                            referrer = o.optString("ref").ifEmpty { null },
                        ))
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Убирает учётные данные из smb://user:pass@host — в хранилище им не место. */
    private fun stripCredentials(url: String): String =
        url.replace(Regex("^(smb://)([^:/@]+):([^@]*)@"), "$1")

    private companion object {
        const val BACKUP_VERSION = 2
    }

    private fun jsonEsc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        s.forEach { ch ->
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        return sb.toString()
    }

    // ---------- SMB-серверы ----------

    fun smbServers(): List<SmbServer> {
        val out = mutableListOf<SmbServer>()
        db.rawQuery("SELECT id,name,base,user,pass FROM smb_servers ORDER BY id", null).use { c ->
            while (c.moveToNext()) {
                out += SmbServer(
                    id = c.getLong(0), name = c.getString(1), base = c.getString(2),
                    user = c.getString(3).ifEmpty { null }, pass = c.getString(4).ifEmpty { null }
                )
            }
        }
        return out
    }

    fun addSmbServer(name: String, base: String, user: String?, pass: String?): Long {
        val cv = ContentValues().apply {
            put("name", name); put("base", base)
            put("user", user ?: ""); put("pass", pass ?: "")
        }
        return db.insert("smb_servers", null, cv)
    }

    fun deleteSmbServer(id: Long) =
        db.delete("smb_servers", "id=?", arrayOf(id.toString()))

    fun close() = db.close()
}
