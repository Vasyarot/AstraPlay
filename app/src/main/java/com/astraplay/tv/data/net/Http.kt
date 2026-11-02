package com.astraplay.tv.data.net

import com.astraplay.tv.core.Prefs
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Единый OkHttp-клиент: компактный пул соединений и таймауты под слабую сеть. */
class Http(prefs: Prefs) {

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val rb = Request.Builder().url(url)
            .header("User-Agent", "AstraPlay/1.0")
        headers.forEach { (k, v) -> rb.header(k, v) }
        client.newCall(rb.build()).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} for $url")
            return resp.body!!.string()
        }
    }

    fun getJson(url: String, headers: Map<String, String> = emptyMap()): JSONObject =
        JSONObject(get(url, headers))
}
