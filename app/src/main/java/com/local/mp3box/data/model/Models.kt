package com.local.mp3box.data.model

import com.local.mp3box.data.db.PlaybackStateEntity
import com.local.mp3box.data.db.PlaylistEntity
import com.local.mp3box.data.db.TrackEntity

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

data class Playlist(
    val id: Long,
    val name: String,
    val coverPath: String?,
    val createdAt: Long,
    val updatedAt: Long
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

/** Playlist-detail row: track columns joined with its position in the list. */
data class PlaylistEntryRow(
    val id: Long,
    val uri: String,
    val fileName: String,
    val title: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val lastModified: Long,
    val isMissing: Boolean,
    val lastSeenAt: Long,
    val position: Int,
    val itemId: Long
)

data class PlaylistEntry(
    val itemId: Long,
    val position: Int,
    val track: Track
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

fun PlaylistEntity.toDomain(): Playlist = Playlist(
    id = id,
    name = name,
    coverPath = coverPath,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun PlaylistStatsRow.toDomain(): Playlist = Playlist(
    id = id,
    name = name,
    coverPath = coverPath,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun PlaylistEntryRow.toDomain(): PlaylistEntry = PlaylistEntry(
    itemId = itemId,
    position = position,
    track = Track(
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
)

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
