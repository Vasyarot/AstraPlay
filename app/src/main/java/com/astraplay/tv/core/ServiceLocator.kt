package com.astraplay.tv.core

import android.content.Context
import com.astraplay.tv.data.db.Store
import com.astraplay.tv.data.iptv.EpgStore
import com.astraplay.tv.data.local.MediaStoreSource
import com.astraplay.tv.data.net.Http
import com.astraplay.tv.data.tmdb.TmdbClient
import com.astraplay.tv.data.torrent.ArchiveSource

/**
 * Простой ServiceLocator вместо DI-фреймворка:
 * на 1 ГБ устройств каждая библиотека отражения стоит памяти.
 */
object ServiceLocator {

    lateinit var appContext: Context
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val prefs: Prefs by lazy { Prefs(appContext) }

    val http: Http by lazy { Http(prefs) }

    val store: Store by lazy { Store(appContext) }

    val epg: EpgStore by lazy { EpgStore(http) }

    val tmdb: TmdbClient by lazy { TmdbClient(http, prefs) }

    val archive: ArchiveSource by lazy { ArchiveSource(http) }

    val media: MediaStoreSource by lazy { MediaStoreSource(appContext) }
}
