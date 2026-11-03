package com.astraplay.tv.data.iptv

import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

class ChannelProbeTest {
    private fun probe(body: String, type: String, code: Int = 200): Boolean {
        ServerSocket(0).use { server ->
            val worker = thread(isDaemon = true) {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val bytes = body.toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 $code Test\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes)
                        flush()
                    }
                }
            }
            val result = ChannelProbe(OkHttpClient()).responds("http://127.0.0.1:${server.localPort}/live")
            worker.join(4000)
            return result
        }
    }

    @Test fun recognizesHlsInsteadOfAnHtmlErrorPage() {
        assertTrue(probe("#EXTM3U\n#EXTINF:10,\nsegment.ts", "text/plain"))
        assertFalse(probe("<!DOCTYPE html><html>Access denied</html>", "text/html"))
        assertFalse(probe("Missing", "video/mp2t", 404))
    }

    @Test fun filteredRussianPresetsKeepOnlyTheRequestedCategory() {
        val text = """#EXTM3U
#EXTINF:-1 group-title="Music;Entertainment",Музыка
https://example.com/music
#EXTINF:-1 group-title="Movies",Кино
https://example.com/movies
"""
        assertEquals(listOf("Музыка"), M3uParser.forPlaylist(text, "https://example.com/rus.m3u#astra-category=music").map { it.name })
        assertEquals(2, M3uParser.forPlaylist(text, "https://example.com/rus.m3u").size)
    }
}
