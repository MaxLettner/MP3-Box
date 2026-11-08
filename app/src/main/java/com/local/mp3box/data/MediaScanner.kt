package com.local.mp3box.data

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One direct child of the granted tree that is a playable audio file. */
data class FoundFile(
    val uri: Uri,
    val fileName: String,
    val sizeBytes: Long,
    val lastModified: Long
)

/**
 * Lists the folder's direct children through a single cursor query (plan §6.2) and
 * reads duration from the container header only (plan §6.3). No ID3, no DocumentFile.
 */
class MediaScanner(private val context: Context) {

    /** Used to validate a vendor picker result before persisting the grant (plan §6.1). */
    suspend fun isReachable(treeUri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(
                childrenUri(treeUri),
                arrayOf(Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null
            )?.use { it.moveToFirst() } ?: false
        }.getOrDefault(false)
    }

    suspend fun listTopLevelAudio(treeUri: Uri): List<FoundFile> = withContext(Dispatchers.IO) {
        val found = mutableListOf<FoundFile>()
        context.contentResolver.query(
            childrenUri(treeUri),
            arrayOf(
                Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE,
                Document.COLUMN_LAST_MODIFIED
            ),
            null,
            null,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val mime = cursor.getString(2) ?: continue
                if (mime == Document.MIME_TYPE_DIR) continue          // subfolders ignored
                val name = cursor.getString(1) ?: continue
                if (extensionOf(name) !in AUDIO_EXTENSIONS) continue
                found += FoundFile(
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0)),
                    fileName = name,
                    sizeBytes = cursor.getLong(3),
                    lastModified = cursor.getLong(4)
                )
            }
        }
        found
    }

    /** Returns 0 when the header cannot be parsed; the UI then renders `--:--`. */
    suspend fun durationMs(uri: Uri): Long = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                val extractor = MediaExtractor()
                try {
                    val length = descriptor.statSize
                    if (length > 0) {
                        extractor.setDataSource(descriptor.fileDescriptor, 0, length)
                    } else {
                        extractor.setDataSource(context, uri, null)
                    }
                    (0 until extractor.trackCount)
                        .map { extractor.getTrackFormat(it) }
                        .firstOrNull { it.containsKey(MediaFormat.KEY_DURATION) }
                        ?.getLong(MediaFormat.KEY_DURATION)
                        ?.div(1000) ?: 0L
                } finally {
                    extractor.release()
                }
            } ?: 0L
        }.getOrDefault(0L)
    }

    private fun extensionOf(fileName: String): String =
        fileName.substringAfterLast('.', "").lowercase()

    private companion object {
        /** MP3 (MPEG audio) and M4A (AAC in an MP4 container). */
        val AUDIO_EXTENSIONS = setOf("mp3", "m4a")
    }

    private fun childrenUri(treeUri: Uri): Uri =
        DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        )
}
