package com.local.mp3box.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.local.mp3box.data.model.PlaylistEntryRow
import com.local.mp3box.data.model.PlaylistStatsRow
import kotlinx.coroutines.flow.Flow

@Dao
abstract class PlaylistDao {

    @Query(
        """
        SELECT p.*,
               (SELECT COUNT(*) FROM playlist_items pi WHERE pi.playlistId = p.id) AS songCount,
               (SELECT COALESCE(SUM(t.durationMs), 0) FROM playlist_items pi
                  JOIN tracks t ON t.id = pi.trackId
                  WHERE pi.playlistId = p.id AND t.isMissing = 0) AS durationMs,
               (SELECT COUNT(*) FROM playlist_items pi JOIN tracks t ON t.id = pi.trackId
                  WHERE pi.playlistId = p.id AND t.isMissing = 1) AS missingCount
        FROM playlists p ORDER BY p.updatedAt DESC, p.id DESC
        """
    )
    abstract fun observePlaylistsWithStats(): Flow<List<PlaylistStatsRow>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    abstract fun observePlaylist(id: Long): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlists WHERE id = :id")
    abstract suspend fun findPlaylist(id: Long): PlaylistEntity?

    @Query(
        """
        SELECT t.*, pi.position AS position, pi.id AS itemId
        FROM playlist_items pi JOIN tracks t ON t.id = pi.trackId
        WHERE pi.playlistId = :playlistId
        ORDER BY pi.position ASC, pi.id ASC
        """
    )
    abstract fun observePlaylistEntries(playlistId: Long): Flow<List<PlaylistEntryRow>>

    /** One-shot read used to build a playback queue (no Flow collection needed). */
    @Query(
        """
        SELECT t.*, pi.position AS position, pi.id AS itemId
        FROM playlist_items pi JOIN tracks t ON t.id = pi.trackId
        WHERE pi.playlistId = :playlistId
        ORDER BY pi.position ASC, pi.id ASC
        """
    )
    abstract suspend fun getPlaylistEntries(playlistId: Long): List<PlaylistEntryRow>

    @Query("SELECT name FROM playlists WHERE id = :playlistId")
    abstract suspend fun playlistName(playlistId: Long): String?

    @Query("SELECT trackId FROM playlist_items WHERE playlistId = :playlistId")
    abstract suspend fun trackIdsIn(playlistId: Long): List<Long>

    @Insert
    abstract suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name, updatedAt = :now WHERE id = :id")
    abstract suspend fun rename(id: Long, name: String, now: Long)

    @Query("UPDATE playlists SET coverPath = :path, updatedAt = :now WHERE id = :id")
    abstract suspend fun setCover(id: Long, path: String?, now: Long)

    @Query("DELETE FROM playlists WHERE id = :id")
    abstract suspend fun deleteById(id: Long)

    /** Called in the same transaction as every playlist mutation. */
    @Query("UPDATE playlists SET updatedAt = :now WHERE id = :id")
    abstract suspend fun touch(id: Long, now: Long)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_items WHERE playlistId = :playlistId")
    abstract suspend fun nextPosition(playlistId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertItems(items: List<PlaylistItemEntity>): List<Long>   // -1 = duplicate

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId AND trackId = :trackId")
    abstract suspend fun removeTrack(playlistId: Long, trackId: Long)

    @Query("UPDATE playlist_items SET position = :position WHERE id = :itemId")
    abstract suspend fun updatePosition(itemId: Long, position: Int)

    @Query(
        "DELETE FROM playlist_items WHERE playlistId = :playlistId " +
            "AND trackId IN (SELECT id FROM tracks WHERE isMissing = 1)"
    )
    abstract suspend fun deleteUnavailableItems(playlistId: Long)

    @Transaction
    open suspend fun reorder(playlistId: Long, orderedItemIds: List<Long>, now: Long) {
        orderedItemIds.forEachIndexed { index, itemId -> updatePosition(itemId, index) }
        touch(playlistId, now)
    }

    @Transaction
    open suspend fun removeUnavailable(playlistId: Long, now: Long) {
        deleteUnavailableItems(playlistId)
        touch(playlistId, now)
    }

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId AND trackId IN (:trackIds)")
    abstract suspend fun deleteTracks(playlistId: Long, trackIds: List<Long>)

    @Transaction
    open suspend fun removeTracksAndTouch(playlistId: Long, trackIds: List<Long>, now: Long) {
        if (trackIds.isEmpty()) return
        deleteTracks(playlistId, trackIds)
        touch(playlistId, now)
    }

    /** Spec §7.1 flow A: create with the picked songs in one transaction. */
    @Transaction
    open suspend fun createWithTracks(name: String, trackIds: List<Long>, now: Long): Long {
        val id = insertPlaylist(PlaylistEntity(name = name, createdAt = now, updatedAt = now))
        if (trackIds.isNotEmpty()) {
            insertItems(
                trackIds.mapIndexed { index, trackId ->
                    PlaylistItemEntity(playlistId = id, trackId = trackId, position = index)
                }
            )
        }
        return id
    }

    /** Returns how many rows were really inserted; duplicates are skipped by the unique index. */
    @Transaction
    open suspend fun addTracks(playlistId: Long, trackIds: List<Long>, now: Long): Int {
        if (trackIds.isEmpty()) return 0
        val start = nextPosition(playlistId)
        val results = insertItems(
            trackIds.mapIndexed { index, trackId ->
                PlaylistItemEntity(playlistId = playlistId, trackId = trackId, position = start + index)
            }
        )
        val added = results.count { it != -1L }
        if (added > 0) touch(playlistId, now)
        return added
    }

    /** Copies rows in saved order; the cover JPEG is copied by the repository once the id exists. */
    @Transaction
    open suspend fun duplicateWithItems(sourceId: Long, name: String, now: Long): Long? {
        findPlaylist(sourceId) ?: return null
        val newId = insertPlaylist(PlaylistEntity(name = name, createdAt = now, updatedAt = now))
        val entries = getPlaylistEntries(sourceId)
        if (entries.isNotEmpty()) {
            insertItems(
                entries.mapIndexed { index, entry ->
                    PlaylistItemEntity(playlistId = newId, trackId = entry.id, position = index)
                }
            )
        }
        return newId
    }
}
