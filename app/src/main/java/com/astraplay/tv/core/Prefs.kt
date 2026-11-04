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

    /** Таймер сна «как в системе»: пауза видео после таймаута экрана Android. */
    var sleepSyncSystem: Boolean
        get() = sp.getBoolean(KEY_SLEEP_SYNC, false)
        set(v) = sp.edit().putBoolean(KEY_SLEEP_SYNC, v).apply()

    // ---- Таймер включения ----
    // 0 — выкл, 1 — ежедневно в wakeHour:wakeMinute, 2 — одноразово в wakeOnceAt
    var wakeMode: Int
        get() = sp.getInt(KEY_WAKE_MODE, 0)
        set(v) = sp.edit().putInt(KEY_WAKE_MODE, v).apply()

    var wakeHour: Int
        get() = sp.getInt(KEY_WAKE_HOUR, 7)
        set(v) = sp.edit().putInt(KEY_WAKE_HOUR, v).apply()

    var wakeMinute: Int
        get() = sp.getInt(KEY_WAKE_MINUTE, 0)
        set(v) = sp.edit().putInt(KEY_WAKE_MINUTE, v).apply()

    var wakeDelayMin: Int // длительность одноразового таймера (для подстановки в диалог)
        get() = sp.getInt(KEY_WAKE_DELAY, 30)
        set(v) = sp.edit().putInt(KEY_WAKE_DELAY, v).apply()

    var wakeOnceAt: Long // момент срабатывания одноразового таймера
        get() = sp.getLong(KEY_WAKE_ONCE_AT, 0L)
        set(v) = sp.edit().putLong(KEY_WAKE_ONCE_AT, v).apply()

    /** Канал для таймера; пусто — самый первый канал первого плейлиста. */
    var wakeChannelUrl: String
        get() = sp.getString(KEY_WAKE_CHANNEL_URL, "") ?: ""
        set(v) = sp.edit().putString(KEY_WAKE_CHANNEL_URL, v).apply()

    var wakeChannelTitle: String
        get() = sp.getString(KEY_WAKE_CHANNEL_TITLE, "") ?: ""
        set(v) = sp.edit().putString(KEY_WAKE_CHANNEL_TITLE, v).apply()

    // ---- Система ----
    var autostart: Boolean
        get() = sp.getBoolean(KEY_AUTOSTART, false)
        set(v) = sp.edit().putBoolean(KEY_AUTOSTART, v).apply()

    var lastPlaylistId: Long
        get() = sp.getLong(KEY_LAST_PLAYLIST, -1L)
        set(v) = sp.edit().putLong(KEY_LAST_PLAYLIST, v).apply()

    var autoEpg: Boolean
        get() = sp.getBoolean("auto_epg", true)
        set(v) = sp.edit().putBoolean("auto_epg", v).apply()

    var epgUrl: String
        get() = sp.getString("epg_url", "") ?: ""
        set(v) = sp.edit().putString("epg_url", v).apply()

    // ---- WebDAV ----
    var webdavUrl: String
        get() = sp.getString(KEY_WEBDAV_URL, "") ?: ""
        set(v) = sp.edit().putString(KEY_WEBDAV_URL, v).apply()

    var webdavUser: String
        get() = sp.getString(KEY_WEBDAV_USER, "") ?: ""
        set(v) = sp.edit().putString(KEY_WEBDAV_USER, v).apply()

    var webdavPass: String
        get() = sp.getString(KEY_WEBDAV_PASS, "") ?: ""
        set(v) = sp.edit().putString(KEY_WEBDAV_PASS, v).apply()

    /** Осознанное разрешение на HTTP без шифрования (для домашнего сервера). */
    var webdavAllowHttp: Boolean
        get() = sp.getBoolean(KEY_WEBDAV_ALLOW_HTTP, false)
        set(v) = sp.edit().putBoolean(KEY_WEBDAV_ALLOW_HTTP, v).apply()

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
        const val KEY_SLEEP_SYNC = "sleep_sync_system"
        const val KEY_WAKE_MODE = "wake_mode"
        const val KEY_WAKE_HOUR = "wake_hour"
        const val KEY_WAKE_MINUTE = "wake_minute"
        const val KEY_WAKE_DELAY = "wake_delay_min"
        const val KEY_WAKE_ONCE_AT = "wake_once_at"
        const val KEY_WAKE_CHANNEL_URL = "wake_channel_url"
        const val KEY_WAKE_CHANNEL_TITLE = "wake_channel_title"
        const val KEY_AUTOSTART = "autostart"
        const val KEY_LAST_PLAYLIST = "last_playlist"
        const val KEY_WEBDAV_URL = "webdav_url"
        const val KEY_WEBDAV_USER = "webdav_user"
        const val KEY_WEBDAV_PASS = "webdav_pass"
        const val KEY_WEBDAV_ALLOW_HTTP = "webdav_allow_http"
        const val DEFAULT_TMDB_KEY = ""
    }
}
