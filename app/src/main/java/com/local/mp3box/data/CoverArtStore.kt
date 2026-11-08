package com.local.mp3box.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Plan §5.3: covers are copied into app storage, downscaled to ≤ 640 px and stored as JPEG 88
 * so they survive the original gallery image being deleted (spec §7.2).
 *
 * `ImageDecoder` is used rather than `BitmapFactory` because it applies the EXIF orientation
 * of camera photos for free (minSdk 31, no extra dependency).
 */
class CoverArtStore(context: Context) {

    private val resolver = context.contentResolver
    private val dir = File(context.filesDir, COVERS_DIR)

    suspend fun import(playlistId: Long, source: Uri, oldPath: String?): String? =
        withContext(Dispatchers.IO) {
            val bitmap = decode(source) ?: return@withContext null
            try {
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, "$playlistId-${System.currentTimeMillis()}.jpg")
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                if (oldPath != null && oldPath != file.absolutePath) File(oldPath).delete()
                file.absolutePath
            } catch (_: Exception) {
                null
            } finally {
                bitmap.recycle()
            }
        }

    /** Duplicate gets its own file so deleting one playlist cannot strip the other's cover. */
    suspend fun copy(existingPath: String?, playlistId: Long): String? =
        withContext(Dispatchers.IO) {
            val source = existingPath?.let(::File)?.takeIf { it.exists() } ?: return@withContext null
            runCatching {
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, "$playlistId-${System.currentTimeMillis()}.jpg")
                source.copyTo(file, overwrite = true)
                file.absolutePath
            }.getOrNull()
        }

    suspend fun delete(path: String?) = withContext(Dispatchers.IO) {
        if (!path.isNullOrBlank()) File(path).delete()
    }

    private fun decode(uri: Uri): Bitmap? = runCatching {
        val source = ImageDecoder.createSource(resolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val longest = maxOf(info.size.width, info.size.height)
            var sample = 1
            while (longest / sample > MAX_EDGE) sample *= 2
            decoder.setTargetSampleSize(sample)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }.getOrNull()

    private companion object {
        const val COVERS_DIR = "covers"
        const val MAX_EDGE = 640
    }
}
