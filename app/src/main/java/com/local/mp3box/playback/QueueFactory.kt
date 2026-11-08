package com.local.mp3box.playback

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import com.local.mp3box.data.model.PlaylistEntryRow
import com.local.mp3box.data.model.Track
import com.local.mp3box.data.model.toDomain
import com.local.mp3box.util.tileJpeg

/**
 * Plan §7.3: missing tracks never enter the queue, metadata carries the derived title only
 * (no artist), and each item gets the generated gradient as artwork.
 *
 * Media3 parcels every MediaItem to the controller in one binder transaction, so artwork is
 * attached only while the queue stays inside a fixed byte budget (~5 KB per 512 px gradient).
 * A huge folder loses artwork on the tail of its queue but never throws TransactionTooLarge.
 */
object QueueFactory {

    private const val ARTWORK_BUDGET_BYTES = 600_000

    fun forTracks(tracks: List<Track>): List<MediaItem> {
        var artworkBytes = 0
        return tracks.filterNot { it.isMissing }.map { track ->
            val artwork = tileJpeg(track.title)
            val attach = artworkBytes + artwork.size <= ARTWORK_BUDGET_BYTES
            if (attach) artworkBytes += artwork.size
            mediaItem(track, if (attach) artwork else null)
        }
    }

    fun forPlaylist(entries: List<PlaylistEntryRow>): List<MediaItem> =
        forTracks(entries.map { it.toDomain().track })

    private fun mediaItem(track: Track, artwork: ByteArray?): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .apply {
                if (artwork != null) {
                    setArtworkData(artwork, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                }
            }
            .build()
        return MediaItem.Builder()
            .setMediaId(track.id.toString())
            .setUri(track.uri.toUri())
            .setMimeType(mimeTypeFor(track.fileName))
            .setMediaMetadata(metadata)
            .build()
    }

    /**
     * SAF document URIs have no file extension for ExoPlayer to sniff, so the container is
     * spelled out here: M4A is AAC in an MP4 container, everything else is MPEG audio.
     */
    private fun mimeTypeFor(fileName: String): String =
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "m4a" -> MimeTypes.AUDIO_MP4
            else -> MimeTypes.AUDIO_MPEG
        }
}
