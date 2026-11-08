package com.local.mp3box.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.local.mp3box.data.MusicFolderRepository
import com.local.mp3box.data.PlaylistRepository
import com.local.mp3box.data.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AddSongsViewModel(
    private val playlistId: Long,
    folderRepository: MusicFolderRepository,
    private val repository: PlaylistRepository
) : ViewModel() {

    val tracks: StateFlow<List<Track>?> =
        folderRepository.tracks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val existingTrackIds: StateFlow<Set<Long>> = repository.observeTrackIds(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    val selected: StateFlow<Set<Long>> = _selected.asStateFlow()

    val visibleTracks: StateFlow<List<Track>?> = combine(tracks, _query) { all, query ->
        if (all == null) {
            null
        } else {
            val needle = query.trim()
            if (needle.isEmpty()) all else all.filter { it.title.contains(needle, ignoreCase = true) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun onQueryChange(query: String) {
        _query.value = query
    }

    fun toggle(trackId: Long) {
        _selected.value = if (trackId in _selected.value) {
            _selected.value - trackId
        } else {
            _selected.value + trackId
        }
    }

    /** Inserts in list order; already-present songs are skipped by the repository. */
    fun add(onDone: () -> Unit) {
        val chosen = _selected.value
        if (chosen.isEmpty()) return
        viewModelScope.launch {
            val ordered = tracks.value.orEmpty()
                .map { it.id }
                .filter { it in chosen }
            repository.addTracks(playlistId, ordered)
            _selected.value = emptySet()
            onDone()
        }
    }
}
