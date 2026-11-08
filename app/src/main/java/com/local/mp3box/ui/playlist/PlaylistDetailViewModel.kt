package com.local.mp3box.ui.playlist

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.local.mp3box.data.PlaylistRepository
import com.local.mp3box.data.model.PlaylistEntryRow
import com.local.mp3box.playback.PlayerRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface PlaylistDetailEvent {
    data object CoverFailed : PlaylistDetailEvent
    data object Duplicated : PlaylistDetailEvent
    data object DuplicateFailed : PlaylistDetailEvent
}

class PlaylistDetailViewModel(
    private val playlistId: Long,
    private val repository: PlaylistRepository,
    private val player: PlayerRepository
) : ViewModel() {

    val playlist = repository.observePlaylist(playlistId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val playerState = player.state

    /** Remembered drag order, so rows follow the finger before Room round-trips. */
    private val orderOverride = MutableStateFlow<List<Long>?>(null)

    val entries: StateFlow<List<PlaylistEntryRow>> = combine(
        repository.observeEntries(playlistId),
        orderOverride
    ) { rows, order ->
        if (order == null) {
            rows
        } else {
            val byId = rows.associateBy { it.itemId }
            order.mapNotNull { byId[it] }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _events = Channel<PlaylistDetailEvent>(Channel.BUFFERED)
    val events: Flow<PlaylistDetailEvent> = _events.receiveAsFlow()

    init {
        // Drop the local order as soon as the database holds exactly that order.
        viewModelScope.launch {
            repository.observeEntries(playlistId).collect { rows ->
                val order = orderOverride.value ?: return@collect
                if (rows.map { it.itemId } == order) orderOverride.value = null
            }
        }
    }

    fun playFromTop() {
        player.playPlaylist(playlistId)
    }

    /** Spec §8.5: turns shuffle on and starts playing. */
    fun shufflePlay() {
        player.playPlaylist(playlistId, shuffle = true)
    }

    fun playTrack(trackId: Long) {
        val entry = entries.value.firstOrNull { it.id == trackId } ?: return
        if (entry.isMissing) return
        player.playPlaylist(playlistId, startTrackId = trackId)
    }

    fun rename(name: String) {
        viewModelScope.launch { repository.rename(playlistId, name) }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            player.stopPlaylistIfCurrent(playlistId)
            repository.delete(playlistId)
            onDeleted()
        }
    }

    fun duplicate() {
        viewModelScope.launch {
            val newId = repository.duplicate(playlistId)
            _events.send(
                if (newId != null) PlaylistDetailEvent.Duplicated else PlaylistDetailEvent.DuplicateFailed
            )
        }
    }

    fun removeTrack(trackId: Long) {
        viewModelScope.launch { repository.removeTrack(playlistId, trackId) }
    }

    fun removeTracks(trackIds: Set<Long>) {
        if (trackIds.isEmpty()) return
        viewModelScope.launch { repository.removeTracks(playlistId, trackIds) }
    }

    fun removeUnavailable() {
        viewModelScope.launch { repository.removeUnavailable(playlistId) }
    }

    fun setCover(uri: Uri) {
        viewModelScope.launch {
            if (!repository.setCover(playlistId, uri)) {
                _events.send(PlaylistDetailEvent.CoverFailed)
            }
        }
    }

    fun clearCover() {
        viewModelScope.launch { repository.clearCover(playlistId) }
    }

    fun move(from: Int, to: Int) {
        val current = entries.value.toMutableList()
        if (from !in current.indices || to !in current.indices || from == to) return
        current.add(to, current.removeAt(from))
        orderOverride.value = current.map { it.itemId }
    }

    /** Persists the in-memory drag order once the gesture ends. */
    fun commitOrder() {
        val order = orderOverride.value ?: return
        viewModelScope.launch { repository.reorder(playlistId, order) }
    }
}
