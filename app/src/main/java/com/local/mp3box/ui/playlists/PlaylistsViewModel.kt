package com.local.mp3box.ui.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.local.mp3box.data.PlaylistRepository
import com.local.mp3box.data.model.PlaylistStatsRow
import com.local.mp3box.playback.PlayerRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface PlaylistsEvent {
    data object Duplicated : PlaylistsEvent
    data object DuplicateFailed : PlaylistsEvent
}

class PlaylistsViewModel(
    private val repository: PlaylistRepository,
    private val player: PlayerRepository
) : ViewModel() {

    /** Null until Room has answered, so the empty state cannot flash. */
    val playlists: StateFlow<List<PlaylistStatsRow>?> =
        repository.playlists.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _events = Channel<PlaylistsEvent>(Channel.BUFFERED)
    val events: Flow<PlaylistsEvent> = _events.receiveAsFlow()

    fun create(name: String, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            repository.create(name)?.let(onCreated)
        }
    }

    fun rename(id: Long, name: String) {
        viewModelScope.launch { repository.rename(id, name) }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            player.stopPlaylistIfCurrent(id)
            repository.delete(id)
        }
    }

    fun duplicate(id: Long) {
        viewModelScope.launch {
            val newId = repository.duplicate(id)
            _events.send(if (newId != null) PlaylistsEvent.Duplicated else PlaylistsEvent.DuplicateFailed)
        }
    }
}
