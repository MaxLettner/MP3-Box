package com.local.mp3box.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.local.mp3box.playback.PlayerRepository
import com.local.mp3box.playback.PlayerUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Shared by the mini-player and Now Playing. Position is polled at 2 Hz only while a screen
 * is in composition (spec §6.1), never permanently.
 */
class PlayerViewModel(private val player: PlayerRepository) : ViewModel() {

    val state: StateFlow<PlayerUiState> = player.state

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private var ticker: Job? = null

    fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = viewModelScope.launch {
            while (isActive) {
                _positionMs.value = player.currentPositionMs
                delay(500)
            }
        }
    }

    fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    fun togglePlayPause() = player.togglePlayPause()
    fun next() = player.next()
    fun previous() = player.previous()
    fun seekTo(positionMs: Long) = player.seekTo(positionMs)
    fun toggleShuffle() = player.toggleShuffle()
    fun cycleRepeat() = player.cycleRepeat()
}
