package com.astraplay.tv.data.smb

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import jcifs.smb.SmbRandomAccessFile

/**
 * DataSource для Media3: стримим видео прямо с SMB-шары,
 * с поддержкой докрутки (seek) через SmbRandomAccessFile.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SmbDataSource : BaseDataSource(true) {

    private var raf: SmbRandomAccessFile? = null
    private var uri: Uri? = null
    private var smbFile: jcifs.smb.SmbFile? = null
    private var opened = false
    private var bytesRemaining = C.LENGTH_UNSET.toLong()

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        try {
            val file = SmbClient.file(dataSpec.uri.toString())
            smbFile = file
            val handle = SmbRandomAccessFile(file, "r")
            raf = handle
            val total = handle.length()
            if (dataSpec.position > total) throw java.io.EOFException("Position exceeds file length")
            handle.seek(dataSpec.position)
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong())
                minOf(dataSpec.length, total - dataSpec.position)
            else total - dataSpec.position
            opened = true
            transferStarted(dataSpec)
            return bytesRemaining
        } catch (error: Exception) {
            close()
            throw java.io.IOException("Cannot open SMB media", error)
        }
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong())
            length else minOf(length.toLong(), bytesRemaining).toInt()
        val n = raf!!.read(target, offset, toRead)
        if (n <= 0) return C.RESULT_END_OF_INPUT
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try { raf?.close() } finally {
            raf = null
            try { smbFile?.close() } finally {
                smbFile = null
                uri = null
                bytesRemaining = 0
                if (opened) {
                    opened = false
                    transferEnded()
                }
            }
        }
    }
}
