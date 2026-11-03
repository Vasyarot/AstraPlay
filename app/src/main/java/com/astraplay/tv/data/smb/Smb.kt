package com.astraplay.tv.data.smb

import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.SmbFile
import java.util.Properties

/**
 * Обёртка над jcifs-ng (SMB2/3). Все операции — на Dispatchers.IO со стороны вызова.
 */
object SmbClient {

    private val context: CIFSContext by lazy {
        val props = Properties().apply {
            // Современные NAS требуют SMB2/3
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.responseTimeout", "15000")
            setProperty("jcifs.smb.client.connTimeout", "10000")
        }
        BaseContext(PropertyConfiguration(props))
    }

    private fun authenticated(url: String): CIFSContext {
        val regex = Regex("^smb://([^:]+):([^@]*)@")
        val match = regex.find(url) ?: return context.withAnonymousCredentials()
        val (u, p) = match.destructured
        return context.withCredentials(
            jcifs.smb.NtlmPasswordAuthenticator(
                "", java.net.URLDecoder.decode(u, "UTF-8"), java.net.URLDecoder.decode(p, "UTF-8")
            )
        )
    }

    private fun stripCredentials(url: String): String =
        url.replace(Regex("^smb://([^:]+):([^@]*)@"), "smb://")

    fun file(url: String): SmbFile =
        SmbFile(stripCredentials(url), authenticated(url))

    /** Содержимое папки: сначала папки, затем видео-файлы. */
    fun list(dirUrl: String): List<Pair<SmbFile, Boolean>> {
        val files = file(dirUrl).listFiles()
        return files
            .map { it to it.isDirectory() }
            .sortedWith(compareByDescending<Pair<SmbFile, Boolean>> { it.second }
                .thenBy { it.first.name.lowercase() })
    }

    // видео-расширения
    val VIDEO_EXT = setOf("mp4", "mkv", "avi", "ts", "m2ts", "mov", "webm", "flv", "wmv", "mpg", "mpeg")

    fun isVideo(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in VIDEO_EXT
}
