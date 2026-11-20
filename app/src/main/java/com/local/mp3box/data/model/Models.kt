package com.local.mp3box.data.model

import androidx.room.Embedded
import com.local.mp3box.data.db.PlaybackStateEntity
import com.local.mp3box.data.db.TrackEntity
import com.local.mp3box.util.NaturalOrder

/** Domain model used by the UI; entity columns stay out of composables. */
data class Track(
    val id: Long,
    val uri: String,
    val fileName: String,
    val title: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val lastModified: Long,
    val isMissing: Boolean,
    val lastSeenAt: Long
)

/** Grid row: playlist plus the three stats the card shows. */
data class PlaylistStatsRow(
    val id: Long,
    val name: String,
    val coverPath: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val songCount: Int,
    val durationMs: Long,
    val missingCount: Int
)

/** Playlist-detail row: the track's columns plus its place in the list. */
data class PlaylistEntryRow(
    @Embedded val track: Track,
    val position: Int,
    val itemId: Long
)

fun TrackEntity.toDomain(): Track = Track(
    id = id,
    uri = uri,
    fileName = fileName,
    title = title,
    durationMs = durationMs,
    sizeBytes = sizeBytes,
    lastModified = lastModified,
    isMissing = isMissing,
    lastSeenAt = lastSeenAt
)

/** Natural A–Z by title (spec §8.3); SQLite cannot order digit runs numerically. */
fun List<TrackEntity>.toSortedTracks(): List<Track> =
    map(TrackEntity::toDomain).sortedWith(compareBy(NaturalOrder) { it.title })

/** Library search: case-insensitive title match; a blank query keeps every track. */
fun List<Track>.filterByTitle(query: String): List<Track> {
    val needle = query.trim()
    return if (needle.isEmpty()) this else filter { it.title.contains(needle, ignoreCase = true) }
}

fun PlaybackStateEntity.toDomain(): PlaybackSnapshot = PlaybackSnapshot(
    playlistId = playlistId,
    trackId = trackId,
    positionMs = positionMs,
    shuffleEnabled = shuffleEnabled,
    repeatMode = repeatMode,
    shuffleOrder = shuffleOrder.orEmpty()
        .split(',')
        .mapNotNull { it.toLongOrNull() }
)

data class PlaybackSnapshot(
    val playlistId: Long?,
    val trackId: Long?,
    val positionMs: Long,
    val shuffleEnabled: Boolean,
    val repeatMode: Int,
    /** Track ids in the saved shuffled play order; empty when shuffle was off. */
    val shuffleOrder: List<Long> = emptyList()
)
