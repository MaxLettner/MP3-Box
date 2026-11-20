package com.local.mp3box.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Spec §5.5: a missing file no playlist points at has nothing left to show, so the row is
 * dropped for good. Rows still referenced stay to render the playlist's "File missing" stub.
 */
internal const val DELETE_ORPHANED_MISSING_TRACKS =
    "DELETE FROM tracks WHERE isMissing = 1 AND id NOT IN (SELECT trackId FROM playlist_items)"

// Spec §5.5: the library lists playable files only. A missing track's row survives a scan
// just long enough to back the playlist stub that still points at it.
private const val AVAILABLE_TRACKS = "SELECT * FROM tracks WHERE isMissing = 0 ORDER BY title ASC"

@Dao
abstract class TrackDao {

    @Query(AVAILABLE_TRACKS)
    abstract fun observeTracks(): Flow<List<TrackEntity>>

    @Query(AVAILABLE_TRACKS)
    abstract suspend fun getTracks(): List<TrackEntity>

    /** Every row, missing ones included: the scan's view of what the library already knows. */
    @Query("SELECT * FROM tracks")
    abstract suspend fun getAll(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE uri = :uri LIMIT 1")
    abstract suspend fun findByUri(uri: String): TrackEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(track: TrackEntity): Long

    /** Overwrites every column of the row with [track]'s id. */
    @Update
    abstract suspend fun update(track: TrackEntity)

    @Query("UPDATE tracks SET isMissing = 0, lastSeenAt = :lastSeenAt WHERE id = :id")
    abstract suspend fun markSeen(id: Long, lastSeenAt: Long)

    @Query("UPDATE tracks SET isMissing = 1 WHERE uri NOT IN (:seenUris)")
    abstract suspend fun markMissingExcept(seenUris: List<String>)

    @Query("UPDATE tracks SET isMissing = 1")
    abstract suspend fun markAllMissing()

    /** Present rows that this listing no longer contains: the files lost by this scan. */
    @Query("SELECT COUNT(*) FROM tracks WHERE isMissing = 0 AND uri NOT IN (:seenUris)")
    abstract suspend fun countNewlyMissing(seenUris: List<String>): Int

    @Query(DELETE_ORPHANED_MISSING_TRACKS)
    abstract suspend fun deleteOrphanedMissing()

    @Query("SELECT COUNT(*) FROM tracks WHERE isMissing = 0")
    abstract suspend fun countAvailable(): Int

    /**
     * Closes a scan in one transaction, so Room commits and notifies observers once instead of
     * once per file: confirms the [unchangedIds], marks every row outside [seenUris] missing and
     * drops the orphans. Returns how many playable files this scan lost.
     */
    @Transaction
    open suspend fun finishScan(unchangedIds: List<Long>, seenUris: List<String>, now: Long): Int {
        unchangedIds.forEach { markSeen(it, now) }
        // Count the loss before marking: an already-missing file is not lost twice, so its
        // snackbar line appears on the scan that finds it gone and never again.
        val lost: Int
        if (seenUris.isEmpty()) {
            // Room rejects an empty IN (), so the no-files case needs its own statements.
            lost = countAvailable()
            markAllMissing()
        } else {
            lost = countNewlyMissing(seenUris)
            markMissingExcept(seenUris)
        }
        // Spec §5.5: only rows a playlist still references survive as stubs; the rest of the
        // library loses them for good here, so songs never show unavailable files.
        deleteOrphanedMissing()
        return lost
    }
}
