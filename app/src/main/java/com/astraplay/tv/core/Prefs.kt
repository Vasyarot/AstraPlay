package com.astraplay.tv.core

import android.content.Context
import android.content.SharedPreferences

/** Обёртка над SharedPreferences. Все функции приложения бесплатны. */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("astraplay", Context.MODE_PRIVATE)

    // ---- Внешний вид ----
    var themeId: Int
        get() = sp.getInt(KEY_THEME, 0)
        set(v) = sp.edit().putInt(KEY_THEME, v).apply()

    var posterSize: Int // 0 = средние, 1 = крупные
        get() = sp.getInt(KEY_POSTER_SIZE, 0)
        set(v) = sp.edit().putInt(KEY_POSTER_SIZE, v).apply()

    // ---- TMDB ----
    var tmdbKey: String
        get() = sp.getString(KEY_TMDB_KEY, null) ?: DEFAULT_TMDB_KEY
        set(v) = sp.edit().putString(KEY_TMDB_KEY, v).apply()

    // ---- Плеер ----
    var hlsBufferSecs: Int // целевой буфер HLS, сек
        get() = sp.getInt(KEY_BUFFER, 20)
        set(v) = sp.edit().putInt(KEY_BUFFER, v).apply()

    var autoplayNext: Boolean
        get() = sp.getBoolean(KEY_AUTO_NEXT, true)
        set(v) = sp.edit().putBoolean(KEY_AUTO_NEXT, v).apply()

    var seekStepSecs: Int
        get() = sp.getInt(KEY_SEEK_STEP, 10)
        set(v) = sp.edit().putInt(KEY_SEEK_STEP, v).apply()

    // ---- Система ----
    var autostart: Boolean
        get() = sp.getBoolean(KEY_AUTOSTART, false)
        set(v) = sp.edit().putBoolean(KEY_AUTOSTART, v).apply()

    var lastPlaylistId: Long
        get() = sp.getLong(KEY_LAST_PLAYLIST, -1L)
        set(v) = sp.edit().putLong(KEY_LAST_PLAYLIST, v).apply()

    fun clearAll() {
        sp.edit().clear().apply()
    }

    private companion object {
        const val KEY_THEME = "theme_id"
        const val KEY_POSTER_SIZE = "poster_size"
        const val KEY_TMDB_KEY = "tmdb_key"
        const val KEY_BUFFER = "hls_buffer"
        const val KEY_AUTO_NEXT = "autoplay_next"
        const val KEY_SEEK_STEP = "seek_step"
        const val KEY_AUTOSTART = "autostart"
        const val KEY_LAST_PLAYLIST = "last_playlist"
        const val DEFAULT_TMDB_KEY = ""
    }
}
