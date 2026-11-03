package com.astraplay.tv.data.webdav

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Минималистичный WebDAV-клиент для синхронизации данных приложения:
 * GET удалённого JSON → объединение с локальным → PUT обратно.
 * Работает с Nextcloud, Яндекс.Диск (WebDAV), Synology и любым RFC4918-сервером.
 */
class WebDav(private val http: com.astraplay.tv.data.net.Http) {

    fun fetch(url: String, user: String?, pass: String?): String? {
        val rb = Request.Builder().url(url)
        if (!user.isNullOrBlank()) rb.header("Authorization", Credentials.basic(user, pass ?: ""))
        http.client.newCall(rb.build()).execute().use { resp ->
            if (resp.code == 404) return null   // файла ещё нет — первый запуск
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            return resp.body!!.string()
        }
    }

    fun put(url: String, user: String?, pass: String?, content: String) {
        val rb = Request.Builder().url(url)
            .put(content.toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (!user.isNullOrBlank()) rb.header("Authorization", Credentials.basic(user, pass ?: ""))
        http.client.newCall(rb.build()).execute().use { resp ->
            if (!resp.isSuccessful && resp.code !in listOf(201, 204)) error("HTTP ${resp.code}")
        }
    }
}
