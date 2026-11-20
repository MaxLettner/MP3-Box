package com.local.mp3box.ui.player

import androidx.lifecycle.ViewModel
import com.local.mp3box.playback.PlayerRepository
import com.local.mp3box.playback.PlayerUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Now Playing's view of the shared player. [positionMs] polls only while it is collected, and
 * `collectAsStateWithLifecycle` stops collecting in the background (spec §6.1).
 */
class PlayerViewModel(private val player: PlayerRepository) : ViewModel() {

    val state: StateFlow<PlayerUiState> = player.state

    val positionMs: Flow<Long> = player.positionMs

    fun togglePlayPause() = player.togglePlayPause()
    fun next() = player.next()
    fun previous() = player.previous()
    fun seekTo(positionMs: Long) = player.seekTo(positionMs)
    fun toggleShuffle() = player.toggleShuffle()
    fun cycleRepeat() = player.cycleRepeat()
}
