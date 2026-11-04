package com.astraplay.tv.data.iptv

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Lightweight reachability check, not a decoder/playback guarantee. Reads at most 2 KiB. */
class ChannelProbe(client: OkHttpClient) {
    private val http = client.newBuilder().connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS).callTimeout(6, TimeUnit.SECONDS).build()

    fun responds(url: String, userAgent: String? = null, referrer: String? = null): Boolean = runCatching {
        val request = Request.Builder().url(url).header("Range", "bytes=0-2047")
            .header("User-Agent", userAgent ?: com.astraplay.tv.data.net.Http.USER_AGENT)
        referrer?.let { request.header("Referer", it) }
        http.newCall(request.build()).execute().use { response ->
            if (!response.isSuccessful) return false
            val body = response.body ?: return false
            val sample = ByteArray(2048)
            val count = body.byteStream().read(sample)
            if (count <= 0) return false
            val text = String(sample, 0, count, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\r', '\n', '\t')
            if (text.startsWith("<!doctype html", true) || text.startsWith("<html", true)) return false
            val type = body.contentType()?.toString().orEmpty().lowercase()
            text.startsWith("#EXTM3U") || type.startsWith("video/") || type.startsWith("audio/") ||
                type.contains("mpegurl") || type.contains("dash+xml") || type.contains("octet-stream") ||
                (sample[0].toInt() and 0xff) == 0x47
        }
    }.getOrDefault(false)
}
