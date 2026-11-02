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
            "SELECT id,name,url,logo,grp,epgId FROM channels WHERE playlistId=? ORDER BY sortOrder",
            arrayOf(playlistId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out += Channel(
                    id = c.getLong(0), playlistId = playlistId, name = c.getString(1),
                    url = c.getString(2), logo = c.getString(3),
                    group = c.getString(4), epgId = c.getString(5)
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
        // одна запись на элемент — свежая дата поверх старой
        db.execSQL("DELETE FROM history WHERE kind=? AND itemId=?", arrayOf(e.kind, e.itemId))
        val cv = ContentValues().apply {
            put("kind", e.kind); put("itemId", e.itemId); put("title", e.title)
            put("subtitle", e.subtitle); put("poster", e.poster); put("url", e.url)
            put("viewedAt", System.currentTimeMillis())
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
        val cv = ContentValues().apply {
            put("itemId", e.itemId); put("kind", e.kind); put("title", e.title)
            put("subtitle", e.subtitle); put("poster", e.poster); put("url", e.url)
            put("positionMs", e.positionMs); put("durationMs", e.durationMs)
            put("updatedAt", System.currentTimeMillis())
        }
        db.insertWithOnConflict("positions", null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun position(itemId: String): MediaEntry? =
        query("SELECT kind,itemId,title,subtitle,poster,url,'',updatedAt,positionMs,durationMs FROM positions WHERE itemId=?",
            mutableListOf(itemId)).firstOrNull()

    fun positions(): List<MediaEntry> =
        query("SELECT kind,itemId,title,subtitle,poster,url,'',updatedAt,positionMs,durationMs FROM positions ORDER BY updatedAt DESC LIMIT 50")

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

    /** Экспорт всех пользовательских данных в JSON (бэкап). */
    fun exportJson(): String {
        val sb = StringBuilder(4096)
        sb.append('{')
        fun list(name: String, rows: List<MediaEntry>) {
            sb.append('"').append(name).append("\":[")
            rows.forEachIndexed { i, e ->
                if (i > 0) sb.append(',')
                sb.append("{\"k\":\"").append(e.kind).append("\",\"i\":\"").append(jsonEsc(e.itemId))
                    .append("\",\"t\":\"").append(jsonEsc(e.title)).append('"')
                e.subtitle?.let { sb.append(",\"s\":\"").append(jsonEsc(it)).append('"') }
                e.poster?.let { sb.append(",\"p\":\"").append(jsonEsc(it)).append('"') }
                e.url?.let { sb.append(",\"u\":\"").append(jsonEsc(it)).append('"') }
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
        list("positions", positions())
        sb.setLength(sb.length - 1)
        sb.append('}')
        return sb.toString()
    }

    /** Импорт из JSON бэкапа. */
    fun importJson(json: String) {
        val root = org.json.JSONObject(json)
        fun rows(name: String): List<MediaEntry> {
            val arr = root.optJSONArray(name) ?: return emptyList()
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                MediaEntry(
                    kind = o.getString("k"), itemId = o.getString("i"), title = o.getString("t"),
                    subtitle = o.optString("s").ifEmpty { null },
                    poster = o.optString("p").ifEmpty { null },
                    url = o.optString("u").ifEmpty { null },
                    category = o.optString("c"), time = o.optLong("d"),
                    positionMs = o.optLong("pos"), durationMs = o.optLong("dur")
                )
            }
        }
        db.beginTransaction()
        try {
            rows("favorites").forEach { addFavorite(it) }
            rows("history").forEach { addHistory(it) }
            rows("positions").forEach { savePosition(it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
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

    fun close() = db.close()
}
