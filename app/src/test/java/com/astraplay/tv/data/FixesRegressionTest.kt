package com.astraplay.tv.data

import com.astraplay.tv.data.db.SmbServer
import com.astraplay.tv.data.sources.OnlineSource
import com.astraplay.tv.data.sources.SourceCatalogLoader
import com.astraplay.tv.data.smb.SmbClient
import com.astraplay.tv.data.tmdb.TmdbJson
import com.astraplay.tv.data.webdav.WebDav
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Регрессионные проверки конкретных исправлений: путь раздела Zona,
 * таймауты лент, тип записей TMDB, SMB без пароля в адресе, WebDAV-HTTP.
 */
class FixesRegressionTest {

    // ---------- путь раздела не перезаписывается (дефект 3) ----------

    @Test fun zonaNoveltyPathIsKeptAndNotReplacedByDefaultCategory() {
        val source = OnlineSource("zona", "Zona", "https://g4.zona.plus/")
        val candidates = SourceCatalogLoader.candidateAddresses(source, "movies", "/movies/")
        // «Новинки»: путь /movies/ сохраняется; путь «По рейтингу» не подмешивается
        assertTrue(candidates.any { it == "https://g4.zona.plus/movies/" })
        assertFalse(candidates.any { it.contains("filter/sort-rating") })
        // зеркало получает тот же выбранный путь
        assertTrue(candidates.any { it == "https://w140.zona.plus/movies/" })
    }

    @Test fun defaultCategoryAppliedOnlyWithoutPathOverride() {
        val source = OnlineSource("zona", "Zona", "https://g4.zona.plus/")
        val candidates = SourceCatalogLoader.candidateAddresses(source, "movies", null)
        assertTrue(candidates.any { it.contains("/movies/filter/sort-rating") })
    }

    // ---------- таймауты лент не перезаписываются загрузчиком (дефект 4) ----------

    @Test fun rowClientTimeoutsSurviveLoaderConstruction() {
        val rowClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
        val loader = SourceCatalogLoader(rowClient, "AstraPlay/1.5", "Mozilla/5.0 test",
            callTimeoutMs = 8000, readTimeoutMs = 8000)
        assertEquals(8000, loader.effectiveClient.callTimeoutMillis)
        assertEquals(8000, loader.effectiveClient.readTimeoutMillis)
        // значения по умолчанию для каталога остаются прежними (15/10 с)
        val defaultLoader = SourceCatalogLoader(rowClient, "AstraPlay/1.5")
        assertEquals(15000, defaultLoader.effectiveClient.callTimeoutMillis)
        assertEquals(10000, defaultLoader.effectiveClient.readTimeoutMillis)
    }

    // ---------- TMDB: сериалы не превращаются в фильмы (дефект 8) ----------

    @Test fun tvSectionItemsWithoutMediaTypeAreParsedAsTv() {
        val arr = JSONArray().put(JSONObject()
            .put("id", 1399)
            .put("name", "Игра престолов")
            .put("first_air_date", "2011-04-17")
            .put("vote_average", 8.4))
        val items = TmdbJson.parseItems(arr, "tv")
        assertEquals("tv", items.single().mediaType)
        assertEquals("Игра престолов", items.single().title)
        assertEquals("2011", items.single().year)
        // ключ записи и путь открытия соответствуют сериалу
        assertEquals("tmdb_tv_1399", items.single().key)
    }

    @Test fun mixedTrendingUsesPerItemMediaType() {
        val arr = JSONArray()
            .put(JSONObject().put("id", 1).put("media_type", "tv").put("name", "Сериал").put("first_air_date", "2020-01-01"))
            .put(JSONObject().put("id", 2).put("media_type", "movie").put("title", "Фильм").put("release_date", "2021-01-01"))
        val items = TmdbJson.parseItems(arr, null)
        assertEquals(listOf("tv", "movie"), items.map { it.mediaType })
    }

    // ---------- SMB: адрес без пароля, данные при подключении (дефект 10) ----------

    @Test fun smbCredentialsInjectedOnlyAtConnectionTime() {
        val server = SmbServer(id = 1, name = "NAS", base = "smb://192.168.1.10/Video", user = "vasya", pass = "p@ss")
        // запись хранится без пароля
        assertEquals("smb://192.168.1.10/Video/film.mkv", server.urlNoAuth("/film.mkv"))
        // подключение получает пароль
        assertEquals("smb://vasya:p%40ss@192.168.1.10/Video/film.mkv",
            SmbClient.withCredentials("smb://192.168.1.10/Video/film.mkv", listOf(server)))
        // адрес с уже вставленными данными не трогаем; чужой адрес остаётся без данных
        val already = "smb://vasya:p%40ss@192.168.1.10/Video/film.mkv"
        assertEquals(already, SmbClient.withCredentials(already, listOf(server)))
        assertEquals("smb://10.0.0.5/x.mkv", SmbClient.withCredentials("smb://10.0.0.5/x.mkv", listOf(server)))
    }

    // ---------- WebDAV: пароль не уходит по HTTP (дефект 13) ----------

    @Test fun webdavRejectsHttpWithCredentialsUnlessExplicitlyAllowed() {
        val dav = WebDav(OkHttpClient(), allowInsecureHttp = false)
        assertFailsWithMessage(dav, "http://dav.local/backup.json", "user")
        dav.checkUrl("https://dav.local/backup.json", "user") // HTTPS — ок
        dav.checkUrl("http://dav.local/backup.json", null)    // без учётных данных — ок
        val lax = WebDav(OkHttpClient(), allowInsecureHttp = true)
        lax.checkUrl("http://dav.local/backup.json", "user")  // осознанное разрешение
    }

    private fun assertFailsWithMessage(dav: WebDav, url: String, user: String) {
        try {
            dav.checkUrl(url, user)
            fail("Ожидалась ошибка передачи пароля по HTTP")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("HTTPS"))
        }
    }
}
