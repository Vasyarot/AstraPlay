package com.astraplay.tv.ui.nav

import android.content.Context
import android.content.Intent
import com.astraplay.tv.data.db.MediaEntry
import com.astraplay.tv.data.tmdb.TmdbItem
import com.astraplay.tv.player.PlayerActivity
import com.astraplay.tv.ui.data.UserDataActivity
import com.astraplay.tv.ui.iptv.IptvActivity
import com.astraplay.tv.ui.local.LocalActivity
import com.astraplay.tv.ui.movies.CatalogActivity
import com.astraplay.tv.ui.movies.DetailsActivity
import com.astraplay.tv.ui.search.SearchActivity
import com.astraplay.tv.ui.settings.SettingsActivity
import com.astraplay.tv.ui.torrent.TorrentActivity

/** Централизованный запуск экранов. */
object Nav {

    fun openIptv(context: Context, playlistId: Long = -1L) =
        context.startActivity(Intent(context, IptvActivity::class.java)
            .putExtra("playlistId", playlistId))

    fun openSources(context: Context) =
        context.startActivity(Intent(context, com.astraplay.tv.ui.movies.SourcesActivity::class.java))

    fun openCatalog(context: Context) =
        context.startActivity(Intent(context, CatalogActivity::class.java))

    fun openDetails(context: Context, item: TmdbItem) =
        context.startActivity(Intent(context, DetailsActivity::class.java)
            .putExtra("itemId", item.id)
            .putExtra("mediaType", item.mediaType))

    fun openTorrent(context: Context, query: String? = null) =
        context.startActivity(Intent(context, TorrentActivity::class.java)
            .putExtra("query", query ?: ""))

    fun openLocal(context: Context) =
        context.startActivity(Intent(context, LocalActivity::class.java))

    fun openSmb(context: Context) =
        context.startActivity(Intent(context, com.astraplay.tv.ui.smb.SmbBrowserActivity::class.java))

    fun openEpgGrid(context: Context, playlistId: Long) =
        context.startActivity(Intent(context, com.astraplay.tv.ui.iptv.EpgGridActivity::class.java)
            .putExtra("playlistId", playlistId))

    fun openSettings(context: Context) =
        context.startActivity(Intent(context, SettingsActivity::class.java))

    fun openSearch(context: Context, query: String? = null) =
        context.startActivity(Intent(context, SearchActivity::class.java).apply {
            action = Intent.ACTION_SEARCH
            putExtra("query", query ?: "")
        })

    fun openUserData(context: Context, showHistory: Boolean = false) =
        context.startActivity(Intent(context, UserDataActivity::class.java)
            .putExtra("history", showHistory))

    /** Запуск плеера. [next*] — данные следующей серии для автоперехода. */
    fun play(
        context: Context,
        entry: MediaEntry,
        nextTitle: String? = null,
        nextUrl: String? = null,
        nextItemId: String? = null,
        playlistId: Long = -1L,
    ) = if (entry.kind == com.astraplay.tv.data.db.Kinds.WEB) {
        context.startActivity(Intent(context, com.astraplay.tv.ui.movies.SourceWebActivity::class.java)
            .putExtra("url", entry.url).putExtra("title", entry.title))
    } else context.startActivity(Intent(context, if (entry.kind == com.astraplay.tv.data.db.Kinds.CHANNEL)
        com.astraplay.tv.player.LivePlayerActivity::class.java else PlayerActivity::class.java).apply {
        putExtra("playlistId", playlistId)
        putExtra("itemId", entry.itemId)
        putExtra("kind", entry.kind)
        putExtra("title", entry.title)
        putExtra("subtitle", entry.subtitle)
        putExtra("poster", entry.poster)
        putExtra("url", entry.url)
        putExtra("pos", entry.positionMs)
        putExtra("dur", entry.durationMs)
        putExtra("nextTitle", nextTitle)
        putExtra("nextUrl", nextUrl)
        putExtra("nextItemId", nextItemId)
    })
}
