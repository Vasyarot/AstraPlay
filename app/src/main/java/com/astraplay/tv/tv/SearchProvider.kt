package com.astraplay.tv.tv

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.app.SearchManager
import android.net.Uri
import com.astraplay.tv.core.ServiceLocator
import com.astraplay.tv.data.db.Kinds
import java.util.Locale

/**
 * Провайдер подсказок для глобального поиска Android TV:
 * системный лаунчер показывает фильмы и каналы приложения по запросу.
 */
class SearchProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        // ContentProvider инициализируется раньше Application.onCreate
        ServiceLocator.init(context!!)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val path = uri.lastPathSegment ?: return null
        // формат: search_suggest_query/<query>
        if (!path.contains(SearchManager.SUGGEST_URI_PATH_QUERY)) return null
        val raw = if (path.startsWith(SearchManager.SUGGEST_URI_PATH_QUERY))
            uri.pathSegments.drop(1).joinToString(" ") else path
        val q = Uri.decode(raw).trim()
        if (q.length < 2) return null
        val lower = q.lowercase(Locale.getDefault())

        val columns = arrayOf(
            "_id",
            SearchManager.SUGGEST_COLUMN_TEXT_1,
            SearchManager.SUGGEST_COLUMN_TEXT_2,
            SearchManager.SUGGEST_COLUMN_ICON_1,
            SearchManager.SUGGEST_COLUMN_INTENT_ACTION,
            SearchManager.SUGGEST_COLUMN_INTENT_EXTRA_DATA,
        )
        val cursor = MatrixCursor(columns)
        val ctx = context ?: return cursor

        try {
            val store = ServiceLocator.store

            // 1) Каналы IPTV
            var id = 0
            store.playlists().forEach { pl ->
                store.channels(pl.id).forEach { ch ->
                    if (id < 15 && ch.name.lowercase(Locale.getDefault()).contains(lower)) {
                        cursor.addRow(arrayOf(
                            id++,
                            ch.name,
                            "IPTV • ${pl.name}",
                            ch.logo,
                            IntentKeys.OPEN_ENTRY,
                            entryJson(Kinds.CHANNEL, ch.key, ch.name, ch.logo, ch.url, ch.group),
                        ))
                    }
                }
            }

            // 2) TMDB (если есть ключ) — синхронный запрос допустим для провайдера
            val tmdb = ServiceLocator.tmdb
            if (tmdb.available && id < 15) {
                runCatching {
                    val res = kotlinx.coroutines.runBlocking {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            tmdb.search(q).getOrDefault(emptyList())
                        }
                    }
                    res.take(15 - id).forEach { item ->
                        cursor.addRow(arrayOf(
                            id++,
                            item.title + (if (item.year.isNotEmpty()) " (${item.year})" else ""),
                            if (item.mediaType == "tv") "Сериал • ★%.1f".format(Locale.US, item.rating)
                            else "Фильм • ★%.1f".format(Locale.US, item.rating),
                            tmdb.posterUrl(item.posterPath, 154),
                            IntentKeys.OPEN_TMDB,
                            "${item.mediaType}|${item.id}|${item.title}",
                        ))
                    }
                }
            }

            // 3) Локальные видео и избранное
            (store.history() + store.favorites())
                .distinctBy { it.kind + it.itemId }
                .filter { it.title.lowercase(Locale.getDefault()).contains(lower) }
                .take(5)
                .forEach { e ->
                    if (id < 20) cursor.addRow(arrayOf(
                        id++,
                        e.title,
                        e.subtitle ?: "",
                        e.poster,
                        if (e.url != null) IntentKeys.OPEN_ENTRY else IntentKeys.OPEN_DATA,
                        entryJson(e.kind, e.itemId, e.title, e.poster, e.url, e.subtitle),
                    ))
                }
        } catch (t: Throwable) {
            // провайдер не должен ронять системный лаунчер
        }
        return cursor
    }

    private fun entryJson(
        kind: String, itemId: String, title: String,
        poster: String?, url: String?, subtitle: String?,
    ): String {
        fun esc(s: String?) = (s ?: "").replace("\\", "\\\\").replace("\"", "\\\"")
        return "{\"k\":\"${esc(kind)}\",\"i\":\"${esc(itemId)}\",\"t\":\"${esc(title)}\"," +
                "\"p\":\"${esc(poster)}\",\"u\":\"${esc(url)}\",\"s\":\"${esc(subtitle)}\"}"
    }

    override fun getType(uri: Uri): String = SearchManager.SUGGEST_MIME_TYPE

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    /** Куда системный лаунчер отправляет клик по подсказке. */
    object IntentKeys {
        const val OPEN_ENTRY = "com.astraplay.tv.OPEN_ENTRY"
        const val OPEN_TMDB = "com.astraplay.tv.OPEN_TMDB"
        const val OPEN_DATA = "com.astraplay.tv.OPEN_DATA"
    }
}
