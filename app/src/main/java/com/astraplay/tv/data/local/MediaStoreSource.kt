package com.astraplay.tv.data.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LocalMedia(
    val id: Long,
    val title: String,
    val uri: String,
    val folder: String,
    val durationMs: Long,
    val sizeBytes: Long,
) {
    val key: String get() = "local_video_$id"
}

/** Видео с устройства и USB-накопителей через MediaStore. */
class MediaStoreSource(private val context: Context) {

    fun hasPermission(): Boolean {
        val perms = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return perms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    suspend fun videos(): Result<List<LocalMedia>> = withContext(Dispatchers.IO) {
        runCatching {
            val out = ArrayList<LocalMedia>(128)
            // BUCKET_DISPLAY_NAME есть только с API 29; на 7/8 берём папку из пути файла
            val useBucket = Build.VERSION.SDK_INT >= 29
            val proj = buildList {
                add(MediaStore.Video.Media._ID)
                add(MediaStore.Video.Media.TITLE)
                add(MediaStore.Video.Media.DURATION)
                add(MediaStore.Video.Media.SIZE)
                if (useBucket) add(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                else add(MediaStore.Video.Media.DATA)
            }
            val sort = if (useBucket)
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME + " ASC, " + MediaStore.Video.Media.TITLE + " ASC"
            else MediaStore.Video.Media.DATA + " ASC"
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                proj.toTypedArray(), null, null, sort
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val iTitle = c.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
                val iDur = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val iSize = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val iBucket = if (useBucket)
                    c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME) else -1
                val iData = if (!useBucket)
                    c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA) else -1
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val folder = if (useBucket) {
                        c.getString(iBucket) ?: "Память"
                    } else {
                        java.io.File(c.getString(iData) ?: "").parentFile?.name ?: "Память"
                    }
                    out += LocalMedia(
                        id = id,
                        title = c.getString(iTitle) ?: "Видео $id",
                        uri = Uri.withAppendedPath(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString()
                        ).toString(),
                        folder = folder,
                        durationMs = c.getLong(iDur),
                        sizeBytes = c.getLong(iSize),
                    )
                }
            }
            out
        }
    }
}
