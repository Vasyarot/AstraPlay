package com.astraplay.tv.data.webdav

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Минималистичный WebDAV-клиент для синхронизации данных приложения:
 * GET удалённого JSON → объединение с локальным → PUT обратно.
 * Работает с Nextcloud, Яндекс.Диск (WebDAV), Synology и любым RFC4918-сервером.
 *
 * Пароль по HTTP не передаётся: соединение с учётными данными требует HTTPS
 * со стандартной проверкой сертификата. Исключение — осознанная настройка
 * [allowInsecureHttp] для домашнего сервера.
 */
class WebDav(
    private val client: OkHttpClient,
    private val allowInsecureHttp: Boolean = false,
) {
    constructor(http: com.astraplay.tv.data.net.Http, allowInsecureHttp: Boolean = false) :
        this(http.client, allowInsecureHttp)

    internal fun checkUrl(url: String, user: String?) {
        if (user.isNullOrBlank()) return // без учётных данных рисков нет
        if (url.startsWith("http://") && !allowInsecureHttp) {
            error("WebDAV-пароль нельзя передавать по HTTP без шифрования. Используйте HTTPS или включите осознанное разрешение в настройках.")
        }
    }

    fun fetch(url: String, user: String?, pass: String?): String? {
        checkUrl(url, user)
        val rb = Request.Builder().url(url)
        if (!user.isNullOrBlank()) rb.header("Authorization", Credentials.basic(user, pass ?: ""))
        client.newCall(rb.build()).execute().use { resp ->
            if (resp.code == 404) return null   // файла ещё нет — первый запуск
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            return resp.body!!.string()
        }
    }

    fun put(url: String, user: String?, pass: String?, content: String) {
        checkUrl(url, user)
        val rb = Request.Builder().url(url)
            .put(content.toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (!user.isNullOrBlank()) rb.header("Authorization", Credentials.basic(user, pass ?: ""))
        client.newCall(rb.build()).execute().use { resp ->
            if (!resp.isSuccessful && resp.code !in listOf(201, 204)) error("HTTP ${resp.code}")
        }
    }
}
