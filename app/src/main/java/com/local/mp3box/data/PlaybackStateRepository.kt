package com.local.mp3box.data

import com.local.mp3box.data.db.PLAYBACK_STATE_ID
import com.local.mp3box.data.db.PlaybackStateDao
import com.local.mp3box.data.db.PlaybackStateEntity
import com.local.mp3box.data.model.PlaybackSnapshot
import com.local.mp3box.data.model.toDomain

/**
 * The resume snapshot: one row, overwritten on every transition / mode change / pause
 * (debounced by the caller). `positionMs` is stored but deliberately not restored in v1.
 */
class PlaybackStateRepository(private val dao: PlaybackStateDao) {

    suspend fun load(): PlaybackSnapshot? = dao.get(PLAYBACK_STATE_ID)?.toDomain()

    suspend fun save(state: PlaybackStateEntity) = dao.put(state)

    suspend fun clear() = dao.clear()
}
