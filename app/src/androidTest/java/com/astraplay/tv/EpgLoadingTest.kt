package com.astraplay.tv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.astraplay.tv.core.Prefs
import com.astraplay.tv.data.iptv.EpgStore
import com.astraplay.tv.data.net.Http
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class EpgLoadingTest {
    @Test fun gzipXmltvLoadsBeforeConnectionClosesAndResolvesChannelNames() = runBlocking {
        val now = System.currentTimeMillis()
        val formatter = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        fun time(offset: Long) = formatter.format(Date(now + offset))
        val xml = """<?xml version="1.0" encoding="UTF-8"?><tv>
            <channel id="test"><display-name>Тестовый канал</display-name></channel>
            <programme channel="test" start="${time(3600000)}" stop="${time(7200000)}"><title>Следующая передача</title></programme>
            <programme channel="test" start="${time(-600000)}" stop="${time(3600000)}"><title>Текущая передача</title><desc>Описание</desc></programme>
            </tv>"""
        val compressed = ByteArrayOutputStream().apply { GZIPOutputStream(this).use { it.write(xml.toByteArray()) } }.toByteArray()
        ServerSocket(0).use { server ->
            val worker = thread(isDaemon = true) {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/gzip\r\nContent-Length: ${compressed.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(compressed)
                        flush()
                    }
                }
            }
            val epg = EpgStore(Http(Prefs(InstrumentationRegistry.getInstrumentation().targetContext)))
            val result = epg.ensureLoaded("http://127.0.0.1:${server.localPort}/epg.xml.gz")
            assertTrue(result.exceptionOrNull()?.toString(), result.isSuccess)
            assertEquals("Текущая передача", epg.nowFor("test", now)?.title)
            assertEquals("Текущая передача", epg.nowFor("Тестовый канал", now)?.title)
            assertEquals(listOf("Текущая передача", "Следующая передача"), epg.upcoming("test").map { it.title })
            worker.join(5000)
        }
    }
}
