package com.local.mp3box.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.local.mp3box.data.MusicFolderRepository
import com.local.mp3box.data.PlaylistRepository
import com.local.mp3box.data.ScanResult
import com.local.mp3box.data.model.PlaylistStatsRow
import com.local.mp3box.data.model.Track
import com.local.mp3box.playback.PlaybackEvent
import com.local.mp3box.playback.PlayerRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface LibraryEvent {
    data class SongsFound(val count: Int) : LibraryEvent
    data class ScanFinished(val added: Int, val missing: Int) : LibraryEvent
    data object FolderUnusable : LibraryEvent
    data class AddedToPlaylist(val name: String) : LibraryEvent
    data object AlreadyInPlaylist : LibraryEvent
    data class CreatedPlaylist(val name: String) : LibraryEvent
}

class LibraryViewModel(
    private val folderRepository: MusicFolderRepository,
    private val player: PlayerRepository,
    private val playlistRepository: PlaylistRepository
) : ViewModel() {

    val folderState = folderRepository.folderState
    val isScanning = folderRepository.isScanning

    /** Null until the DB has answered, so the empty state cannot flash on launch. */
    val tracks: StateFlow<List<Track>?> = folderRepository.tracks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val visibleTracks: StateFlow<List<Track>?> =
        combine(folderRepository.tracks, _query) { all, query ->
            val needle = query.trim()
            if (needle.isEmpty()) all else all.filter { it.title.contains(needle, ignoreCase = true) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _events = Channel<LibraryEvent>(Channel.BUFFERED)
    val events: Flow<LibraryEvent> = _events.receiveAsFlow()

    val currentTrackId: StateFlow<Long?> = player.state
        .map { it.currentTrackId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val playbackEvents: Flow<PlaybackEvent> = player.events

    val playlists: StateFlow<List<PlaylistStatsRow>> = playlistRepository.playlists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Spec §6.2: the tapped song starts the library list as the queue. */
    fun play(trackId: Long) {
        player.playLibrary(trackId)
    }

    fun addToPlaylist(playlistId: Long, trackId: Long) {
        viewModelScope.launch {
            val name = playlistRepository.name(playlistId).orEmpty()
            val added = playlistRepository.addTracks(playlistId, listOf(trackId))
            _events.send(
                if (added > 0) LibraryEvent.AddedToPlaylist(name) else LibraryEvent.AlreadyInPlaylist
            )
        }
    }

    fun createPlaylistWithTrack(name: String, trackId: Long) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            if (playlistRepository.create(clean, listOf(trackId)) != null) {
                _events.send(LibraryEvent.CreatedPlaylist(clean))
            }
        }
    }

    fun onQueryChange(query: String) {
        _query.value = query
    }

    fun pickFolder(uri: Uri) {
        viewModelScope.launch {
            if (!folderRepository.onFolderPicked(uri)) {
                _events.send(LibraryEvent.FolderUnusable)
                return@launch
            }
            folderRepository.lastResult.value?.let {
                _events.send(LibraryEvent.SongsFound(it.total))
            }
        }
    }

    fun rescan() {
        viewModelScope.launch {
            val result: ScanResult = folderRepository.scan() ?: return@launch
            _events.send(LibraryEvent.ScanFinished(result.added, result.missing))
        }
    }
}
