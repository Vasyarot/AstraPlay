package com.astraplay.tv

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.astraplay.tv.data.torrent.TorrServerClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class TorrServerProtocolTest {
    @Test fun selectsTheServerFileIndexWithoutChangingIt() = runBlocking {
        val hash = "0123456789abcdef0123456789abcdef01234567"
        ServerSocket(0).use { server ->
            val worker = thread(isDaemon = true) {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    var length = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                    }
                    val body = CharArray(length)
                    var offset = 0
                    while (offset < length) { val count = reader.read(body, offset, length - offset); if (count < 0) break; offset += count }
                    val json = """{"hash":"$hash","file_stats":[{"id":4,"path":"Season/episode.mkv","length":12345},{"id":5,"path":"readme.txt","length":10}]}""".toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${json.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(json); flush()
                    }
                }
            }
            val client = TorrServerClient("http://127.0.0.1:${server.localPort}", OkHttpClient())
            val (returnedHash, files) = client.videos("magnet:?xt=urn:btih:$hash")
            assertEquals(hash, returnedHash)
            assertEquals(1, files.size)
            assertEquals(4, files.single().index)
            assertEquals("4", client.stream(hash, files.single()).toHttpUrl().queryParameter("index"))
            worker.join(5000)
        }
    }
}
