package com.local.mp3box.ui.nowplaying

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.local.mp3box.Mp3BoxApp
import com.local.mp3box.R
import com.local.mp3box.playback.PlayerUiState
import com.local.mp3box.ui.components.TrackTile
import com.local.mp3box.ui.player.PlayerViewModel
import com.local.mp3box.util.Duration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val factory = remember(context) { playerViewModelFactory(context) }
    val viewModel: PlayerViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val positionMs by viewModel.positionMs.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        viewModel.startTicker()
        onDispose { viewModel.stopTicker() }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.now_playing_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.close)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        if (state.currentTrackId == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.nothing_playing),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        } else {
            PlayerContent(
                state = state,
                positionMs = positionMs,
                onSeek = viewModel::seekTo,
                onTogglePlayPause = viewModel::togglePlayPause,
                onNext = viewModel::next,
                onPrevious = viewModel::previous,
                onToggleShuffle = viewModel::toggleShuffle,
                onCycleRepeat = viewModel::cycleRepeat,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 24.dp)
            )
        }
    }
}

@Composable
private fun PlayerContent(
    state: PlayerUiState,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    modifier: Modifier = Modifier
) {
    val durationMs = state.durationMs.coerceAtLeast(0L)
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val shownPosition = (dragValue ?: positionMs.toFloat()).coerceIn(0f, durationMs.toFloat())

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(8.dp))
        TrackTile(
            seed = state.currentTrackId?.toString().orEmpty(),
            size = 240.dp
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = state.currentTitle.orEmpty(),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(
                R.string.track_of,
                state.currentIndex + 1,
                state.queueSize
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Slider(
            value = shownPosition,
            onValueChange = { dragValue = it },
            onValueChangeFinished = {
                dragValue?.let { onSeek(it.toLong()) }
                dragValue = null
            },
            valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
            enabled = durationMs > 0
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = Duration.formatTrack(shownPosition.toLong()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (durationMs > 0) Duration.formatTrack(durationMs) else "--:--",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onToggleShuffle) {
                Icon(
                    imageVector = Icons.Filled.Shuffle,
                    contentDescription = stringResource(
                        if (state.shuffle) R.string.shuffle_on else R.string.shuffle_off
                    ),
                    tint = if (state.shuffle) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            IconButton(onClick = onPrevious) {
                Icon(
                    imageVector = Icons.Filled.SkipPrevious,
                    contentDescription = stringResource(R.string.previous)
                )
            }
            FilledIconButton(
                onClick = onTogglePlayPause,
                modifier = Modifier.size(64.dp)
            ) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(
                        if (state.isPlaying) R.string.pause else R.string.play
                    ),
                    modifier = Modifier.size(32.dp)
                )
            }
            IconButton(onClick = onNext) {
                Icon(
                    imageVector = Icons.Filled.SkipNext,
                    contentDescription = stringResource(R.string.next)
                )
            }
            val repeatOne = state.repeatMode == androidx.media3.common.Player.REPEAT_MODE_ONE
            IconButton(onClick = onCycleRepeat) {
                Icon(
                    imageVector = if (repeatOne) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    contentDescription = stringResource(
                        if (repeatOne) R.string.repeat_one else R.string.repeat_all
                    ),
                    tint = if (repeatOne) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

private fun playerViewModelFactory(context: Context): ViewModelProvider.Factory {
    val container = (context.applicationContext as Mp3BoxApp).container
    return viewModelFactory {
        initializer { PlayerViewModel(container.playerRepository) }
    }
}
