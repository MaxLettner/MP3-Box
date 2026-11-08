package com.local.mp3box.data

import android.net.Uri
import com.local.mp3box.data.db.PlaylistDao
import com.local.mp3box.data.db.PlaylistEntity
import com.local.mp3box.data.model.PlaylistEntryRow
import com.local.mp3box.data.model.PlaylistStatsRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Spec §7: create/rename/delete/duplicate, add/remove, reorder, covers. Every mutation bumps
 * `updatedAt`, which is the grid's only sort key (spec §7.3). Songs are never deleted here.
 */
class PlaylistRepository(
    private val dao: PlaylistDao,
    private val coverArtStore: CoverArtStore
) {

    val playlists: Flow<List<PlaylistStatsRow>> = dao.observePlaylistsWithStats()

    fun observePlaylist(id: Long): Flow<PlaylistEntity?> = dao.observePlaylist(id)

    fun observeEntries(id: Long): Flow<List<PlaylistEntryRow>> = dao.observePlaylistEntries(id)

    fun observeTrackIds(id: Long): Flow<Set<Long>> =
        dao.observePlaylistEntries(id).map { rows -> rows.mapTo(HashSet()) { it.id } }

    /** Null when the name is blank. */
    suspend fun create(name: String, trackIds: List<Long> = emptyList()): Long? {
        val clean = name.trim()
        if (clean.isEmpty()) return null
        return dao.createWithTracks(clean, trackIds.distinct(), now())
    }

    suspend fun rename(id: Long, name: String): Boolean {
        val clean = name.trim()
        if (clean.isEmpty()) return false
        dao.rename(id, clean, now())
        return true
    }

    suspend fun delete(id: Long) {
        val cover = dao.findPlaylist(id)?.coverPath
        dao.deleteById(id)
        coverArtStore.delete(cover)
    }

    suspend fun duplicate(id: Long): Long? {
        val source = dao.findPlaylist(id) ?: return null
        val newId = dao.duplicateWithItems(id, copyName(source.name), now()) ?: return null
        coverArtStore.copy(source.coverPath, newId)?.let { path ->
            dao.setCover(newId, path, now())
        }
        return newId
    }

    /** Returns the number of rows added; duplicates and already-present songs are skipped. */
    suspend fun addTracks(id: Long, trackIds: List<Long>): Int =
        if (trackIds.isEmpty()) 0 else dao.addTracks(id, trackIds.distinct(), now())

    suspend fun removeTrack(playlistId: Long, trackId: Long) =
        dao.removeTracksAndTouch(playlistId, listOf(trackId), now())

    suspend fun removeTracks(playlistId: Long, trackIds: Collection<Long>) =
        dao.removeTracksAndTouch(playlistId, trackIds.toList(), now())

    suspend fun reorder(playlistId: Long, orderedItemIds: List<Long>) =
        dao.reorder(playlistId, orderedItemIds, now())

    suspend fun removeUnavailable(id: Long) = dao.removeUnavailable(id, now())

    suspend fun setCover(id: Long, uri: Uri): Boolean {
        val current = dao.findPlaylist(id) ?: return false
        val path = coverArtStore.import(id, uri, current.coverPath) ?: return false
        dao.setCover(id, path, now())
        return true
    }

    suspend fun clearCover(id: Long) {
        val current = dao.findPlaylist(id) ?: return
        dao.setCover(id, null, now())
        coverArtStore.delete(current.coverPath)
    }

    suspend fun entries(id: Long): List<PlaylistEntryRow> = dao.getPlaylistEntries(id)

    suspend fun name(id: Long): String? = dao.playlistName(id)

    private fun copyName(name: String): String =
        if (name.endsWith(COPY_SUFFIX)) name else "$name$COPY_SUFFIX"

    private fun now() = System.currentTimeMillis()

    private companion object {
        const val COPY_SUFFIX = " (copy)"
    }
}
