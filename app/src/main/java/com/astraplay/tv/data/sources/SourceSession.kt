package com.astraplay.tv.data.sources

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** Shares each website's own session with its native catalog. Credentials stay in the website form. */
class SourceSession(private val cookies: CookieManager) : CookieJar {
    override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.getCookie(url.toString())
        .orEmpty().split(';').mapNotNull { pair ->
            val separator = pair.indexOf('=')
            if (separator <= 0) null else runCatching {
                Cookie.Builder().name(pair.substring(0, separator).trim())
                    .value(pair.substring(separator + 1).trim()).hostOnlyDomain(url.host).path("/").build()
            }.getOrNull()
        }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (cookie in cookies) this.cookies.setCookie(url.toString(), cookie.toString())
        this.cookies.flush()
    }
}
