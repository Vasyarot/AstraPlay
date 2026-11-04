package com.astraplay.tv.data.sources

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** The public API used by the current official embed player. Resolve each time: stream URLs expire. */
object SmotrimPlayback {
    fun videoId(url: String): String? {
        val address = url.toHttpUrlOrNull() ?: return null
        if (address.username.isNotEmpty() || address.password.isNotEmpty()) return null
        if (address.host !in setOf("smotrim.ru", "www.smotrim.ru", "embed.smotrim.ru", "player.smotrim.ru", "player.vgtrk.com")) return null
        return Regex("^/(?:iframe/video/id/|video/)([0-9]{1,12})(?:/.*)?$").matchEntire(address.encodedPath)?.groupValues?.get(1)
    }

    fun embedUrl(id: String) = "https://embed.smotrim.ru/iframe/video/id/$id"

    fun parse(data: JSONObject, id: String): SourcePlayOption {
        require(Regex("[0-9]{1,12}").matches(id))
        val fallback = embedUrl(id)
        val video = data.optJSONObject("data")
        val protected = video?.optBoolean("is_drm") == true || video?.optBoolean("drm") == true ||
            (video?.optJSONObject("drm")?.length() ?: 0) > 0
        val raw = if (data.optString("status") == "OK" && !protected)
            video?.optJSONObject("streams")?.optString("m3u8") else null
        val stream = raw?.toHttpUrlOrNull()?.takeIf { it.username.isEmpty() && it.password.isEmpty() }
        return if (stream != null) SourcePlayOption("Смотреть", SourcePlayKind.DIRECT, stream.toString(), "https://smotrim.ru/", fallback)
            else SourcePlayOption("Плеер источника", SourcePlayKind.EMBED, fallback, "https://smotrim.ru/")
    }
}
